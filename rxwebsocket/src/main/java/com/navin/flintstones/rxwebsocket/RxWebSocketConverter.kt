package com.navin.flintstones.rxwebsocket

import java.io.IOException
import java.lang.reflect.Type

interface WebSocketConverter<F : Any, T : Any> {
    @Throws(IOException::class)
    fun convert(value: F): T

    /** Creates convertor instances based on a type and target usage.  */
    abstract class Factory {
        open fun responseBodyConverter(type: Type): WebSocketConverter<String, *>? = null

        open fun requestBodyConverter(type: Type): WebSocketConverter<*, String>? = null
    }
}