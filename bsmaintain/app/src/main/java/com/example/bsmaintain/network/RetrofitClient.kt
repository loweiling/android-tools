package com.example.bsmaintain.network

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private var _driveService: DriveService? = null
    val driveService: DriveService get() = _driveService ?: error("RetrofitClient.init(context) 尚未呼叫")

    private var _driveMetaService: DriveMetaService? = null
    val driveMetaService: DriveMetaService get() = _driveMetaService ?: error("RetrofitClient.init(context) 尚未呼叫")

    fun init(context: Context) {
        if (_driveService != null) return

        _driveService = Retrofit.Builder()
            .baseUrl("https://drive.google.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveService::class.java)

        val metaClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-Android-Package", "com.example.bsmaintain")
                    .header("X-Android-Cert", "db9569f34ea8b6a614d59f753cb48e9eb49b6b66")
                    .build()
                chain.proceed(request)
            }
            .build()

        _driveMetaService = Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(metaClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveMetaService::class.java)
    }
}