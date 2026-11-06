package com.homekey.tv.data.api

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

class OkHttpHATransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
) : HATransport {

    private val tag = "OkHttpHATransport"

    private val _incomingMessages = Channel<String>(Channel.UNLIMITED)
    override val incomingMessages: Flow<String> = _incomingMessages.receiveAsFlow()

    private val _connectionEvents = MutableSharedFlow<TransportEvent>(replay = 1)
    override val connectionEvents: Flow<TransportEvent> = _connectionEvents.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(tag, "WebSocket Opened")
            _connectionEvents.tryEmit(TransportEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _incomingMessages.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(tag, "WebSocket Closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(tag, "WebSocket Closed: $code $reason")
            _connectionEvents.tryEmit(TransportEvent.Disconnected(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(tag, "WebSocket Failure: ${t.message}", t)
            _connectionEvents.tryEmit(TransportEvent.Failure(t))
        }
    }

    override fun connect(wsUrl: String) {
        closeCurrentSocket(1000, "Reconnecting")
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    override fun send(message: String): Boolean {
        val ws = webSocket ?: return false
        return ws.send(message)
    }

    override fun disconnect(code: Int, reason: String) {
        closeCurrentSocket(code, reason)
    }

    private fun closeCurrentSocket(code: Int, reason: String) {
        try {
            webSocket?.close(code, reason)
        } catch (e: Exception) {
            Log.e(tag, "Error closing websocket", e)
        }
        webSocket = null
    }
}
