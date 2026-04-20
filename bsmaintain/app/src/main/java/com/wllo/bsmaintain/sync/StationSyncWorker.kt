package com.wllo.bsmaintain.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wllo.bsmaintain.Constants
import com.wllo.bsmaintain.network.RetrofitClient
import java.io.File

class StationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            RetrofitClient.init(applicationContext)
            val fileId = Constants.DRIVE_FILE_ID
            val prefs = applicationContext.getSharedPreferences("drive_cache", Context.MODE_PRIVATE)
            val storedModifiedTime = prefs.getString("modified_$fileId", null)

            val modifiedTime = try {
                RetrofitClient.driveMetaService.getFileMetadata(fileId, apiKey = Constants.DRIVE_API_KEY).modifiedTime
            } catch (e: Exception) {
                Log.w("StationSync", "metadata fetch failed: ${e.message}"); null
            }

            val cacheFile = File(applicationContext.cacheDir, "drive_cache_$fileId.json")
            if (modifiedTime != null && modifiedTime == storedModifiedTime && cacheFile.exists()) {
                Log.d("StationSync", "up-to-date, skip")
                return Result.success()
            }

            val json = RetrofitClient.driveService.getUniversalData(fileId)
            cacheFile.writeText(json.toString())
            if (modifiedTime != null) prefs.edit().putString("modified_$fileId", modifiedTime).apply()
            Log.d("StationSync", "synced, modifiedTime=$modifiedTime")
            Result.success()
        } catch (e: Exception) {
            Log.e("StationSync", "sync failed", e)
            Result.retry()
        }
    }
}
