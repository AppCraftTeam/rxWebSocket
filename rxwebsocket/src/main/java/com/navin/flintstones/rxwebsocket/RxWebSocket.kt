package com.navin.flintstones.rxwebsocket

import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import okio.ByteString
import java.lang.reflect.Type
import java.util.*

private const val INTERNAL_ERROR = 500

@Suppress("unused", "MemberVisibilityCanBePrivate")
class RxWebsocket private constructor() {
    private lateinit var request: Request
    private var converterFactories: List<WebSocketConverter.Factory> = ArrayList()
    private var receiveInterceptors: List<WebSocketInterceptor> = ArrayList()
    private var originalWebsocket: WebSocket? = null
    private var userRequestedClose = false
    private var okHttpClient: OkHttpClient? = null

    private val eventProcessor: PublishProcessor<WebSocketEvent> = PublishProcessor.create()
    val eventStream: Flowable<WebSocketEvent> = eventProcessor.hide().share()

    private val webSocketListener: WebSocketListener by lazy {
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                setClient(webSocket)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(WebSocketEvent.Open(response))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(WebSocketEvent.Message<Any>(
                            dataOrDataBytesAsString(data = text),
                            ::interceptMessage,
                            ::responseConverter
                    ))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(WebSocketEvent.Message<Any>(
                            dataOrDataBytesAsString(dataBytes = bytes),
                            ::interceptMessage,
                            ::responseConverter
                    ))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                if (userRequestedClose) {
                    if (eventProcessor.hasSubscribers()) {
                        eventProcessor.onNext(WebSocketEvent.Closed(code, reason))
                        eventProcessor.onComplete()
                    }
                } else {
                    if (eventProcessor.hasSubscribers()) {
                        eventProcessor.onError(WebSocketEvent.Closed(code, reason))
                    }
                }
                setClient(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onError(t)
                }
                setClient(null)
            }
        }
    }

    fun connect(): Single<WebSocketEvent.Open> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doConnect() }
            .ofType(WebSocketEvent.Open::class.java)
            .firstOrError()

    fun listen(): Flowable<WebSocketEvent.Message<*>> = eventStream
            .subscribeOn(Schedulers.io())
            .ofType(WebSocketEvent.Message::class.java)

    fun send(message: ByteArray): Single<WebSocketEvent.QueuedMessage<*>> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doQueueMessage(message) }
            .ofType(WebSocketEvent.QueuedMessage::class.java)
            .firstOrError()

    fun <T : Any> send(message: T): Single<WebSocketEvent.QueuedMessage<*>> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doQueueMessage(message) }
            .ofType(WebSocketEvent.QueuedMessage::class.java)
            .firstOrError()

    fun disconnect(code: Int, reason: String): Single<WebSocketEvent.Closed> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doDisconnect(code, reason) }
            .ofType(WebSocketEvent.Closed::class.java)
            .firstOrError()

    private fun doConnect() {
        if (originalWebsocket != null) {
            if (eventProcessor.hasSubscribers())
                eventProcessor.onNext(WebSocketEvent.Open())
            return
        }
        if (okHttpClient == null) {
            okHttpClient = OkHttpClient.Builder()
                    .build()
        }
        okHttpClient!!.newWebSocket(request, webSocketListener)
    }

    private fun doDisconnect(code: Int, reason: String) {
        requireSocket()
        userRequestedClose = true
        if (originalWebsocket != null) {
            originalWebsocket!!.close(code, reason)
        }
    }

    private fun doQueueMessage(message: ByteArray) {
        requireSocket()

        if (originalWebsocket!!.send(ByteString.of(*message))) {
            if (eventProcessor.hasSubscribers()) {
                eventProcessor.onNext(WebSocketEvent.QueuedMessage<Any?>(ByteString.of(*message)))
            }
        }
    }

    private fun <T : Any> doQueueMessage(message: T) {
        requireSocket()

        val converter: WebSocketConverter<T, String>? = requestConverter(message.javaClass)
        if (converter != null) {
            try {
                if (originalWebsocket!!.send(converter.convert(message))) {
                    if (eventProcessor.hasSubscribers()) {
                        eventProcessor.onNext(WebSocketEvent.QueuedMessage<Any?>(message))
                    }
                }
            } catch (throwable: Throwable) {
                throw RuntimeException(throwable)
            }
        } else if (message is String) {
            if (originalWebsocket!!.send(message as String)) {
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(WebSocketEvent.QueuedMessage(message))
                }
            }
        }
    }

    private fun setClient(originalWebsocket: WebSocket?) {
        this.originalWebsocket = originalWebsocket
        userRequestedClose = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> responseConverter(dataClass: Class<T>): WebSocketConverter<String, T>? {
        for (converterFactory in converterFactories) {
            converterFactory.responseBodyConverter(dataClass)
                    ?.let { return it as WebSocketConverter<String, T> }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> requestConverter(type: Type): WebSocketConverter<T, String>? {
        converterFactories.forEach { converterFactory ->
            converterFactory.requestBodyConverter(type)
                    ?.let { return it as WebSocketConverter<T, String> }
        }
        return null
    }

    private fun dataOrDataBytesAsString(data: String? = null, dataBytes: ByteString? = null): String {
        return when {
            data == null && dataBytes == null -> ""
            dataBytes == null -> data!!
            data == null -> dataBytes.utf8()
            else -> ""
        }
    }

    // private fun <T : Any> convertResponse(response: String, type: Class<T>): T {
    //     val converter: WebSocketConverter<String, T>? = responseConverter(type)
    //     return converter
    //             ?.convert(response)
    //             ?: throw Exception("No converters available to convert the enqueued object")
    // }

    private fun interceptMessage(message: String?): String? {
        var interceptedMessage = message
        for (interceptor in receiveInterceptors) {
            interceptedMessage = interceptor.intercept(interceptedMessage)
        }
        return interceptedMessage
    }

    private fun requireSocket() = requireNotNull(originalWebsocket) { "Expected an open websocket" }

    /**
     * Builder class for creating rx websockets.
     */
    class Builder {
        private val converterFactories: MutableList<WebSocketConverter.Factory> = mutableListOf()
        private val receiveInterceptors: MutableList<WebSocketInterceptor> = mutableListOf()

        fun addConverterFactory(factory: WebSocketConverter.Factory): Builder = this.apply {
            converterFactories.add(factory)
        }

        fun addReceiveInterceptor(receiveInterceptor: WebSocketInterceptor): Builder = this.apply {
            receiveInterceptors.add(receiveInterceptor)
        }

        fun build(okHttpClient: OkHttpClient, request: Request): RxWebsocket = RxWebsocket().apply {
            this.request = request
            this.converterFactories = this@Builder.converterFactories
            this.receiveInterceptors = this@Builder.receiveInterceptors
            this.okHttpClient = okHttpClient
        }

        fun build(okHttpClient: OkHttpClient, wssUrl: String): RxWebsocket {
            require(wssUrl.isNotEmpty()) { "Websocket address cannot be null or empty" }

            return RxWebsocket().apply {
                this.converterFactories = this@Builder.converterFactories
                this.receiveInterceptors = this@Builder.receiveInterceptors
                this.request = Request.Builder()
                        .url(wssUrl)
                        .get()
                        .build()
                this.okHttpClient = okHttpClient
            }
        }
    }
}