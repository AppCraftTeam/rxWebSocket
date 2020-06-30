package com.navin.flintstones.rxwebsocket

import io.reactivex.Flowable
import io.reactivex.Maybe
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

    private val eventProcessor: PublishProcessor<in Event> = PublishProcessor.create()
    val eventStream: Flowable<in Event>
        get() = eventStream.hide()

    private val webSocketListener: WebSocketListener by lazy {
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                setClient(webSocket)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(Open(response))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(Message(text))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(Message(bytes))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                if (userRequestedClose) {
                    if (eventProcessor.hasSubscribers()) {
                        eventProcessor.onNext(Closed(code, reason))
                        eventProcessor.onComplete()
                    }
                } else {
                    if (eventProcessor.hasSubscribers()) {
                        eventProcessor.onError(Closed(code, reason))
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

    fun connect(): Single<Open> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doConnect() }
            .ofType(Open::class.java)
            .firstOrError()

    fun listen(): Flowable<Message> = eventStream
            .subscribeOn(Schedulers.io())
            .ofType(Message::class.java)

    fun send(message: ByteArray): Single<QueuedMessage<*>> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doQueueMessage(message) }
            .ofType(QueuedMessage::class.java)
            .firstOrError()

    fun <T : Any> send(message: T): Single<QueuedMessage<*>> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doQueueMessage(message) }
            .ofType(QueuedMessage::class.java)
            .firstOrError()

    fun disconnect(code: Int, reason: String): Single<Closed> = eventStream
            .subscribeOn(Schedulers.io())
            .doOnSubscribe { doDisconnect(code, reason) }
            .ofType(Closed::class.java)
            .firstOrError()

    private fun doConnect() {
        if (originalWebsocket != null) {
            if (eventProcessor.hasSubscribers())
                eventProcessor.onNext(Open())
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
                eventProcessor.onNext(QueuedMessage<Any?>(ByteString.of(*message)))
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
                        eventProcessor.onNext(QueuedMessage<Any?>(message))
                    }
                }
            } catch (throwable: Throwable) {
                throw RuntimeException(throwable)
            }
        } else if (message is String) {
            if (originalWebsocket!!.send(message as String)) {
                if (eventProcessor.hasSubscribers()) {
                    eventProcessor.onNext(QueuedMessage(message))
                }
            }
        }
    }

    private fun setClient(originalWebsocket: WebSocket?) {
        this.originalWebsocket = originalWebsocket
        userRequestedClose = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> responseConverter(type: Type): WebSocketConverter<String, T>? {
        for (converterFactory in converterFactories) {
            converterFactory.responseBodyConverter(type)
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

    interface Event {
        val client: RxWebsocket
    }

    inner class Open(response: Response? = null) : Event {
        val response: Maybe<Response> = response
                ?.let { Maybe.just(it) }
                ?: Maybe.empty()

        fun response(): Response? = response.blockingGet()

        override val client: RxWebsocket = this@RxWebsocket
    }

    inner class Message : Event {
        private val message: String?
        private val messageBytes: ByteString?

        constructor(message: String?) {
            this.message = message
            this.messageBytes = null
        }

        constructor(messageBytes: ByteString?) {
            this.messageBytes = messageBytes
            this.message = null
        }

        fun data(): String? {
            var interceptedMessage = message
            for (interceptor in receiveInterceptors) {
                interceptedMessage = interceptor.intercept(interceptedMessage)
            }
            return interceptedMessage
        }

        fun dataBytes(): ByteString? = messageBytes

        private fun dataOrDataBytesAsString(): String {
            if (data() == null && dataBytes() == null) {
                return ""
            }
            if (dataBytes() == null) {
                return data()!!
            }
            return if (data() == null) {
                if (dataBytes() == null) "" else dataBytes()!!.utf8()
            } else ""
        }

        fun <T : Any> data(type: Class<T>): T {
            val converter: WebSocketConverter<String, T>? = responseConverter(type)
            return converter
                    ?.convert(dataOrDataBytesAsString())
                    ?: throw Exception("No converters available to convert the enqueued object")
        }

        override val client: RxWebsocket = this@RxWebsocket
    }

    inner class QueuedMessage<T>(private val message: T) : Event {
        fun message(): T? = message

        override val client: RxWebsocket = this@RxWebsocket
    }

    inner class Closed(val code: Int, val reason: String) : Throwable(), Event {
        override val message: String = reason

        override val client: RxWebsocket = this@RxWebsocket
    }
}