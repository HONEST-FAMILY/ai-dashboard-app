package com.honestfamily.dashboard

object MessageFilters {
    private val ONE_TIME_CODE = Regex(
        "인증\\s?번호|승인\\s?번호|verification|(?<![a-z])otp(?![a-z])",
        RegexOption.IGNORE_CASE
    )

    private val PLACEHOLDER = Regex(
        "^((새로운|새)\\s*메시지(가|이)?(\\s*(있습니다|도착했습니다|왔습니다))?" +
            "|메시지가\\s*도착했습니다" +
            "|[0-9]+\\s*개의\\s*(새로운|새|읽지\\s*않은)\\s*메시지.*" +
            "|(새로운|새)\\s*메시지\\s*[0-9]+\\s*개.*" +
            "|[0-9]*\\s*new\\s+messages?)[.!]?$",
        RegexOption.IGNORE_CASE
    )

    fun isAd(text: String): Boolean = text.contains("(광고)")

    fun isPlaceholder(text: String): Boolean = PLACEHOLDER.matches(text.trim())

    fun isSmsNoise(text: String): Boolean {
        val trimmed = text.trim()
        return isAd(trimmed) || trimmed.startsWith("[Web발신]") || ONE_TIME_CODE.containsMatchIn(trimmed)
    }
}
