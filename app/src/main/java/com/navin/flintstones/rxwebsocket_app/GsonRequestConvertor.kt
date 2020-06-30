package com.navin.flintstones.rxwebsocket_app

import com.google.gson.TypeAdapter
import com.navin.flintstones.rxwebsocket.WebSocketConverter

class GsonRequestConvertor<T : Any>(
        private val adapter: TypeAdapter<T>
) : WebSocketConverter<T, String> {
    override fun convert(value: T): String = adapter.toJson(value)
}