package com.homekey.tv.data.server

import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.data.models.toPortalCatalogJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class PortalFetchResult {
    data class Success(val bodyJson: String) : PortalFetchResult()
    data class HttpError(val statusCode: Int) : PortalFetchResult()
    data class NetworkError(val message: String) : PortalFetchResult()
}

data class PortalResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Pure, framework-free handler for the HomeKey TV mobile web setup portal.
 *
 * Owns pairing-PIN brute-force lockout, SSRF/token-reuse guards, partial configuration updates,
 * backup/restore endpoints, and runtime HTML hydration from [ThemeModels] and [PreferencesRepository].
 */
class SetupPortalHandler(
    private val preferencesRepository: PreferencesRepository,
    private val fetchEntities: suspend (url: String, token: String) -> PortalFetchResult,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // Pairing-PIN brute-force back-off. The PIN only has ~1e6 combinations, so after a few wrong
    // attempts the server stops accepting new guesses for a minute.
    private var failedPinAttempts = 0
    private var lockoutUntil = 0L

    @Serializable
    data class SetupPayload(
        val serverUrl: String = "",
        val fallbackServerUrl: String? = null,
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
        val fallbackServerUrl: String? = null,
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

    @Serializable
    data class PortalInitialState(
        val serverUrl: String,
        val fallbackServerUrl: String,
        val isTokenSaved: Boolean,
        val pinnedEntities: List<PinnedEntityConfig>,
        val layout: String,
        val themePreset: String,
        val customColors: Map<String, String>
    )

    @Synchronized
    fun isPinAccepted(pin: String?): Boolean {
        val now = clockMs()
        if (now < lockoutUntil) return false
        val expectedPin = preferencesRepository.serverConfig.value.pairingPin
        val ok = !pin.isNullOrBlank() && pin == expectedPin
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

    suspend fun handleRequest(
        method: String,
        uri: String,
        body: String = "",
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        templateHtmlProvider: () -> String = { "" }
    ): PortalResponse {
        val normalizedMethod = method.uppercase()
        val normalizedHeaders = headers.entries.associate { (k, v) -> k.lowercase() to v }
        return when {
            normalizedMethod == "GET" && uri == "/" -> {
                PortalResponse(200, "text/html", renderPortalHtml(templateHtmlProvider()))
            }
            normalizedMethod == "GET" && uri == "/api/status" -> handleStatus()
            normalizedMethod == "POST" && uri == "/api/fetch-entities" -> handleFetchEntities(body)
            normalizedMethod == "POST" && uri == "/api/save" -> handleSave(body)
            normalizedMethod == "GET" && uri == "/api/backup" -> {
                val pin = queryParams["pin"] ?: normalizedHeaders["x-pairing-pin"]
                val includeToken = queryParams["includeToken"]?.toBoolean() ?: false
                handleBackup(pin = pin, includeToken = includeToken)
            }
            normalizedMethod == "POST" && uri == "/api/restore" -> {
                handleRestore(
                    postData = body,
                    headerPin = normalizedHeaders["x-pairing-pin"],
                    queryPin = queryParams["pin"]
                )
            }
            else -> PortalResponse(404, "text/plain", "Not Found")
        }
    }

    fun renderPortalHtml(templateHtml: String): String {
        val server = preferencesRepository.serverConfig.value
        val state = PortalInitialState(
            serverUrl = server.serverUrl,
            fallbackServerUrl = server.fallbackServerUrl,
            isTokenSaved = server.accessToken.isNotBlank(),
            pinnedEntities = preferencesRepository.pinnedEntities.value,
            layout = preferencesRepository.dockConfig.value.layout,
            themePreset = preferencesRepository.themeConfig.value.preset,
            customColors = preferencesRepository.themeConfig.value.customColors
        )
        // NOTE: the long-lived access token is deliberately NOT embedded in this page. It is never
        // sent back to the browser; once the TV is configured, /api/* use the saved token server-side.
        val stateJson = escapeInlineScriptJson(json.encodeToString(state))
        val catalogJson = escapeInlineScriptJson(toPortalCatalogJson())
        return templateHtml
            .replace(THEME_CATALOG_PLACEHOLDER, catalogJson)
            .replace(PORTAL_STATE_PLACEHOLDER, stateJson)
    }

    fun handleStatus(): PortalResponse {
        val server = preferencesRepository.serverConfig.value
        // Built as structured JSON — never string interpolation (serverUrl is user data).
        val body = buildJsonObject {
            put("configured", server.isConfigured)
            put("serverUrl", server.serverUrl)
            put("fallbackServerUrl", server.fallbackServerUrl)
            put("pinnedCount", preferencesRepository.pinnedEntities.value.size)
            put("layout", preferencesRepository.dockConfig.value.layout)
            put("themePreset", preferencesRepository.themeConfig.value.preset)
        }.toString()
        return PortalResponse(200, "application/json", body)
    }

    suspend fun handleFetchEntities(postData: String): PortalResponse {
        val payload = try {
            json.decodeFromString<FetchPayload>(postData)
        } catch (_: SerializationException) {
            return jsonError(400, "Invalid request payload.")
        }

        if (!isPinAccepted(payload.pin)) {
            return forbidden()
        }

        // Credentials typed into the page take priority (covers first-time setup and
        // token rotation/repair). The saved token is only ever sent to the saved server
        // — never to a URL the requester chose — so a LAN client that got past the PIN
        // cannot use the TV's token to probe arbitrary internal hosts (SSRF).
        val server = preferencesRepository.serverConfig.value
        val savedUrl = server.serverUrl.trim().removeSuffix("/")
        val savedFallbackUrl = server.fallbackServerUrl.trim().removeSuffix("/")
        val savedToken = server.accessToken
        val typedUrl = payload.serverUrl.trim().removeSuffix("/")
        val typedToken = payload.token.trim()

        val cleanUrl: String
        val token: String
        if (typedToken.isNotBlank()) {
            cleanUrl = typedUrl.ifBlank { savedUrl }
            token = typedToken
        } else {
            val matchesSavedUrl = typedUrl == savedUrl ||
                (savedFallbackUrl.isNotBlank() && typedUrl == savedFallbackUrl)
            if (typedUrl.isNotBlank() && !matchesSavedUrl) {
                return jsonError(
                    400,
                    "Enter an access token for that server - the TV's saved token only works with its saved Home Assistant."
                )
            }
            cleanUrl = typedUrl.ifBlank { savedUrl }
            token = savedToken
        }

        if (cleanUrl.isBlank() || token.isBlank()) {
            return jsonError(
                400,
                "Enter your Home Assistant URL and access token to fetch entities."
            )
        }

        return when (val result = fetchEntities(cleanUrl, token)) {
            is PortalFetchResult.Success -> PortalResponse(200, "application/json", result.bodyJson)
            is PortalFetchResult.HttpError -> jsonError(400, "Home Assistant returned HTTP ${result.statusCode}")
            is PortalFetchResult.NetworkError -> jsonError(500, "Failed to connect to Home Assistant")
        }
    }

    suspend fun handleSave(postData: String): PortalResponse {
        val payload = try {
            json.decodeFromString<SetupPayload>(postData)
        } catch (_: SerializationException) {
            return jsonError(400, "Invalid request payload.")
        }

        if (!isPinAccepted(payload.pin)) {
            return forbidden()
        }

        // The page never receives the token back (it is not embedded in the HTML), so
        // blank fields mean "keep the value already stored on the TV".
        val server = preferencesRepository.serverConfig.value
        val savedUrl = server.serverUrl.trim().removeSuffix("/")
        val savedFallback = server.fallbackServerUrl.trim().removeSuffix("/")
        val savedToken = server.accessToken
        val incomingUrl = payload.serverUrl.trim().removeSuffix("/")
        val incomingFallback = payload.fallbackServerUrl?.trim()?.removeSuffix("/")
        val incomingToken = payload.token.trim()

        val effectiveUrl = incomingUrl.ifBlank { savedUrl }
        if (effectiveUrl.isBlank()) {
            return jsonError(400, "Enter your Home Assistant URL to continue.")
        }

        // Switching to a different server (primary or fallback) requires a fresh token.
        // Reusing the saved token for a new host would silently send the Home Assistant
        // credentials to a server the user never authorised.
        val primaryChanged = incomingUrl.isNotBlank() && incomingUrl != savedUrl
        val fallbackChanged = !incomingFallback.isNullOrBlank() &&
            incomingFallback != savedFallback &&
            incomingFallback != savedUrl
        if ((primaryChanged || fallbackChanged) &&
            incomingToken.isBlank() && savedToken.isNotBlank()
        ) {
            return jsonError(
                400,
                "Enter an access token for the new server to switch Home Assistant servers."
            )
        }

        val effectiveToken = incomingToken.ifBlank { savedToken }
        val effectiveFallback = incomingFallback ?: server.fallbackServerUrl

        preferencesRepository.updateServerConfig {
            it.copy(
                serverUrl = effectiveUrl,
                fallbackServerUrl = effectiveFallback,
                accessToken = effectiveToken
            )
        }

        if (!payload.layout.isNullOrBlank()) {
            preferencesRepository.updateDockConfig { it.copy(layout = payload.layout) }
        }

        if (!payload.themePreset.isNullOrBlank() || payload.customThemeColors != null) {
            preferencesRepository.updateThemeConfig { current ->
                current.copy(
                    preset = if (!payload.themePreset.isNullOrBlank()) payload.themePreset else current.preset,
                    customColors = if (payload.customThemeColors != null) {
                        current.customColors + payload.customThemeColors
                    } else {
                        current.customColors
                    }
                )
            }
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
            preferencesRepository.savePinnedEntities(pinned)
        }

        return jsonSuccess("Configuration saved to Google TV!")
    }

    suspend fun handleBackup(pin: String?, includeToken: Boolean = false): PortalResponse {
        if (!isPinAccepted(pin)) {
            return forbidden()
        }
        val backupJson = preferencesRepository.exportBackupJson(includeToken)
        return PortalResponse(
            statusCode = 200,
            contentType = "application/json",
            body = backupJson,
            headers = mapOf("Content-Disposition" to "attachment; filename=\"homekey_tv_backup.json\"")
        )
    }

    suspend fun handleRestore(
        postData: String,
        headerPin: String? = null,
        queryPin: String? = null
    ): PortalResponse {
        var actualPin = headerPin ?: queryPin
        var backupContent = postData

        try {
            val parsed = json.parseToJsonElement(postData)
            if (parsed is JsonObject) {
                if (parsed.containsKey("pin")) {
                    actualPin = (parsed["pin"] as? JsonPrimitive)?.content ?: actualPin
                }
                if (parsed.containsKey("backup")) {
                    backupContent = parsed["backup"].toString()
                }
            }
        } catch (_: Exception) {
            // Raw or invalid JSON will be checked after PIN verification.
        }

        if (!isPinAccepted(actualPin)) {
            return forbidden()
        }

        val success = preferencesRepository.restoreFromBackupJson(backupContent).isSuccess
        return if (success) {
            jsonSuccess("Configuration restored successfully!")
        } else {
            jsonError(400, "Failed to restore: invalid backup format.")
        }
    }

    private fun escapeInlineScriptJson(rawJson: String): String {
        return rawJson
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    private fun forbidden(): PortalResponse = PortalResponse(
        statusCode = 403,
        contentType = "application/json",
        body = "{\"error\":\"Invalid or missing pairing PIN. Enter the code shown on the TV screen.\"}"
    )

    private fun jsonError(statusCode: Int, message: String): PortalResponse {
        val body = buildJsonObject { put("error", message) }.toString()
        return PortalResponse(statusCode, "application/json", body)
    }

    private fun jsonSuccess(message: String): PortalResponse {
        val body = buildJsonObject {
            put("success", true)
            put("message", message)
        }.toString()
        return PortalResponse(200, "application/json", body)
    }

    companion object {
        const val MAX_PIN_ATTEMPTS = 8
        const val PIN_LOCKOUT_MS = 60_000L
        const val THEME_CATALOG_PLACEHOLDER = "__THEME_CATALOG_JSON__"
        const val PORTAL_STATE_PLACEHOLDER = "__PORTAL_STATE_JSON__"
    }
}
