package com.navin.flintstones.rxwebsocket_app

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.navin.flintstones.rxwebsocket.WebSocketConverter

open class GsonRequestConvertor<T : Any>(
        private val gson: Gson,
        private val adapter: TypeAdapter<T>
) : WebSocketConverter<T, String> {
    override fun convert(value: T): String = adapter.toJson(value)
}