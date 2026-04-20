package com.wllo.bsmaintain.network

import com.wllo.bsmaintain.model.FileMetadata
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DriveMetaService {
    @GET("drive/v3/files/{fileId}")
    suspend fun getFileMetadata(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = "modifiedTime",
        @Query("key") apiKey: String,
    ): FileMetadata
}
