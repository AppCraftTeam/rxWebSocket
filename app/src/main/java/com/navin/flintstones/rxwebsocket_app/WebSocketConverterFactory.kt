package com.navin.flintstones.rxwebsocket_app

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.navin.flintstones.rxwebsocket.WebSocketConverter
import java.lang.reflect.Type

open class WebSocketConverterFactory private constructor(
        private val gson: Gson
) : WebSocketConverter.Factory() {
    override fun responseBodyConverter(type: Type): WebSocketConverter<String, *> {
        val adapter = gson.getAdapter(TypeToken.get(type))
        return GsonResponseConvertor(gson, adapter)
    }

    override fun requestBodyConverter(type: Type): WebSocketConverter<*, String> {
        val adapter = gson.getAdapter(TypeToken.get(type))
        return GsonRequestConvertor(gson, adapter)
    }

    companion object {
        @JvmOverloads
        fun create(gson: Gson = Gson()): WebSocketConverterFactory {
            return WebSocketConverterFactory(gson)
        }
    }
}