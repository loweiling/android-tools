package com.example.bsmaintain.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast

/**
 * 收到簡訊 → 過濾 OTP → 目前先 Log/Toast，下一階段接 Firestore 上傳。
 *
 * Manifest 註冊：
 *   <receiver android:name=".sms.SmsReceiver" android:exported="true"
 *             android:permission="android.permission.BROADCAST_SMS">
 *       <intent-filter android:priority="999">
 *           <action android:name="android.provider.Telephony.SMS_RECEIVED" />
 *       </intent-filter>
 *   </receiver>
 *
 * 權限：RECEIVE_SMS（runtime 要由使用者同意）。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // 同一通簡訊可能被切成多段 PDU，依 originatingAddress 合併
        val sender = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        Log.i(TAG, "收到簡訊 sender=$sender, body=$body")

        // sender 過濾（避免不相關號碼）
        if (sender.takeLast(OtpParser.EXPECTED_SENDER.length) != OtpParser.EXPECTED_SENDER) {
            Log.d(TAG, "sender 不是 ${OtpParser.EXPECTED_SENDER}，略過")
            return
        }

        val otp = OtpParser.parse(body)
        if (otp == null) {
            Log.d(TAG, "body 不含 OTP 關鍵字（可能是刷卡通知），略過")
            return
        }

        Log.i(TAG, "OTP 解析成功 code=${otp.code} requestedAt=${otp.requestedAt}")
        Toast.makeText(context, "OTP: ${otp.code} → 上傳中", Toast.LENGTH_SHORT).show()

        FirestoreUploader.uploadOtp(otp, sender)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
