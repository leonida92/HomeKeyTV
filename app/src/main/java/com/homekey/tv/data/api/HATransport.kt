package com.homekey.tv.data.api

import kotlinx.coroutines.flow.Flow

sealed interface TransportEvent {
    data object Connected : TransportEvent
    data class Disconnected(val code: Int, val reason: String) : TransportEvent
    data class Failure(val throwable: Throwable) : TransportEvent
}

interface HATransport {
    val incomingMessages: Flow<String>
    val connectionEvents: Flow<TransportEvent>
    fun connect(wsUrl: String)
    fun send(message: String): Boolean
    fun disconnect(code: Int = 1000, reason: String = "Normal closure")
}
