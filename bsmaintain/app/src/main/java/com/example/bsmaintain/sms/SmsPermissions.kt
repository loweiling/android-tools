package com.example.bsmaintain.sms

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * SMS runtime 權限處理。Manifest 宣告完還要 runtime 同意，使用者拒絕的話 SmsReceiver 不會被觸發。
 *
 * 用法（在 MainActivity.onCreate 末尾呼叫一次）：
 *     SmsPermissions.requestIfNeeded(this)
 */
object SmsPermissions {

    private const val REQUEST_CODE = 4801

    private val PERMS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    fun allGranted(activity: Activity): Boolean = PERMS.all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestIfNeeded(activity: Activity) {
        if (allGranted(activity)) return
        ActivityCompat.requestPermissions(activity, PERMS, REQUEST_CODE)
    }
}
