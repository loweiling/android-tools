package com.example.bsmaintain.sms

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * 把 OTP 寫進 Firestore `codes` collection，桌機 Python listener 會在 < 1s 內推到。
 *
 * Schema:
 *   codes/{auto_id}
 *     ├─ code:        "827009"
 *     ├─ sender:      "123770"
 *     ├─ source:      "企業行動管理系統"
 *     ├─ raw:         "FROM 企業行動管理系統: ..."
 *     ├─ requestedAt: "2026-04-15 09:27:31.906"   # 從簡訊抓
 *     ├─ receivedAt:  serverTimestamp()           # Firestore 端時間，桌機 listener 用這個排序
 *     └─ consumed:    false                        # 桌機用完改 true
 */
object FirestoreUploader {

    private const val TAG = "FirestoreUploader"
    private const val COLLECTION = "codes"

    fun uploadOtp(otp: OtpParser.Otp, sender: String) {
        val data = mapOf(
            "code" to otp.code,
            "sender" to sender,
            "source" to "企業行動管理系統",
            "raw" to otp.raw,
            "requestedAt" to otp.requestedAt,
            "receivedAt" to FieldValue.serverTimestamp(),
            "consumed" to false,
        )

        Firebase.firestore.collection(COLLECTION)
            .add(data)
            .addOnSuccessListener { ref ->
                Log.i(TAG, "上傳成功 docId=${ref.id} code=${otp.code}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "上傳失敗 code=${otp.code}", e)
            }
    }
}
