package com.homekey.tv.data.server

import android.util.Log
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.models.PinnedEntityConfig
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebSetupServer(
    port: Int = 8124,
    private val preferencesManager: PreferencesManager,
    private val onConfigUpdated: () -> Unit
) : NanoHTTPD(port) {

    private val tag = "WebSetupServer"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Pairing-PIN brute-force back-off. The PIN only has ~1e6 combinations, so after a few wrong
    // attempts the server stops accepting new guesses for a minute.
    private var failedPinAttempts = 0
    private var lockoutUntil = 0L

    private companion object {
        const val MAX_PIN_ATTEMPTS = 8
        const val PIN_LOCKOUT_MS = 60_000L
    }

    private fun isPinAccepted(pin: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) return false
        val ok = !pin.isNullOrBlank() && pin == preferencesManager.pairingPin.value
        if (ok) {
            failedPinAttempts = 0
        } else {
            failedPinAttempts++
            if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                lockoutUntil = now + PIN_LOCKOUT_MS
                failedPinAttempts = 0
            }
        }
        return ok
    }

    private fun forbidden(): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Invalid or missing pairing PIN. Enter the code shown on the TV screen.\"}")

    private fun jsonError(status: Response.Status, message: String): Response {
        val body = buildJsonObject { put("error", message) }.toString()
        return newFixedLengthResponse(status, "application/json", body)
    }

    private fun jsonSuccess(message: String): Response {
        val body = buildJsonObject {
            put("success", true)
            put("message", message)
        }.toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    @Serializable
    data class SetupPayload(
        val serverUrl: String = "",
        val token: String = "",
        val layout: String? = null,
        val pin: String = "",
        val themePreset: String? = null,
        val customThemeColors: Map<String, String>? = null,
        // null = client omitted the field (keep current selection); empty list = user cleared it.
        val pinnedEntities: List<PinnedItemPayload>? = null
    )

    @Serializable
    data class FetchPayload(
        val serverUrl: String = "",
        val token: String = "",
        val pin: String = ""
    )

    @Serializable
    data class PinnedItemPayload(
        val entityId: String,
        val customName: String? = null,
        val category: String? = null,
        val customIcon: String? = null
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(tag, "HTTP Request: $method $uri")

        return when {
            method == Method.GET && uri == "/" -> {
                newFixedLengthResponse(Response.Status.OK, "text/html", getWebHtml())
            }
            method == Method.GET && uri == "/api/status" -> {
                // Built as structured JSON — never string interpolation (serverUrl is user data).
                val body = buildJsonObject {
                    put("configured", preferencesManager.isConfigured)
                    put("serverUrl", preferencesManager.serverUrl.value)
                    put("pinnedCount", preferencesManager.pinnedEntities.value.size)
                    put("layout", preferencesManager.panelLayout.value)
                    put("themePreset", preferencesManager.themePreset.value)
                }.toString()
                newFixedLengthResponse(Response.Status.OK, "application/json", body)
            }
            method == Method.POST && uri == "/api/fetch-entities" -> {
                try {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    val payload = json.decodeFromString<FetchPayload>(postData)

                    if (!isPinAccepted(payload.pin)) {
                        return forbidden()
                    }

                    // Credentials typed into the page take priority (covers first-time setup and
                    // token rotation/repair). The saved token is only ever sent to the saved server
                    // — never to a URL the requester chose — so a LAN client that got past the PIN
                    // cannot use the TV's token to probe arbitrary internal hosts (SSRF).
                    val savedUrl = preferencesManager.serverUrl.value.trim().removeSuffix("/")
                    val savedToken = preferencesManager.accessToken.value
                    val typedUrl = payload.serverUrl.trim().removeSuffix("/")
                    val typedToken = payload.token.trim()

                    val cleanUrl: String
                    val token: String
                    if (typedToken.isNotBlank()) {
                        cleanUrl = typedUrl.ifBlank { savedUrl }
                        token = typedToken
                    } else {
                        if (typedUrl.isNotBlank() && typedUrl != savedUrl) {
                            return jsonError(
                                Response.Status.BAD_REQUEST,
                                "Enter an access token for that server - the TV's saved token only works with its saved Home Assistant."
                            )
                        }
                        cleanUrl = savedUrl
                        token = savedToken
                    }

                    if (cleanUrl.isBlank() || token.isBlank()) {
                        return jsonError(
                            Response.Status.BAD_REQUEST,
                            "Enter your Home Assistant URL and access token to fetch entities."
                        )
                    }

                    val request = Request.Builder()
                        .url("$cleanUrl/api/states")
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: "[]"

                    if (response.isSuccessful) {
                        newFixedLengthResponse(Response.Status.OK, "application/json", responseBody)
                    } else {
                        jsonError(Response.Status.BAD_REQUEST, "Home Assistant returned HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error proxying entity fetch", e)
                    jsonError(Response.Status.INTERNAL_ERROR, "Failed to connect to Home Assistant")
                }
            }
            method == Method.POST && uri == "/api/save" -> {
                try {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""

                    val payload = json.decodeFromString<SetupPayload>(postData)

                    if (!isPinAccepted(payload.pin)) {
                        return forbidden()
                    }

                    // The page never receives the token back (it is not embedded in the HTML), so
                    // blank fields mean "keep the value already stored on the TV".
                    val savedUrl = preferencesManager.serverUrl.value.trim().removeSuffix("/")
                    val savedToken = preferencesManager.accessToken.value
                    val incomingUrl = payload.serverUrl.trim().removeSuffix("/")
                    val incomingToken = payload.token.trim()
                    if (incomingUrl.isBlank() && incomingToken.isBlank()) {
                        return jsonError(Response.Status.BAD_REQUEST, "Enter your Home Assistant URL to continue.")
                    }
                    // Switching to a different server requires a fresh token. Reusing the saved token
                    // for a new host would silently send the Home Assistant credentials to a server
                    // the user never authorised.
                    if (incomingUrl.isNotBlank() && incomingUrl != savedUrl &&
                        incomingToken.isBlank() && savedToken.isNotBlank()
                    ) {
                        return jsonError(
                            Response.Status.BAD_REQUEST,
                            "Enter an access token for the new server to switch Home Assistant servers."
                        )
                    }
                    val effectiveUrl = incomingUrl.ifBlank { savedUrl }
                    val effectiveToken = incomingToken.ifBlank { savedToken }
                    preferencesManager.saveServerConfig(effectiveUrl, effectiveToken)

                    if (!payload.layout.isNullOrBlank()) {
                        preferencesManager.setPanelLayout(payload.layout)
                    }

                    if (!payload.themePreset.isNullOrBlank()) {
                        preferencesManager.setThemePreset(payload.themePreset)
                    }

                    if (payload.customThemeColors != null) {
                        preferencesManager.setCustomDomainColors(payload.customThemeColors)
                    }

                    // null = field absent (leave dock untouched); an explicit empty list clears it.
                    if (payload.pinnedEntities != null) {
                        val pinned = payload.pinnedEntities.mapIndexed { idx, item ->
                            PinnedEntityConfig(
                                entityId = item.entityId,
                                customName = item.customName,
                                customCategory = item.category,
                                customIcon = item.customIcon,
                                order = idx
                            )
                        }
                        preferencesManager.savePinnedEntities(pinned)
                    }

                    onConfigUpdated()
                    jsonSuccess("Configuration saved to Google TV!")
                } catch (e: Exception) {
                    Log.e(tag, "Error saving setup config", e)
                    jsonError(Response.Status.INTERNAL_ERROR, "Failed to save configuration")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun getWebHtml(): String {
        val currentUrl = preferencesManager.serverUrl.value
        val isTokenSaved = preferencesManager.accessToken.value.isNotBlank()
        // NOTE: the long-lived access token is deliberately NOT embedded in this page. It is never
        // sent back to the browser; once the TV is configured, /api/* use the saved token server-side.
        val currentPinned = json.encodeToString(preferencesManager.pinnedEntities.value)
        val currentLayout = preferencesManager.panelLayout.value
        val currentThemePreset = preferencesManager.themePreset.value
        val currentCustomColors = json.encodeToString(preferencesManager.customThemeColors.value)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HomeKey TV Setup</title>
    <style>
        :root {
            --bg: #0f172a;
            --surface: #1e293b;
            --surface-card: #334155;
            --primary: #0284c7;
            --primary-hover: #0369a1;
            --accent: #38bdf8;
            --text: #f8fafc;
            --text-muted: #94a3b8;
            --success: #22c55e;
            --border: #475569;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
            background-color: var(--bg);
            color: var(--text);
            padding: 16px;
            line-height: 1.5;
        }
        .container {
            max-width: 680px;
            margin: 0 auto;
            background: var(--surface);
            border-radius: 16px;
            padding: 20px;
            box-shadow: 0 10px 25px rgba(0,0,0,0.5);
            border: 1px solid var(--border);
        }
        .setup-card {
            background: #151f32;
            border: 1px solid var(--border);
            border-radius: 12px;
            margin-bottom: 16px;
            overflow: hidden;
            transition: border-color 0.2s;
        }
        .setup-card:focus-within {
            border-color: var(--accent);
        }
        .card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 14px 16px;
            background: rgba(255, 255, 255, 0.03);
            cursor: pointer;
            user-select: none;
            transition: background 0.15s;
        }
        .card-header:hover {
            background: rgba(255, 255, 255, 0.07);
        }
        .card-title {
            font-size: 14px;
            font-weight: 700;
            color: var(--accent);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .card-badge {
            font-size: 11px;
            background: rgba(2, 132, 199, 0.25);
            border: 1px solid var(--primary);
            color: var(--accent);
            padding: 2px 8px;
            border-radius: 12px;
            font-weight: 600;
        }
        .card-chevron {
            font-size: 11px;
            color: var(--text-muted);
            transition: transform 0.2s ease;
        }
        .setup-card.collapsed .card-chevron {
            transform: rotate(-90deg);
        }
        .card-body {
            padding: 16px;
        }
        .setup-card.collapsed .card-body {
            display: none;
        }
        .theme-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
            gap: 10px;
            margin-top: 8px;
            margin-bottom: 14px;
        }
        .theme-card {
            padding: 12px;
            border: 1px solid var(--border);
            border-radius: 10px;
            background: #0b1120;
            cursor: pointer;
            transition: all 0.15s;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            gap: 6px;
        }
        .theme-card:hover {
            border-color: var(--accent);
            background: #131d31;
            transform: translateY(-2px);
        }
        .theme-card.active {
            border-color: var(--accent);
            background: rgba(2, 132, 199, 0.2);
            box-shadow: 0 0 0 1px var(--accent);
        }
        .theme-card-title {
            font-weight: 700;
            font-size: 13px;
            color: var(--text);
        }
        .theme-card-desc {
            font-size: 11px;
            color: var(--text-muted);
            line-height: 1.3;
        }
        .theme-swatches-preview {
            display: flex;
            gap: 5px;
            margin-top: 4px;
        }
        .theme-swatch-dot {
            width: 13px;
            height: 13px;
            border-radius: 50%;
            border: 1px solid rgba(255, 255, 255, 0.25);
            display: inline-block;
        }
        .color-picker-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
            gap: 8px;
        }
        .color-picker-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 10px;
            background: #0b1120;
            border: 1px solid var(--border);
            border-radius: 8px;
        }
        .color-picker-label {
            font-size: 12px;
            font-weight: 500;
            color: var(--text);
        }
        .color-picker-input-group {
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .color-picker-hex {
            font-size: 11px;
            font-family: monospace;
            color: var(--text-muted);
        }
        .color-input {
            width: 34px;
            height: 28px;
            padding: 0;
            border: 1px solid var(--border);
            border-radius: 6px;
            cursor: pointer;
            background: transparent;
        }
        h1 {
            font-size: 22px;
            color: var(--accent);
            margin-bottom: 6px;
            font-weight: 700;
        }
        .subtitle {
            color: var(--text-muted);
            font-size: 13px;
            margin-bottom: 20px;
        }
        .form-group {
            margin-bottom: 16px;
        }
        label {
            display: block;
            font-weight: 600;
            font-size: 12px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 6px;
            color: var(--text-muted);
        }
        input, select {
            width: 100%;
            padding: 12px 14px;
            border-radius: 8px;
            border: 1px solid var(--border);
            background: #0b1120;
            color: var(--text);
            font-size: 15px;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus, select:focus { border-color: var(--accent); }
        .help-text {
            font-size: 11px;
            color: var(--text-muted);
            margin-top: 4px;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            padding: 14px;
            border-radius: 10px;
            border: none;
            background: var(--primary);
            color: white;
            font-weight: 700;
            font-size: 15px;
            cursor: pointer;
            transition: all 0.2s;
            margin-top: 10px;
        }
        .btn:hover { background: var(--primary-hover); }
        .btn-secondary {
            background: var(--surface-card);
            color: var(--text);
            margin-top: 8px;
        }
        .btn-secondary:hover { background: #475569; }
        .section-title {
            font-size: 15px;
            font-weight: 700;
            margin: 20px 0 10px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 6px;
            color: var(--accent);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .domain-tabs {
            display: flex;
            gap: 6px;
            overflow-x: auto;
            margin-bottom: 10px;
            padding-bottom: 4px;
        }
        .domain-tab {
            padding: 6px 12px;
            border-radius: 20px;
            background: #0b1120;
            border: 1px solid var(--border);
            color: var(--text-muted);
            font-size: 12px;
            cursor: pointer;
            white-space: nowrap;
        }
        .domain-tab.active {
            background: var(--primary);
            color: white;
            border-color: var(--accent);
        }
        .entity-list {
            max-height: 380px;
            overflow-y: auto;
            border: 1px solid var(--border);
            border-radius: 8px;
            background: #0b1120;
            padding: 6px;
        }
        .entity-item {
            display: flex;
            flex-direction: column;
            padding: 10px 8px;
            border-radius: 8px;
            border-bottom: 1px solid rgba(255,255,255,0.05);
            gap: 8px;
            transition: background 0.15s;
        }
        .entity-item:hover { background: var(--surface-card); }
        .entity-header {
            display: flex;
            align-items: center;
            gap: 12px;
            cursor: pointer;
        }
        .entity-item input[type="checkbox"] {
            width: 20px;
            height: 20px;
            cursor: pointer;
        }
        .entity-info { flex: 1; min-width: 0; }
        .entity-name { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .entity-id { font-size: 11px; color: var(--text-muted); }
        .entity-custom-row {
            display: none;
            padding-left: 32px;
            gap: 10px;
            align-items: center;
            flex-wrap: wrap;
        }
        .entity-item.selected .entity-custom-row {
            display: flex;
        }
        .custom-name-wrapper {
            flex: 1;
            min-width: 180px;
        }
        .custom-name-input {
            width: 100%;
            padding: 7px 10px;
            border-radius: 8px;
            border: 1px solid var(--border);
            background: #0b1120;
            color: var(--text);
            font-size: 13px;
        }
        .custom-name-input:focus {
            border-color: var(--accent);
        }
        .icon-trigger-btn {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            padding: 6px 12px;
            border-radius: 8px;
            background: #1e293b;
            border: 1px solid var(--border);
            color: var(--text);
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s;
        }
        .icon-trigger-btn:hover {
            border-color: var(--accent);
            background: #273549;
        }
        .icon-trigger-svg {
            width: 18px;
            height: 18px;
            fill: none;
            stroke: var(--accent);
            stroke-width: 2;
            stroke-linecap: round;
            stroke-linejoin: round;
        }
        .alert {
            padding: 12px;
            border-radius: 8px;
            margin-bottom: 16px;
            display: none;
            font-size: 13px;
        }
        .alert-success { background: rgba(34, 197, 94, 0.2); border: 1px solid var(--success); color: var(--success); }
        .alert-error { background: rgba(239, 68, 68, 0.2); border: 1px solid #ef4444; color: #fca5a5; }
        .quick-actions {
            display: flex;
            gap: 8px;
            margin-bottom: 8px;
        }
        .quick-btn {
            padding: 4px 10px;
            font-size: 11px;
            border-radius: 6px;
            background: var(--surface-card);
            color: var(--text);
            border: 1px solid var(--border);
            cursor: pointer;
        }

        /* Modal Styles */
        .modal-backdrop {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0, 0, 0, 0.75);
            display: none;
            align-items: center;
            justify-content: center;
            z-index: 1000;
            padding: 16px;
            backdrop-filter: blur(4px);
        }
        .modal-backdrop.open { display: flex; }
        .modal-box {
            background: #1e293b;
            border-radius: 16px;
            border: 1px solid var(--border);
            width: 100%;
            max-width: 620px;
            max-height: 85vh;
            display: flex;
            flex-direction: column;
            box-shadow: 0 20px 40px rgba(0,0,0,0.6);
            overflow: hidden;
        }
        .modal-header {
            padding: 16px 20px;
            border-bottom: 1px solid var(--border);
            display: flex;
            align-items: center;
            justify-content: space-between;
        }
        .modal-title { font-size: 17px; font-weight: 700; color: var(--accent); }
        .modal-close {
            background: none;
            border: none;
            color: var(--text-muted);
            font-size: 22px;
            cursor: pointer;
            padding: 4px 8px;
            line-height: 1;
        }
        .modal-close:hover { color: white; }
        .modal-body {
            padding: 16px;
            overflow-y: auto;
            flex: 1;
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .modal-cat-tabs {
            display: flex;
            gap: 6px;
            overflow-x: auto;
            padding-bottom: 4px;
        }
        .modal-cat-tab {
            padding: 4px 10px;
            border-radius: 14px;
            background: #0f172a;
            border: 1px solid var(--border);
            font-size: 11px;
            color: var(--text-muted);
            cursor: pointer;
            white-space: nowrap;
        }
        .modal-cat-tab.active {
            background: var(--primary);
            color: white;
            border-color: var(--accent);
        }
        .icon-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(95px, 1fr));
            gap: 10px;
            max-height: 380px;
            overflow-y: auto;
            padding: 4px;
        }
        .icon-card {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 12px 6px;
            background: #0f172a;
            border: 1px solid var(--border);
            border-radius: 10px;
            cursor: pointer;
            transition: all 0.15s;
            text-align: center;
            gap: 6px;
        }
        .icon-card:hover {
            border-color: var(--accent);
            background: #182234;
            transform: translateY(-2px);
        }
        .icon-card.active {
            border-color: var(--accent);
            background: rgba(2, 132, 199, 0.25);
        }
        .icon-card-svg {
            width: 26px;
            height: 26px;
            fill: none;
            stroke: var(--text);
            stroke-width: 2;
            stroke-linecap: round;
            stroke-linejoin: round;
        }
        .icon-card.active .icon-card-svg, .icon-card:hover .icon-card-svg {
            stroke: var(--accent);
        }
        .icon-card-label {
            font-size: 10px;
            color: var(--text-muted);
            font-weight: 500;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            width: 100%;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>HomeKey TV Setup</h1>
        <p class="subtitle">Configure Home Assistant & Appearance for your Google TV</p>

        <div id="alertBox" class="alert"></div>

        <!-- Section 1: Home Assistant Connection (Collapsible) -->
        <div class="setup-card" id="card-connection">
            <div class="card-header" onclick="toggleCard('card-connection')">
                <div class="card-title">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7"/></svg>
                    <span>Home Assistant Connection</span>
                </div>
                <span class="card-chevron">&#9660;</span>
            </div>
            <div class="card-body">
                <div class="form-group">
                    <label>Home Assistant URL</label>
                    <input type="url" id="haUrl" placeholder="http://192.168.1.100:8123 or https://xxx.ui.nabu.casa" value="$currentUrl">
                    <p class="help-text">Local IP or your Nabu Casa remote URL</p>
                </div>

                <div class="form-group">
                    <label>Long-Lived Access Token</label>
                    <input type="password" id="haToken" placeholder="${if (isTokenSaved) "•••••••• (Saved on TV - leave blank to keep)" else "eyJhbGciOi..."}" autocomplete="off" oninput="persistCredentials()">
                    <p class="help-text">Found in Home Assistant &gt; Profile &gt; Long-Lived Access Tokens. Leave blank if already configured.</p>
                </div>

                <div class="form-group">
                    <label>Pairing PIN</label>
                    <input type="text" id="pairingPin" inputmode="numeric" maxlength="6" placeholder="000000" autocomplete="one-time-code" oninput="persistCredentials()">
                    <p class="help-text">6-digit code shown on the TV under Settings &gt; Instant Phone Setup</p>
                </div>

                <button type="button" id="btnFetch" class="btn btn-secondary" onclick="fetchEntities()">Fetch Entities from HA</button>
            </div>
        </div>

        <!-- Section 2: Menu Layout Position (Collapsible) -->
        <div class="setup-card" id="card-layout">
            <div class="card-header" onclick="toggleCard('card-layout')">
                <div class="card-title">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 15h18"/></svg>
                    <span>Menu Layout Position</span>
                </div>
                <span class="card-chevron">&#9660;</span>
            </div>
            <div class="card-body">
                <div class="form-group" style="margin-bottom: 0;">
                    <label>Dock Layout Position</label>
                    <select id="layoutStyle">
                        <option value="DOCK_BOTTOM" ${if (currentLayout == "DOCK_BOTTOM" || currentLayout == "DOCK") "selected" else ""}>Bottom Dock (tvQuickActions style)</option>
                        <option value="DOCK_LEFT" ${if (currentLayout == "DOCK_LEFT") "selected" else ""}>Left Dock (Vertical)</option>
                        <option value="DOCK_RIGHT" ${if (currentLayout == "DOCK_RIGHT" || currentLayout == "SIDE_PANEL") "selected" else ""}>Right Dock (Vertical)</option>
                    </select>
                </div>
            </div>
        </div>

        <!-- Section 3: Themes & Domain Colors (Collapsible) -->
        <div class="setup-card" id="card-theme">
            <div class="card-header" onclick="toggleCard('card-theme')">
                <div class="card-title">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4"/><line x1="4.93" y1="4.93" x2="9.17" y2="9.17"/><line x1="14.83" y1="14.83" x2="19.07" y2="19.07"/><line x1="14.83" y1="9.17" x2="19.07" y2="4.93"/><line x1="4.93" y1="19.07" x2="9.17" y2="14.83"/></svg>
                    <span>Theme & Domain Colors</span>
                </div>
                <span class="card-chevron">&#9660;</span>
            </div>
            <div class="card-body">
                <label>Theme Preset</label>
                <div class="theme-grid" id="themeGrid">
                    <!-- Dynamic Theme Presets -->
                </div>

                <div id="customColorsSection" style="margin-top: 16px;">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                        <label style="margin-bottom: 0;">Domain Color Customizer</label>
                        <button type="button" class="quick-btn" style="padding: 3px 10px;" onclick="resetCustomColors()">Reset to Preset</button>
                    </div>
                    <p class="help-text" style="margin-bottom: 12px;">Click any color swatch to pick custom hex colors for each entity domain.</p>
                    <div class="color-picker-grid" id="colorPickerGrid">
                        <!-- Dynamic Domain Color Pickers -->
                    </div>
                </div>
            </div>
        </div>

        <!-- Section 4: Pinned Entities (Collapsible) -->
        <div class="setup-card" id="card-entities">
            <div class="card-header" onclick="toggleCard('card-entities')">
                <div class="card-title">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                    <span>Selected Entities for TV Menu</span>
                    <span id="selectedCountBadge" class="card-badge">0 selected</span>
                </div>
                <span class="card-chevron">&#9660;</span>
            </div>
            <div class="card-body">
                <div class="quick-actions">
                    <button type="button" class="quick-btn" onclick="selectAllFiltered(true)">Select All</button>
                    <button type="button" class="quick-btn" onclick="selectAllFiltered(false)">Clear All</button>
                </div>

                <div id="domainTabs" class="domain-tabs">
                    <div class="domain-tab active" onclick="filterDomain('ALL')">All</div>
                    <div id="selectedTab" class="domain-tab" onclick="filterDomain('SELECTED')" style="background: rgba(2, 132, 199, 0.2); border-color: var(--primary);">Selected (0)</div>
                    <div class="domain-tab" onclick="filterDomain('light')">Lights</div>
                    <div class="domain-tab" onclick="filterDomain('switch')">Switches</div>
                    <div class="domain-tab" onclick="filterDomain('scene')">Scenes</div>
                    <div class="domain-tab" onclick="filterDomain('script')">Scripts</div>
                    <div class="domain-tab" onclick="filterDomain('climate')">Climate</div>
                    <div class="domain-tab" onclick="filterDomain('camera')">Cameras</div>
                    <div class="domain-tab" onclick="filterDomain('media_player')">Media</div>
                    <div class="domain-tab" onclick="filterDomain('cover')">Covers</div>
                    <div class="domain-tab" onclick="filterDomain('fan')">Fans</div>
                </div>

                <input type="text" id="entityFilter" placeholder="Search devices by name or id..." oninput="renderEntitiesList()" style="margin-bottom: 8px;">

                <div id="entitiesContainer" class="entity-list">
                    <p style="padding: 16px; text-align: center; color: var(--text-muted);">Tap Fetch Entities to load devices from Home Assistant</p>
                </div>
            </div>
        </div>

        <button type="button" class="btn" onclick="saveConfig()">Save Configuration to TV</button>
    </div>

    <!-- Visual Icon Picker Modal -->
    <div id="iconModal" class="modal-backdrop" onclick="closeIconModal(event)">
        <div class="modal-box" onclick="event.stopPropagation()">
            <div class="modal-header">
                <div>
                    <div class="modal-title">Choose Icon</div>
                    <div id="modalSubtitle" style="font-size:11px; color:var(--text-muted);">Select an icon for device</div>
                </div>
                <button class="modal-close" onclick="closeIconModal()">&times;</button>
            </div>
            <div class="modal-body">
                <input type="text" id="modalSearch" placeholder="Search icons (e.g. lamp, sofa, fan, tv, ac)..." oninput="renderIconGrid()">
                <div class="modal-cat-tabs" id="modalCatTabs">
                    <div class="modal-cat-tab active" onclick="filterModalCat('all')">All Icons</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('light')">Lighting</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('room')">Rooms & Furniture</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('climate')">Climate & Air</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('cover')">Doors & Covers</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('security')">Security & Access</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('power')">Power & Energy</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('appliance')">Appliances</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('media')">Media & Audio</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('vehicle')">Vehicles</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('outdoor')">Outdoor & Garden</div>
                    <div class="modal-cat-tab" onclick="filterModalCat('sensor')">Sensors & Automations</div>
                </div>
                <div id="iconGridContainer" class="icon-grid"></div>
                <button type="button" class="quick-btn" style="margin-top:8px; align-self:flex-start; padding:6px 14px;" onclick="resetIconOverride()">Reset to Default Icon (Auto)</button>
            </div>
        </div>
    </div>

    <script>
        function toggleCard(cardId) {
            const card = document.getElementById(cardId);
            if (card) {
                card.classList.toggle('collapsed');
            }
        }

        const ICON_GALLERY = [
            // Lighting
            { id: 'lightbulb', label: 'Lightbulb', cat: 'light', svg: '<path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-1 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/>' },
            { id: 'lamp', label: 'Desk Lamp', cat: 'light', svg: '<path d="M9 2h6l3 7H6l3-7z"/><path d="M12 9v11"/><path d="M8 20h8"/>' },
            { id: 'ceiling_light', label: 'Ceiling Light', cat: 'light', svg: '<path d="M12 2v5"/><path d="M6 12a6 6 0 0 0 12 0H6z"/><path d="M9 12v1a3 3 0 0 0 6 0v-1"/>' },
            { id: 'strip', label: 'LED Strip', cat: 'light', svg: '<rect x="2" y="9" width="20" height="6" rx="2"/><circle cx="6" cy="12" r="1"/><circle cx="10" cy="12" r="1"/><circle cx="14" cy="12" r="1"/><circle cx="18" cy="12" r="1"/>' },
            { id: 'spotlight', label: 'Spotlight', cat: 'light', svg: '<path d="m14 2-8 11h6l-2 9 10-12h-6l2-8z"/>' },
            { id: 'sunny', label: 'Sun / Bright', cat: 'light', svg: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>' },
            { id: 'nightlight', label: 'Night Light', cat: 'light', svg: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>' },

            // Rooms & Furniture
            { id: 'sofa', label: 'Sofa / Couch', cat: 'room', svg: '<path d="M20 9V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v2"/><path d="M2 11v5a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z"/><path d="M4 18v2"/><path d="M20 18v2"/>' },
            { id: 'bed', label: 'Bed / Bedroom', cat: 'room', svg: '<path d="M2 4v16"/><path d="M2 8h18a2 2 0 0 1 2 2v10"/><path d="M2 17h20"/><path d="M6 8v4"/>' },
            { id: 'chair', label: 'Chair', cat: 'room', svg: '<path d="M7 4v8h10V4a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2z"/><path d="M5 12h14a2 2 0 0 1 2 2v2H3v-2a2 2 0 0 1 2-2z"/><path d="M6 16v5"/><path d="M18 16v5"/>' },
            { id: 'table', label: 'Dining Table', cat: 'room', svg: '<path d="M3 7h18"/><path d="M4 7l2 14"/><path d="M20 7l-2 14"/><path d="M7 14h10"/>' },
            { id: 'desk', label: 'Desk / Office', cat: 'room', svg: '<rect x="3" y="6" width="18" height="4" rx="1"/><path d="M5 10v10"/><path d="M19 10v10"/><path d="M14 10v10"/><path d="M14 14h5"/><path d="M14 17h5"/>' },
            { id: 'kitchen', label: 'Kitchen', cat: 'room', svg: '<rect x="4" y="2" width="16" height="20" rx="2"/><path d="M4 10h16"/><path d="M9 6v2"/><path d="M9 14v4"/>' },
            { id: 'bath', label: 'Bathtub', cat: 'room', svg: '<path d="M2 12h20v4a5 5 0 0 1-5 5H7a5 5 0 0 1-5-5v-4z"/><path d="M4 12V5a2 2 0 0 1 2-2h2"/>' },
            { id: 'shower', label: 'Shower', cat: 'room', svg: '<path d="M4 4h7a4 4 0 0 1 4 4v2"/><path d="M12 14l-1 2"/><path d="M15 14l-1 2"/><path d="M18 14l-1 2"/><path d="M11 10h8l-1 4h-6z"/>' },

            // Climate & Air
            { id: 'ac', label: 'AC / Cold', cat: 'climate', svg: '<path d="M12 2v20"/><path d="M2 12h20"/><path d="m4.93 4.93 14.14 14.14"/><path d="m19.07 4.93-14.14 14.14"/><path d="M8 4l4 4 4-4"/><path d="M8 20l4-4 4 4"/>' },
            { id: 'fan', label: 'Fan / Air', cat: 'climate', svg: '<path d="M12 12c2-2 5-1 6 1s-1 5-3 5c-1 0-2-1-3-3z"/><path d="M12 12c-2-2-1-5 1-6s5 1 5 3c0 1-1 2-3 3z"/><path d="M12 12c-2 2-5 1-6-1s1-5 3-5c1 0 2 1 3 3z"/><path d="M12 12c2 2 1 5-1 6s-5-1-5-3c0-1 1-2 3-3z"/>' },
            { id: 'heater', label: 'Heater / Fire', cat: 'climate', svg: '<path d="M12 2c1 3 4 5 4 9a6 6 0 0 1-12 0c0-3 2-6 4-8 1 2 2 3 4-1z"/>' },
            { id: 'thermostat', label: 'Thermostat', cat: 'climate', svg: '<path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z"/>' },
            { id: 'fireplace', label: 'Fireplace', cat: 'climate', svg: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 21v-8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v8"/><path d="M12 15a1.5 1.5 0 0 1 1.5 1.5c0 1-.8 1.5-1.5 2.5-.7-1-1.5-1.5-1.5-2.5A1.5 1.5 0 0 1 12 15z"/>' },

            // Doors, Windows & Covers
            { id: 'door', label: 'Front Door', cat: 'cover', svg: '<path d="M4 21h16"/><path d="M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17"/><circle cx="14" cy="12" r="1"/>' },
            { id: 'door_sliding', label: 'Sliding Door', cat: 'cover', svg: '<path d="M3 21h18"/><path d="M4 21V4a1 1 0 0 1 1-1h7v18"/><path d="M12 3h7a1 1 0 0 1 1 1v17"/><path d="M9 12v2"/><path d="M15 12v2"/>' },
            { id: 'garage', label: 'Garage Door', cat: 'cover', svg: '<path d="M3 21h18"/><path d="M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16"/><path d="M8 9h8"/><path d="M8 13h8"/><path d="M8 17h8"/>' },
            { id: 'window', label: 'Window', cat: 'cover', svg: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M12 3v18"/><path d="M3 12h18"/>' },
            { id: 'curtains', label: 'Curtains', cat: 'cover', svg: '<path d="M2 3h20"/><path d="M4 3v17c1-2 2-3 4-3s3 1 4 3V3"/><path d="M20 3v17c-1-2-2-3-4-3s-3 1-4 3V3"/>' },
            { id: 'blinds', label: 'Blinds', cat: 'cover', svg: '<path d="M3 3h18"/><path d="M4 7h16"/><path d="M4 11h16"/><path d="M4 15h16"/><path d="M12 15v5"/><path d="M19 3v13"/>' },
            { id: 'roller_shade', label: 'Roller Shade', cat: 'cover', svg: '<rect x="3" y="3" width="18" height="4" rx="1"/><path d="M5 7v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7"/><path d="M12 18v3"/>' },

            // Security & Access
            { id: 'lock', label: 'Lock', cat: 'security', svg: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>' },
            { id: 'lock_open', label: 'Unlock', cat: 'security', svg: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/>' },
            { id: 'key', label: 'Key', cat: 'security', svg: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 8.3-8.3h3v3l-2 2v2l-2 2"/>' },
            { id: 'shield', label: 'Security Shield', cat: 'security', svg: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>' },
            { id: 'camera', label: 'CCTV Camera', cat: 'security', svg: '<path d="m22 7-6 4v2l6 4V7z"/><rect x="2" y="6" width="14" height="12" rx="2"/>' },
            { id: 'doorbell', label: 'Doorbell', cat: 'security', svg: '<rect x="6" y="3" width="12" height="18" rx="3"/><circle cx="12" cy="13" r="2.5"/><path d="M12 7h.01"/>' },
            { id: 'alarm', label: 'Alarm / Siren', cat: 'security', svg: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2 2"/><path d="m5 3-2 2"/><path d="m19 3 2 2"/>' },

            // Power & Energy
            { id: 'power', label: 'Power Switch', cat: 'power', svg: '<path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><path d="M12 2v10"/>' },
            { id: 'socket', label: 'Power Socket', cat: 'power', svg: '<circle cx="12" cy="12" r="10"/><line x1="9" y1="9.5" x2="9" y2="14.5"/><line x1="15" y1="9.5" x2="15" y2="14.5"/><circle cx="12" cy="16.5" r="1"/>' },
            { id: 'plug', label: 'Power Plug', cat: 'power', svg: '<path d="M6 3v6"/><path d="M18 3v6"/><path d="M4 9h16v3a8 8 0 0 1-7 7.93V22h-2v-2.07A8 8 0 0 1 4 12V9z"/>' },
            { id: 'bolt', label: 'Energy / Electricity', cat: 'power', svg: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>' },
            { id: 'battery', label: 'Battery', cat: 'power', svg: '<rect x="2" y="7" width="16" height="10" rx="2"/><line x1="20" y1="11" x2="20" y2="13"/><line x1="6" y1="12" x2="10" y2="12"/><line x1="10" y1="10" x2="14" y2="14"/>' },
            { id: 'solar', label: 'Solar Power', cat: 'power', svg: '<path d="M3 14h18l-3 7H6l-3-7z"/><path d="M8 14l1 7"/><path d="M16 14l-1 7"/><path d="M12 3v5"/><path d="m5 6 3 2"/><path d="m19 6-3 2"/>' },

            // Appliances
            { id: 'vacuum', label: 'Robot Vacuum', cat: 'appliance', svg: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/><path d="M12 3v3"/><path d="M12 18v3"/>' },
            { id: 'coffee', label: 'Coffee Maker', cat: 'appliance', svg: '<path d="M6 2h12v4H6z"/><path d="M6 6v14a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V6"/><path d="M10 13h4"/><path d="M9 16h6"/>' },
            { id: 'laundry', label: 'Washing Machine', cat: 'appliance', svg: '<rect x="4" y="2" width="16" height="20" rx="2"/><circle cx="12" cy="13" r="5"/><circle cx="12" cy="13" r="2"/><circle cx="8" cy="6" r="1"/><circle cx="12" cy="6" r="1"/>' },
            { id: 'microwave', label: 'Microwave', cat: 'appliance', svg: '<rect x="2" y="4" width="20" height="16" rx="2"/><rect x="5" y="7" width="10" height="10" rx="1"/><line x1="18" y1="8" x2="18" y2="8.01"/><line x1="18" y1="12" x2="18" y2="12.01"/><line x1="18" y1="16" x2="18" y2="16.01"/>' },
            { id: 'blender', label: 'Blender', cat: 'appliance', svg: '<path d="M8 2h8l-1 11H9L8 2z"/><rect x="6" y="17" width="12" height="5" rx="1"/><path d="M9 13v4"/><path d="M15 13v4"/>' },
            { id: 'iron', label: 'Iron', cat: 'appliance', svg: '<path d="M2 18h18a2 2 0 0 0 2-2c0-4-4-7-9-7H7l-5 9z"/><path d="M5 9V6a2 2 0 0 1 2-2h8"/>' },
            { id: 'grill', label: 'Outdoor Grill', cat: 'appliance', svg: '<path d="M4 10a8 8 0 0 0 16 0H4z"/><path d="M7 10v4"/><path d="M12 10v4"/><path d="M17 10v4"/><path d="M7 14l-3 8"/><path d="M17 14l3 8"/>' },

            // Media & Audio
            { id: 'tv', label: 'TV / Display', cat: 'media', svg: '<rect x="2" y="7" width="20" height="13" rx="2"/><path d="m17 2-5 5-5-5"/>' },
            { id: 'monitor', label: 'Monitor / PC', cat: 'media', svg: '<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8"/><path d="M12 17v4"/>' },
            { id: 'laptop', label: 'Laptop', cat: 'media', svg: '<path d="M20 16V5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v11"/><path d="M2 20h20"/>' },
            { id: 'smartphone', label: 'Smartphone', cat: 'media', svg: '<rect x="6" y="2" width="12" height="20" rx="2"/><circle cx="12" cy="18" r="1"/>' },
            { id: 'tablet', label: 'Tablet', cat: 'media', svg: '<rect x="4" y="2" width="16" height="20" rx="2"/><circle cx="12" cy="18" r="1"/>' },
            { id: 'speaker', label: 'Speaker', cat: 'media', svg: '<rect x="4" y="2" width="16" height="20" rx="2"/><circle cx="12" cy="14" r="4"/><circle cx="12" cy="6" r="1.5"/>' },
            { id: 'speaker_group', label: 'Speaker Group', cat: 'media', svg: '<rect x="8" y="2" width="13" height="16" rx="2"/><circle cx="14.5" cy="11" r="3"/><circle cx="14.5" cy="5" r="1"/><path d="M4 6v14a2 2 0 0 0 2 2h10"/>' },
            { id: 'headphones', label: 'Headphones', cat: 'media', svg: '<path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a1 1 0 0 1-1-1v-6a9 9 0 0 1 18 0v6a1 1 0 0 1-1 1h-2a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"/>' },
            { id: 'music', label: 'Music Note', cat: 'media', svg: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>' },
            { id: 'radio', label: 'Radio', cat: 'media', svg: '<rect x="2" y="8" width="20" height="14" rx="2"/><circle cx="16" cy="15" r="3"/><path d="M6 12h4"/><path d="M6 16h4"/><path d="m4 8 10-6"/>' },
            { id: 'mic', label: 'Microphone', cat: 'media', svg: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><line x1="12" y1="17" x2="12" y2="22"/><line x1="8" y1="22" x2="16" y2="22"/>' },
            { id: 'gamepad', label: 'Gamepad', cat: 'media', svg: '<path d="M6 12h4m-2-2v4M15 11h.01M18 13h.01M17.32 5H6.68A4.68 4.68 0 0 0 2 9.68v4.64A4.68 4.68 0 0 0 6.68 19l2.4-2h5.84l2.4 2A4.68 4.68 0 0 0 22 14.32V9.68A4.68 4.68 0 0 0 17.32 5z"/>' },

            // Vehicles
            { id: 'car', label: 'Car / Vehicle', cat: 'vehicle', svg: '<path d="M5 17a2 2 0 1 0 4 0 2 2 0 0 0-4 0zm10 0a2 2 0 1 0 4 0 2 2 0 0 0-4 0z"/><path d="M3 9l2-5h14l2 5v7H3V9zm0 0h18"/>' },
            { id: 'electric_car', label: 'Electric Car', cat: 'vehicle', svg: '<path d="M5 17a2 2 0 1 0 4 0 2 2 0 0 0-4 0zm10 0a2 2 0 1 0 4 0 2 2 0 0 0-4 0z"/><path d="M3 9l2-5h14l2 5v7H3V9zm0 0h18"/><path d="M12 2v3"/><path d="M10 3.5l4 0"/>' },
            { id: 'ev_station', label: 'EV Charger', cat: 'vehicle', svg: '<rect x="3" y="3" width="10" height="18" rx="2"/><path d="M6 7h4"/><path d="M13 10h3a2 2 0 0 1 2 2v5a2 2 0 0 0 2 2 2 2 0 0 0 2-2v-7l-2-2"/>' },
            { id: 'bike', label: 'Bicycle', cat: 'vehicle', svg: '<circle cx="5.5" cy="17.5" r="3.5"/><circle cx="18.5" cy="17.5" r="3.5"/><path d="M15 6a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm-3 11.5L9 9h3l3 4.5"/><path d="m14 9-3-5H8"/>' },
            { id: 'motorcycle', label: 'Motorcycle', cat: 'vehicle', svg: '<circle cx="5" cy="16" r="3"/><circle cx="19" cy="16" r="3"/><path d="M12 16h3l3-7h-4l-3 4H8l-3 3"/><path d="M13 6h3"/>' },

            // Outdoor & Garden
            { id: 'yard', label: 'Garden / Plants', cat: 'outdoor', svg: '<path d="M12 22V8"/><path d="M5 12H2a10 10 0 0 1 10-10"/><path d="M19 12h3a10 10 0 0 0-10-10"/><path d="M12 14c2.5-3 5-3 8-3"/><path d="M12 14c-2.5-3-5-3-8-3"/>' },
            { id: 'balcony', label: 'Balcony', cat: 'outdoor', svg: '<path d="M4 10V4a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v6"/><path d="M2 10h20v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V10z"/><path d="M6 10v11"/><path d="M10 10v11"/><path d="M14 10v11"/><path d="M18 10v11"/>' },
            { id: 'pool', label: 'Swimming Pool', cat: 'outdoor', svg: '<path d="M2 15c2 0 3-1 5-1s3 1 5 1 3-1 5-1 3 1 5 1"/><path d="M2 19c2 0 3-1 5-1s3 1 5 1 3-1 5-1 3 1 5 1"/><path d="M15 5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm2 2-3 5h4l2-3"/>' },
            { id: 'deck', label: 'Patio / Deck', cat: 'outdoor', svg: '<path d="M12 2v6"/><path d="m4 8 8-6 8 6"/><path d="M4 8v13"/><path d="M20 8v13"/><path d="M8 12h8"/><path d="M8 16h8"/>' },
            { id: 'fence', label: 'Gate / Fence', cat: 'outdoor', svg: '<path d="M4 6 6 4l2 2v14H4z"/><path d="M10 6 12 4l2 2v14h-4z"/><path d="M16 6 18 4l2 2v14h-4z"/><path d="M2 10h20"/><path d="M2 16h20"/>' },
            { id: 'hot_tub', label: 'Hot Tub', cat: 'outdoor', svg: '<path d="M2 14h20v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5z"/><path d="M6 5c1 1 1 2 0 3"/><path d="M12 5c1 1 1 2 0 3"/><path d="M18 5c1 1 1 2 0 3"/>' },

            // Sensors & Automations
            { id: 'sensor', label: 'Motion Sensor', cat: 'sensor', svg: '<path d="M2 12h3l3-9 4 18 3-9h7"/>' },
            { id: 'water', label: 'Water / Leak', cat: 'sensor', svg: '<path d="M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z"/>' },
            { id: 'co2', label: 'CO2 / Smoke', cat: 'sensor', svg: '<circle cx="12" cy="12" r="9"/><path d="M9 10a2 2 0 0 0-2 2v0a2 2 0 0 2 2h1"/><path d="M15 10v4h2v-4h-2z"/>' },
            { id: 'timer', label: 'Timer / Clock', cat: 'sensor', svg: '<circle cx="12" cy="14" r="8"/><path d="M12 10v4l3 2"/><path d="M10 2h4"/>' },
            { id: 'wifi', label: 'WiFi Router', cat: 'sensor', svg: '<path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/>' },
            { id: 'palette', label: 'Scene / Color', cat: 'sensor', svg: '<circle cx="13.5" cy="6.5" r="1.5"/><circle cx="17.5" cy="10.5" r="1.5"/><circle cx="8.5" cy="7.5" r="1.5"/><path d="M12 2C6.5 2 2 6.5 2 12c0 3.6 2.4 6.6 6 7.6 1 .3 1.5-.6 1.5-1.2v-1.9c0-1.7 1.3-3 3-3h2.5c4.1 0 7.5-3.4 7.5-7.5C22 4.5 17.5 2 12 2z"/>' },
            { id: 'play', label: 'Script / Play', cat: 'sensor', svg: '<polygon points="5 3 19 12 5 21 5 3"/>' },
            { id: 'pets', label: 'Pets', cat: 'sensor', svg: '<circle cx="4.5" cy="9.5" r="2"/><circle cx="9" cy="5.5" r="2"/><circle cx="15" cy="5.5" r="2"/><circle cx="19.5" cy="9.5" r="2"/><path d="M12 12c-3 0-5 2-5 5 0 2 2 3 5 3s5-1 5-3c0-3-2-5-5-5z"/>' }
        ];

        const PRESET_PALETTES = {
            classic: {
                name: 'HomeKey Classic',
                desc: 'Original vivid smart home palette',
                colors: {
                    light: '#F59E0B', switch: '#0284C7', climate: '#EA580C', camera: '#6366F1',
                    media_player: '#0D9488', cover: '#059669', fan: '#0891B2', vacuum: '#78716C',
                    scene: '#9333EA', sensor: '#8B5CF6',
                    off_background: '#1E293B', icon_color: '#FFFFFF'
                }
            },
            apple: {
                name: 'Cupertino Glow',
                desc: 'Apple HomeKit warm luminous design',
                colors: {
                    light: '#FFC043', switch: '#007AFF', climate: '#FF453A', camera: '#FF9500',
                    media_player: '#FF2D55', cover: '#34C759', fan: '#64D2FF', vacuum: '#8E8E93',
                    scene: '#BF5AF2', sensor: '#5E5CE6',
                    off_background: '#1C1C1E', icon_color: '#FFFFFF'
                }
            },
            cyberpunk: {
                name: 'Cyberpunk Neon',
                desc: 'High-contrast futuristic electric neon',
                colors: {
                    light: '#FFE600', switch: '#00F0FF', climate: '#FF0055', camera: '#FF1744',
                    media_player: '#00FF66', cover: '#39FF14', fan: '#00B8FF', vacuum: '#64748B',
                    scene: '#B000FF', sensor: '#FF007F',
                    off_background: '#0D0D1A', icon_color: '#000000'
                }
            },
            nordic: {
                name: 'Nordic Calm',
                desc: 'Subtle, Scandinavian minimal earth tones',
                colors: {
                    light: '#D4A373', switch: '#5B8296', climate: '#BC6C25', camera: '#9B5B6E',
                    media_player: '#606C38', cover: '#588157', fan: '#7C98A6', vacuum: '#595959',
                    scene: '#8E7C93', sensor: '#DDA15E',
                    off_background: '#21272A', icon_color: '#FFFFFF'
                }
            },
            monochrome: {
                name: 'Monochrome Luxe',
                desc: 'High-contrast OLED white & platinum',
                colors: {
                    light: '#FFFFFF', switch: '#E2E8F0', climate: '#F1F5F9', camera: '#E5E7EB',
                    media_player: '#94A3B8', cover: '#A0AEC0', fan: '#F8FAFC', vacuum: '#64748B',
                    scene: '#CBD5E1', sensor: '#D1D5DB',
                    off_background: '#18181B', icon_color: '#000000'
                }
            },
            custom: {
                name: 'Custom Palette',
                desc: 'Personalized custom domain colors',
                colors: {}
            }
        };

        const DOMAINS_META = [
            { key: 'light', label: 'Lights' },
            { key: 'switch', label: 'Switches & Outlets' },
            { key: 'climate', label: 'Climate & AC' },
            { key: 'camera', label: 'Camera Feeds' },
            { key: 'media_player', label: 'Media Players' },
            { key: 'cover', label: 'Covers & Blinds' },
            { key: 'fan', label: 'Fans & Purifiers' },
            { key: 'vacuum', label: 'Robot Vacuums' },
            { key: 'scene', label: 'Scenes & Scripts' },
            { key: 'sensor', label: 'Sensors' },
            { key: 'off_background', label: 'Off-State Background' },
            { key: 'icon_color', label: 'Active Icon Color' }
        ];

        let selectedThemePreset = '$currentThemePreset';
        let customColorsMap = Object.assign({}, $currentCustomColors);

        function getActiveColorForDomain(domain) {
            if (selectedThemePreset === 'custom' && customColorsMap[domain]) {
                return customColorsMap[domain];
            }
            const preset = PRESET_PALETTES[selectedThemePreset] || PRESET_PALETTES.classic;
            if (preset.colors && preset.colors[domain]) {
                return preset.colors[domain];
            }
            return PRESET_PALETTES.classic.colors[domain] || '#0284C7';
        }

        function renderThemeGrid() {
            const container = document.getElementById('themeGrid');
            if (!container) return;
            const presetsKeys = ['classic', 'apple', 'cyberpunk', 'nordic', 'monochrome', 'custom'];
            container.innerHTML = presetsKeys.map(function(key) {
                const p = PRESET_PALETTES[key];
                const isActive = (selectedThemePreset === key) ? ' active' : '';
                const sampleKeys = ['light', 'switch', 'camera', 'off_background', 'icon_color'];
                const swatchesHtml = sampleKeys.map(function(dk) {
                    let col = '#888888';
                    if (key === 'custom') {
                        col = customColorsMap[dk] || PRESET_PALETTES.classic.colors[dk] || '#888888';
                    } else {
                        col = p.colors[dk] || '#888888';
                    }
                    return '<span class="theme-swatch-dot" style="background-color:' + col + '"></span>';
                }).join('');

                return '<div class="theme-card' + isActive + '" onclick="selectThemePreset(\'' + key + '\')">' +
                    '<div>' +
                        '<div class="theme-card-title">' + p.name + '</div>' +
                        '<div class="theme-card-desc">' + p.desc + '</div>' +
                    '</div>' +
                    '<div class="theme-swatches-preview">' + swatchesHtml + '</div>' +
                '</div>';
            }).join('');
        }

        function selectThemePreset(presetKey) {
            selectedThemePreset = presetKey;
            renderThemeGrid();
            renderColorPickers();
        }

        function renderColorPickers() {
            const container = document.getElementById('colorPickerGrid');
            if (!container) return;
            container.innerHTML = DOMAINS_META.map(function(item) {
                const currentColor = getActiveColorForDomain(item.key);
                return '<div class="color-picker-row">' +
                    '<span class="color-picker-label">' + item.label + '</span>' +
                    '<div class="color-picker-input-group">' +
                        '<span class="color-picker-hex" id="hex_' + item.key + '">' + currentColor.toUpperCase() + '</span>' +
                        '<input type="color" class="color-input" id="picker_' + item.key + '" value="' + currentColor + '" onchange="onColorPicked(\'' + item.key + '\', this.value)" oninput="onColorInput(\'' + item.key + '\', this.value)">' +
                    '</div>' +
                '</div>';
            }).join('');
        }

        function onColorInput(domain, hexValue) {
            const hexEl = document.getElementById('hex_' + domain);
            if (hexEl) hexEl.innerText = hexValue.toUpperCase();
        }

        function onColorPicked(domain, hexValue) {
            customColorsMap[domain] = hexValue;
            selectedThemePreset = 'custom';
            renderThemeGrid();
            const hexEl = document.getElementById('hex_' + domain);
            if (hexEl) hexEl.innerText = hexValue.toUpperCase();
        }

        function resetCustomColors() {
            const basePreset = PRESET_PALETTES.classic;
            DOMAINS_META.forEach(function(item) {
                customColorsMap[item.key] = basePreset.colors[item.key];
            });
            renderThemeGrid();
            renderColorPickers();
        }

        let allEntities = [];
        let currentDomain = 'ALL';
        let modalActiveEntityId = null;
        let currentModalCat = 'all';

        const existingPinned = $currentPinned || [];
        const selectedEntityIds = new Set(existingPinned.map(function(p) { return p.entityId; }));
        const customNames = {};
        const customIcons = {};
        existingPinned.forEach(function(p) {
            if (p.customName) customNames[p.entityId] = p.customName;
            if (p.customIcon) customIcons[p.entityId] = p.customIcon;
        });

        function getIconObj(iconId, fallbackDomain) {
            if (iconId) {
                const found = ICON_GALLERY.find(function(i) { return i.id === iconId; });
                if (found) return found;
            }
            // Domain fallback
            switch(fallbackDomain) {
                case 'light': return ICON_GALLERY.find(function(i) { return i.id === 'lightbulb'; });
                case 'climate': return ICON_GALLERY.find(function(i) { return i.id === 'thermostat'; });
                case 'media_player': return ICON_GALLERY.find(function(i) { return i.id === 'tv'; });
                case 'cover': return ICON_GALLERY.find(function(i) { return i.id === 'curtains'; });
                case 'fan': return ICON_GALLERY.find(function(i) { return i.id === 'fan'; });
                case 'lock': return ICON_GALLERY.find(function(i) { return i.id === 'lock'; });
                case 'vacuum': return ICON_GALLERY.find(function(i) { return i.id === 'vacuum'; });
                case 'scene': return ICON_GALLERY.find(function(i) { return i.id === 'palette'; });
                case 'script': return ICON_GALLERY.find(function(i) { return i.id === 'play'; });
                case 'sensor':
                case 'binary_sensor': return ICON_GALLERY.find(function(i) { return i.id === 'sensor'; });
                default: return ICON_GALLERY.find(function(i) { return i.id === 'power'; });
            }
        }

        function showAlert(msg, isSuccess) {
            const box = document.getElementById('alertBox');
            box.style.display = 'block';
            box.className = 'alert ' + (isSuccess ? 'alert-success' : 'alert-error');
            box.innerText = msg;
        }

        function persistCredentials() {
            try {
                const pin = document.getElementById('pairingPin').value.trim();
                const token = document.getElementById('haToken').value.trim();
                if (pin) localStorage.setItem('ha_pairing_pin', pin);
                if (token) localStorage.setItem('ha_token', token);
            } catch (e) {}
        }

        function restoreSavedCredentials() {
            try {
                const savedPin = localStorage.getItem('ha_pairing_pin') || '';
                const savedToken = localStorage.getItem('ha_token') || '';
                const pinInput = document.getElementById('pairingPin');
                const tokenInput = document.getElementById('haToken');
                if (savedPin && pinInput && !pinInput.value) {
                    pinInput.value = savedPin;
                }
                if (savedToken && tokenInput && !tokenInput.value) {
                    tokenInput.value = savedToken;
                }
            } catch (e) {}
        }

        async function fetchEntities() {
            persistCredentials();
            const url = document.getElementById('haUrl').value.trim().replace(/\/$/, '');
            const token = document.getElementById('haToken').value.trim();
            const pin = document.getElementById('pairingPin').value.trim();

            if (!url) {
                showAlert('Please enter your Home Assistant URL first.', false);
                return;
            }
            if (!pin) {
                showAlert('Enter the pairing PIN shown on the Google TV screen (Settings > Instant Phone Setup).', false);
                return;
            }
            // Token may be blank when the TV already has one saved - the TV uses its stored token.

            const btn = document.getElementById('btnFetch');
            btn.innerText = 'Connecting via Google TV...';
            btn.disabled = true;
            showAlert('Connecting to Home Assistant...', true);

            try {
                const res = await fetch('/api/fetch-entities', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ serverUrl: url, token: token, pin: pin })
                });

                btn.disabled = false;
                btn.innerText = 'Fetch Entities from HA';

                if (!res.ok) {
                    const errData = await res.json().catch(function() { return {}; });
                    throw new Error(errData.error || ('HTTP ' + res.status));
                }

                allEntities = await res.json();
                renderEntitiesList();
                updateSelectedCount();
                showAlert('Loaded ' + allEntities.length + ' entities. Select devices and customize their icons below.', true);
            } catch (err) {
                btn.disabled = false;
                btn.innerText = 'Fetch Entities from HA';
                showAlert('Connection Error: ' + err.message, false);
            }
        }

        function filterDomain(domain) {
            currentDomain = domain;
            const tabs = document.querySelectorAll('.domain-tab');
            tabs.forEach(function(t) {
                if ((domain === 'ALL' && t.innerText === 'All') ||
                    (domain === 'SELECTED' && t.id === 'selectedTab') ||
                    (t.innerText.toLowerCase() === domain.toLowerCase())) {
                    t.classList.add('active');
                } else {
                    t.classList.remove('active');
                }
            });
            renderEntitiesList();
        }

        function renderEntitiesList() {
            const container = document.getElementById('entitiesContainer');
            if (!allEntities || allEntities.length === 0) {
                container.innerHTML = '<p style="padding: 16px; text-align: center; color: var(--text-muted);">No entities loaded yet</p>';
                return;
            }

            const query = document.getElementById('entityFilter').value.toLowerCase();
            const filtered = allEntities.filter(function(e) {
                if (currentDomain === 'SELECTED' && !selectedEntityIds.has(e.entity_id)) {
                    return false;
                }
                const domain = e.entity_id.split('.')[0];
                const matchesDomain = (currentDomain === 'ALL' || currentDomain === 'SELECTED') || (domain === currentDomain);
                const name = (e.attributes && e.attributes.friendly_name) || '';
                const matchesSearch = e.entity_id.toLowerCase().indexOf(query) !== -1 || name.toLowerCase().indexOf(query) !== -1;
                return matchesDomain && matchesSearch;
            });

            if (filtered.length === 0) {
                container.innerHTML = '<p style="padding: 16px; text-align: center; color: var(--text-muted);">' +
                    (currentDomain === 'SELECTED' ? 'No entities currently selected. Check some devices to add them.' : 'No matching entities found') +
                    '</p>';
                return;
            }

            container.innerHTML = filtered.map(function(item) {
                const domain = item.entity_id.split('.')[0];
                const defaultName = (item.attributes && item.attributes.friendly_name) ? item.attributes.friendly_name : item.entity_id;
                const customName = customNames[item.entity_id] || '';
                const displayName = customName || defaultName;
                const isSelected = selectedEntityIds.has(item.entity_id);
                const isChecked = isSelected ? 'checked' : '';
                const itemClass = 'entity-item' + (isSelected ? ' selected' : '');
                const currentIconKey = customIcons[item.entity_id];
                const iconObj = getIconObj(currentIconKey, domain);

                return '<div class="' + itemClass + '" id="row_' + item.entity_id.replace(/\./g, '_') + '">' +
                    '<div class="entity-header" onclick="triggerCheckbox(\'' + item.entity_id + '\')">' +
                        '<input type="checkbox" value="' + item.entity_id + '" data-name="' + (displayName.replace(/"/g, '&quot;')) + '" ' + isChecked + ' onchange="toggleEntitySelection(this)" onclick="event.stopPropagation()">' +
                        '<div class="entity-info">' +
                            '<div class="entity-name">' + displayName + '</div>' +
                            '<div class="entity-id">' + item.entity_id + ' (' + item.state + ')' + (customName ? ' &bull; <span style="color:var(--accent);">Custom Name</span>' : '') + '</div>' +
                        '</div>' +
                    '</div>' +
                    '<div class="entity-custom-row">' +
                        '<div class="custom-name-wrapper">' +
                            '<input type="text" class="custom-name-input" placeholder="Rename (default: ' + defaultName.replace(/"/g, '&quot;') + ')" value="' + customName.replace(/"/g, '&quot;') + '" oninput="updateCustomName(\'' + item.entity_id + '\', this.value)" onclick="event.stopPropagation()">' +
                        '</div>' +
                        '<button type="button" class="icon-trigger-btn" onclick="openIconModal(\'' + item.entity_id + '\', \'' + (displayName.replace(/'/g, "\\'")) + '\')">' +
                            '<svg class="icon-trigger-svg" viewBox="0 0 24 24">' + iconObj.svg + '</svg>' +
                            '<span>Icon: ' + (currentIconKey ? iconObj.label : 'Auto (' + iconObj.label + ')') + '</span>' +
                            '<span style="font-size:10px; color:var(--accent);">Change &rarr;</span>' +
                        '</button>' +
                    '</div>' +
                '</div>';
            }).join('');
        }

        function updateCustomName(entityId, newName) {
            const trimmed = newName.trim();
            if (trimmed.length > 0) {
                customNames[entityId] = trimmed;
            } else {
                delete customNames[entityId];
            }
            const safeId = entityId.replace(/\./g, '_');
            const row = document.getElementById('row_' + safeId);
            if (row) {
                const nameEl = row.querySelector('.entity-name');
                const idEl = row.querySelector('.entity-id');
                const entity = allEntities.find(function(e) { return e.entity_id === entityId; });
                const defaultName = entity && entity.attributes && entity.attributes.friendly_name ? entity.attributes.friendly_name : entityId;
                const state = entity ? entity.state : '';
                if (nameEl) {
                    nameEl.innerText = trimmed || defaultName;
                }
                if (idEl) {
                    idEl.innerHTML = entityId + (state ? ' (' + state + ')' : '') + (trimmed ? ' &bull; <span style="color:var(--accent);">Custom Name</span>' : '');
                }
            }
        }

        function triggerCheckbox(entityId) {
            const safeId = entityId.replace(/\./g, '_');
            const row = document.getElementById('row_' + safeId);
            if (!row) return;
            const cb = row.querySelector('input[type="checkbox"]');
            if (!cb) return;
            cb.checked = !cb.checked;
            toggleEntitySelection(cb);
        }

        function toggleEntitySelection(cb) {
            const entityId = cb.value;
            const safeId = entityId.replace(/\./g, '_');
            const row = document.getElementById('row_' + safeId);

            if (cb.checked) {
                selectedEntityIds.add(entityId);
                if (row) row.classList.add('selected');
            } else {
                selectedEntityIds.delete(entityId);
                if (row) row.classList.remove('selected');
            }
            updateSelectedCount();
        }

        function selectAllFiltered(select) {
            const checkboxes = document.querySelectorAll('#entitiesContainer input[type="checkbox"]');
            checkboxes.forEach(function(cb) {
                cb.checked = select;
                const safeId = cb.value.replace(/\./g, '_');
                const row = document.getElementById('row_' + safeId);
                if (select) {
                    selectedEntityIds.add(cb.value);
                    if (row) row.classList.add('selected');
                } else {
                    selectedEntityIds.delete(cb.value);
                    if (row) row.classList.remove('selected');
                }
            });
            updateSelectedCount();
        }

        function updateSelectedCount() {
            const count = selectedEntityIds.size;
            const badge = document.getElementById('selectedCountBadge');
            if (badge) badge.innerText = count + ' selected';
            const selectedTab = document.getElementById('selectedTab');
            if (selectedTab) selectedTab.innerText = 'Selected (' + count + ')';
        }

        /* Modal Handlers */
        function openIconModal(entityId, name) {
            modalActiveEntityId = entityId;
            document.getElementById('modalSubtitle').innerText = 'Select an icon for ' + name;
            document.getElementById('modalSearch').value = '';
            document.getElementById('iconModal').classList.add('open');
            currentModalCat = 'all';
            updateModalCatTabs();
            renderIconGrid();
        }

        function closeIconModal() {
            document.getElementById('iconModal').classList.remove('open');
            modalActiveEntityId = null;
        }

        function filterModalCat(cat) {
            currentModalCat = cat;
            updateModalCatTabs();
            renderIconGrid();
        }

        function updateModalCatTabs() {
            const tabs = document.querySelectorAll('.modal-cat-tab');
            tabs.forEach(function(t) {
                if ((currentModalCat === 'all' && t.innerText === 'All Icons') ||
                    t.getAttribute('onclick').indexOf(currentModalCat) !== -1) {
                    t.classList.add('active');
                } else {
                    t.classList.remove('active');
                }
            });
        }

        function renderIconGrid() {
            const container = document.getElementById('iconGridContainer');
            const query = document.getElementById('modalSearch').value.toLowerCase();
            const currentSelectedIcon = modalActiveEntityId ? customIcons[modalActiveEntityId] : null;

            const filtered = ICON_GALLERY.filter(function(icon) {
                const matchesCat = (currentModalCat === 'all') || (icon.cat === currentModalCat);
                const matchesSearch = icon.id.toLowerCase().indexOf(query) !== -1 || icon.label.toLowerCase().indexOf(query) !== -1;
                return matchesCat && matchesSearch;
            });

            if (filtered.length === 0) {
                container.innerHTML = '<p style="grid-column: 1/-1; padding: 20px; text-align: center; color: var(--text-muted);">No matching icons</p>';
                return;
            }

            container.innerHTML = filtered.map(function(icon) {
                const isActive = (currentSelectedIcon === icon.id) ? ' active' : '';
                return '<div class="icon-card' + isActive + '" onclick="chooseIcon(\'' + icon.id + '\')">' +
                    '<svg class="icon-card-svg" viewBox="0 0 24 24">' + icon.svg + '</svg>' +
                    '<div class="icon-card-label">' + icon.label + '</div>' +
                '</div>';
            }).join('');
        }

        function chooseIcon(iconId) {
            if (modalActiveEntityId) {
                customIcons[modalActiveEntityId] = iconId;
                renderEntitiesList();
            }
            closeIconModal();
        }

        function resetIconOverride() {
            if (modalActiveEntityId) {
                delete customIcons[modalActiveEntityId];
                renderEntitiesList();
            }
            closeIconModal();
        }

        async function saveConfig() {
            const url = document.getElementById('haUrl').value.trim();
            const token = document.getElementById('haToken').value.trim();
            const pin = document.getElementById('pairingPin').value.trim();
            const layout = document.getElementById('layoutStyle').value;

            if (!url) {
                showAlert('Please enter your Home Assistant URL.', false);
                return;
            }
            if (!pin) {
                showAlert('Enter the pairing PIN shown on the Google TV screen (Settings > Instant Phone Setup).', false);
                return;
            }
            persistCredentials();
            if (!token) {
                showAlert('No token entered - keeping the token already saved on the TV.', true);
            }

            const pinnedList = Array.from(selectedEntityIds).map(function(entityId) {
                const entity = allEntities.find(function(e) { return e.entity_id === entityId; });
                const defaultName = entity && entity.attributes && entity.attributes.friendly_name ? entity.attributes.friendly_name : entityId;
                const customName = customNames[entityId] && customNames[entityId].trim().length > 0 ? customNames[entityId].trim() : defaultName;
                return {
                    entityId: entityId,
                    customName: customName,
                    category: entityId.split('.')[0],
                    customIcon: customIcons[entityId] || null
                };
            });

            const payload = {
                serverUrl: url,
                token: token,
                pin: pin,
                layout: layout,
                themePreset: selectedThemePreset,
                customThemeColors: customColorsMap,
                pinnedEntities: pinnedList
            };

            showAlert('Saving to Google TV...', true);

            try {
                const res = await fetch('/api/save', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();
                if (data.success) {
                    showAlert('Saved to Google TV. Menu updated with ' + pinnedList.length + ' entities.', true);
                } else {
                    showAlert('Error saving: ' + data.error, false);
                }
            } catch (err) {
                showAlert('Network error saving to TV: ' + err.message, false);
            }
        }

        // Initialize credentials and automatically load entities if URL and PIN are present
        restoreSavedCredentials();
        renderThemeGrid();
        renderColorPickers();
        updateSelectedCount();
        const autoUrl = document.getElementById('haUrl').value.trim();
        const autoPin = document.getElementById('pairingPin').value.trim();
        if (autoUrl && autoPin) {
            fetchEntities();
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}

object WebSetupServerManager {
    private const val TAG = "WebSetupServerManager"
    private var server: WebSetupServer? = null

    @Synchronized
    fun start(prefs: PreferencesManager, onConfigUpdated: () -> Unit = {}) {
        if (!prefs.haEnabled.value) {
            Log.d(TAG, "HA integration is disabled, not starting WebSetupServer")
            stop()
            return
        }
        if (server?.isAlive == true) {
            Log.d(TAG, "WebSetupServer already running on port 8124")
            return
        }
        try {
            server = WebSetupServer(8124, prefs, onConfigUpdated)
            server?.start()
            Log.d(TAG, "WebSetupServer started on port 8124")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSetupServer on port 8124", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            if (server != null) {
                server?.stop()
                server = null
                Log.d(TAG, "WebSetupServer stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSetupServer", e)
        }
    }

    fun isRunning(): Boolean = server?.isAlive == true
}
