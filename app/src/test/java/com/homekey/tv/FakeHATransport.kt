package com.homekey.tv

import com.homekey.tv.data.api.HATransport
import com.homekey.tv.data.api.TransportEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeHATransport : HATransport {
    val sentMessages = mutableListOf<String>()
    var lastConnectedUrl: String? = null
    var isConnected = false

    private val _incomingMessages = Channel<String>(Channel.UNLIMITED)
    override val incomingMessages: Flow<String> = _incomingMessages.receiveAsFlow()

    private val _connectionEvents = MutableSharedFlow<TransportEvent>(replay = 1)
    override val connectionEvents: Flow<TransportEvent> = _connectionEvents.asSharedFlow()

    override fun connect(wsUrl: String) {
        lastConnectedUrl = wsUrl
        isConnected = true
        _connectionEvents.tryEmit(TransportEvent.Connected)
    }

    override fun send(message: String): Boolean {
        sentMessages.add(message)
        return true
    }

    override fun disconnect(code: Int, reason: String) {
        isConnected = false
        _connectionEvents.tryEmit(TransportEvent.Disconnected(code, reason))
    }

    fun emitMessage(msg: String) {
        _incomingMessages.trySend(msg)
    }

    fun emitEvent(event: TransportEvent) {
        if (event is TransportEvent.Connected) isConnected = true
        if (event is TransportEvent.Disconnected || event is TransportEvent.Failure) isConnected = false
        _connectionEvents.tryEmit(event)
    }
}
