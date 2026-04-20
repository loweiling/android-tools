package com.wllo.bsmaintain.network

import com.google.gson.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

interface DriveService {
    @GET("uc?export=download")
    suspend fun getUniversalData(
        @Query("id") fileId: String,
    ): JsonElement
}
