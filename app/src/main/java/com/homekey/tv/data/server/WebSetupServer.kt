package com.homekey.tv.data.server

import android.content.Context
import android.util.Log
import com.homekey.tv.data.local.PreferencesRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class WebSetupServer(
    port: Int = 8124,
    private val preferencesRepository: PreferencesRepository,
    private val context: Context? = null,
    private val templateLoader: () -> String = { loadTemplateHtml(context) }
) : NanoHTTPD(port) {

    private val tag = "WebSetupServer"

    private val portalTemplate: String by lazy { templateLoader() }

    private val handler = SetupPortalHandler(
        preferencesRepository = preferencesRepository,
        fetchEntities = ::fetchEntitiesFromHa
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(tag, "HTTP Request: $method $uri")

        return try {
            val postData = if (method == Method.POST) {
                val map = HashMap<String, String>()
                session.parseBody(map)
                map["postData"] ?: ""
            } else {
                ""
            }

            val queryParams = session.parameters.mapValues { (_, values) ->
                values.firstOrNull() ?: ""
            }

            val portalResponse = runBlocking {
                handler.handleRequest(
                    method = method.name,
                    uri = uri,
                    body = postData,
                    queryParams = queryParams,
                    headers = session.headers,
                    templateHtmlProvider = { portalTemplate }
                )
            }
            toNanoResponse(portalResponse)
        } catch (e: Exception) {
            Log.e(tag, "Error handling request $method $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                "{\"error\":\"Internal server error\"}"
            )
        }
    }

    private suspend fun fetchEntitiesFromHa(cleanUrl: String, token: String): PortalFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$cleanUrl/api/states")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .build()

                sharedHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "[]"
                    if (response.isSuccessful) {
                        PortalFetchResult.Success(body)
                    } else {
                        PortalFetchResult.HttpError(response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error proxying entity fetch", e)
                PortalFetchResult.NetworkError(e.message ?: "Failed to connect to Home Assistant")
            }
        }

    private fun toNanoResponse(portalResponse: PortalResponse): Response {
        val status = when (portalResponse.statusCode) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, portalResponse.contentType, portalResponse.body).apply {
            portalResponse.headers.forEach { (k, v) -> addHeader(k, v) }
        }
    }

    companion object {
        private const val PORTAL_ASSET_NAME = "setup_portal.html"
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        internal fun loadTemplateHtml(context: Context?): String {
            if (context != null) {
                return context.assets.open(PORTAL_ASSET_NAME).bufferedReader().use { it.readText() }
            }
            val classpathStream = WebSetupServer::class.java.classLoader?.getResourceAsStream(PORTAL_ASSET_NAME)
            if (classpathStream != null) {
                return classpathStream.bufferedReader().use { it.readText() }
            }
            val candidateFiles = listOf(
                File("app/src/main/assets/$PORTAL_ASSET_NAME"),
                File("src/main/assets/$PORTAL_ASSET_NAME")
            )
            return candidateFiles.firstOrNull { it.isFile }?.readText()
                ?: error("Could not load $PORTAL_ASSET_NAME")
        }
    }
}

object WebSetupServerManager {
    private const val TAG = "WebSetupServerManager"
    private var server: WebSetupServer? = null
    @Volatile
    private var appContext: Context? = null

    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun start(
        prefs: PreferencesRepository,
        context: Context? = null
    ) {
        if (context != null) {
            appContext = context.applicationContext
        }
        if (!prefs.serverConfig.value.haEnabled) {
            Log.d(TAG, "HA integration is disabled, not starting WebSetupServer")
            stop()
            return
        }
        if (server?.isAlive == true) {
            Log.d(TAG, "WebSetupServer already running on port 8124")
            return
        }
        try {
            val effectiveContext = context?.applicationContext ?: appContext
            server = WebSetupServer(
                port = 8124,
                preferencesRepository = prefs,
                context = effectiveContext
            )
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
