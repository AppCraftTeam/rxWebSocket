package com.navin.flintstones.rxwebsocket

import io.reactivex.Flowable

// Extends original RxWebSocket for additional functionality

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> RxWebsocket.listenData(): Flowable<T> = listen()
        .map { (it as? WebSocketEvent.Message<T>)?.data(T::class.java) }
        .ofType(T::class.java)