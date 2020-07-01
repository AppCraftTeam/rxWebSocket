package com.navin.flintstones.rxwebsocket_app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.navin.flintstones.rxwebsocket.RxWebsocket
import com.navin.flintstones.rxwebsocket.RxWebsocket.*
import com.navin.flintstones.rxwebsocket.WebSocketEvent
import com.navin.flintstones.rxwebsocket.WebSocketInterceptor
import com.navin.flintstones.rxwebsocket.listenData
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Consumer
import io.reactivex.internal.functions.Functions
import io.reactivex.schedulers.Schedulers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    @BindView(R.id.location)
    lateinit var location: EditText

    @BindView(R.id.send_message)
    lateinit var sendMessage: EditText

    @BindView(R.id.recd_message)
    lateinit var recdMessage: TextView

    private var websocket: RxWebsocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        ButterKnife.bind(this)
    }

    private fun openWebsocket() {
        val okHttpClientBuilder = OkHttpClient.Builder()
        okHttpClientBuilder.addInterceptor(Interceptor { chain: Interceptor.Chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            requestBuilder.addHeader("Authorization", "Bearer ")
                    .build()
            chain.proceed(requestBuilder.build())
        })
        websocket = Builder()
                .addConverterFactory(WebSocketConverterFactory.create())
                .addReceiveInterceptor(WebSocketInterceptor { d -> "INTERCEPTED:$d " })
                .build(okHttpClientBuilder.build(), location.text.toString())
        logEvents()
    }

    @SuppressLint("CheckResult")
    private fun logEvents() {
        websocket!!.eventStream
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { event ->
                    when (event) {
                        is WebSocketEvent.Open -> {
                            log("CONNECTED")
                            logNewLine()
                        }
                        is WebSocketEvent.Closed -> {
                            log("DISCONNECTED")
                            logNewLine()
                        }
                        is WebSocketEvent.QueuedMessage<*> -> {
                            log("[MESSAGE QUEUED]:" + event.message().toString())
                            logNewLine()
                        }
                        is WebSocketEvent.Message<*> -> {
                            try {
                                (event as? WebSocketEvent.Message<SampleDataModel>)?.run {
                                    log("[DE-SERIALIZED MESSAGE RECEIVED]:" + data(SampleDataModel::class.java).toString())
                                    log(String.format("[DE-SERIALIZED MESSAGE RECEIVED][id]:%d", data(SampleDataModel::class.java)?.id))
                                    log(String.format(
                                            "[DE-SERIALIZED MESSAGE RECEIVED][message]:%s",
                                            event.data(SampleDataModel::class.java)?.message
                                    ))
                                    logNewLine()
                                }
                            } catch (throwable: Throwable) {
                                log("[MESSAGE RECEIVED]:" + event.data)
                                logNewLine()
                            }
                        }
                    }
                }
                .subscribeOn(Schedulers.io())
                .subscribe(Functions.emptyConsumer(), Consumer { throwable: Throwable -> logError(throwable) })

    }

    private fun logNewLine() {
        recdMessage.text = String.format("%s\n", recdMessage.text)
    }

    private fun logError(throwable: Throwable) {
        recdMessage.text = String.format("%s%s", recdMessage.text, String.format("\n[%s]:[ERROR]%s", currentTime, throwable.message))
    }

    private fun log(text: String) {
        recdMessage.text = String.format("%s%s", recdMessage.text, String.format("\n[%s]:%s", currentTime, text))
    }

    private val currentTime: String
        get() {
            val c = Calendar.getInstance()
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            return sdf.format(c.time)
        }

    @SuppressLint("CheckResult")
    @OnClick(R.id.connect)
    fun onConnect() {
        openWebsocket()
        websocket!!.connect()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { event: WebSocketEvent.Open ->
                            Log.d(MainActivity::class.java.simpleName, event.toString())
                        },
                        { throwable: Throwable ->
                            logError(throwable)
                        }
                )
    }

    @SuppressLint("CheckResult")
    @OnClick(R.id.disconnect)
    fun onDisconnect() {
        if (websocket != null) {
            websocket!!.disconnect(1000, "Disconnect")
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            { event: WebSocketEvent.Closed ->
                                Log.d(MainActivity::class.java.simpleName, event.toString())
                            },
                            { throwable: Throwable ->
                                logError(throwable)
                            }
                    )
        }
    }

    @SuppressLint("CheckResult")
    @OnClick(R.id.send)
    fun onSend() {
        if (websocket != null) {
            websocket!!
                    .send(sendMessage.text.toString())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            { event: WebSocketEvent.QueuedMessage<*> ->
                                Log.d(MainActivity::class.java.simpleName, event.toString())
                            },
                            { throwable: Throwable ->
                                logError(throwable)
                            }
                    )
        }
    }

    @SuppressLint("CheckResult")
    @OnClick(R.id.send_sample_obj)
    fun onSendObject() {
        if (websocket != null) {
            websocket!!
                    .send(SampleDataModel(1, "sample object"))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            { event: WebSocketEvent.QueuedMessage<*> ->
                                Log.d(MainActivity::class.java.simpleName, event.toString())
                            },
                            { throwable: Throwable ->
                                logError(throwable)
                            }
                    )
        }
    }

    @OnClick(R.id.clear)
    fun onClear() {
        recdMessage.text = ""
    }
}