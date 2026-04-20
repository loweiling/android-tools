package com.wllo.bsmaintain.sms

/**
 * 解析 nmoss.cht.com.tw 的 OTP 簡訊。
 *
 * 期望格式：
 *   FROM 企業行動管理系統: 您申請的OTP簡訊動態密碼為 827009 , 申請時間為: 2026-04-15 09:27:31.906
 *
 * 寄件人 123770 也會發其他類型簡訊（上下班刷卡），所以一律靠 body 內含關鍵字過濾。
 */
object OtpParser {

    const val EXPECTED_SENDER = "123770"
    // 寬鬆關鍵字：OTP 系統未來若改格式（例如拿掉 OTP 字樣），只要還含「動態密碼」就抓得到。
    // 加上 sender=123770 過濾後不會誤判一般廣告簡訊。
    private const val KEYWORD = "動態密碼"

    // 「動態密碼為」後面的第一串純數字（不限長度，相容 4~10 位 OTP）
    private val CODE_REGEX = Regex("""動態密碼為\s*(\d+)""")
    private val REQUESTED_AT_REGEX = Regex("""申請時間為:\s*([\d\-: .]+)""")

    data class Otp(
        val code: String,
        val requestedAt: String?,
        val raw: String,
    )

    /** 不是 OTP 就回 null（呼叫端就不要送 Firestore） */
    fun parse(body: String?): Otp? {
        if (body.isNullOrBlank() || KEYWORD !in body) return null
        val code = CODE_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return null
        val requestedAt = REQUESTED_AT_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
        return Otp(code = code, requestedAt = requestedAt, raw = body)
    }
}
