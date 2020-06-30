package com.navin.flintstones.rxwebsocket_app

import com.google.gson.annotations.SerializedName

data class SampleDataModel(
        @field:SerializedName("id") val id: Int,
        @field:SerializedName("message") val message: String
)