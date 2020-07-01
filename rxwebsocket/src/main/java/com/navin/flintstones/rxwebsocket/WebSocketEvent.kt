package com.navin.flintstones.rxwebsocket

import io.reactivex.Maybe
import okhttp3.Response

interface WebSocketEvent {
    open class Open internal constructor(response: Response? = null) : WebSocketEvent {
        val response: Maybe<Response> = response
                ?.let { Maybe.just(it) }
                ?: Maybe.empty()

        fun response(): Response? = response.blockingGet()
    }

    open class Message<T : Any> internal constructor(
            private val message: String,
            private val dataInterceptor: (String?) -> String?,
            private val responseConverter: (Class<T>) -> WebSocketConverter<String, *>?
    ) : WebSocketEvent {
        val data: String?
            get() = dataInterceptor(message)

        @Suppress("UNCHECKED_CAST")
        fun data(dataClass: Class<T>): T? = responseConverter(dataClass)
                ?.convert(message) as? T
    }

    open class QueuedMessage<T> internal constructor(private val message: T) : WebSocketEvent {
        fun message(): T? = message
    }

    open class Closed internal constructor(val code: Int, val reason: String) : Throwable(), WebSocketEvent {
        override val message: String = reason
    }
}