package com.wllo.bsmaintain.sms

import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

/**
 * 確保 Firebase Auth 有匿名登入身分。Firestore 規則要 `request.auth != null` 才放行寫入。
 *
 * 在 Application.onCreate() 呼叫一次即可，session 會持續存在裝置上。
 */
object FirebaseAuthBootstrap {

    private const val TAG = "FirebaseAuthBootstrap"

    fun ensureAnonymousSignedIn() {
        val auth = Firebase.auth
        if (auth.currentUser != null) {
            Log.d(TAG, "已有 user uid=${auth.currentUser?.uid}")
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { Log.i(TAG, "匿名登入成功 uid=${it.user?.uid}") }
            .addOnFailureListener { Log.e(TAG, "匿名登入失敗", it) }
    }
}
