package com.navin.flintstones.rxwebsocket_app

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.navin.flintstones.rxwebsocket.WebSocketConverter
import java.io.IOException
import java.io.StringReader

open class GsonResponseConvertor<T : Any>(
        private val gson: Gson,
        private val adapter: TypeAdapter<T>
) : WebSocketConverter<String, T> {
    @Throws(IOException::class)
    override fun convert(value: String): T = gson
            .newJsonReader(StringReader(value))
            .use { jsonReader ->
                adapter.read(jsonReader)
            }
}
