package com.navin.flintstones.rxwebsocket

@FunctionalInterface
interface WebSocketInterceptor {
    fun intercept(data: String?): String?

    companion object {
        inline operator fun invoke(crossinline action: (String?) -> String?) =
                object : WebSocketInterceptor {
                    override fun intercept(data: String?): String? = action(data)
                }
    }
}