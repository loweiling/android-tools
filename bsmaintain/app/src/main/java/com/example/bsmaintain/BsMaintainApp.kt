package com.example.bsmaintain

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bsmaintain.network.RetrofitClient
import com.example.bsmaintain.sync.StationSyncWorker
import java.util.concurrent.TimeUnit

class BsMaintainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RetrofitClient.init(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // App 啟動時先觸發一次(未達條件或 cache 已存在時會快速返回)
        WorkManager.getInstance(this).enqueueUniqueWork(
            "station_sync_oneshot",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<StationSyncWorker>()
                .setConstraints(constraints)
                .build()
        )

        // 每 6 小時自動同步一次
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_sync_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StationSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
    }
}
