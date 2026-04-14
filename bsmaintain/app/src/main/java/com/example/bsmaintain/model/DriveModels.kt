package com.example.bsmaintain.model



data class FileSearchResponse(
    val files: List<DriveFile>
)

data class DriveFile(
    val id: String,
    val name: String
)

data class FileMetadata(
    val modifiedTime: String
)