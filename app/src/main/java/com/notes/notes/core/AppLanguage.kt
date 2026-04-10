package com.notes.notes.core

import java.util.Locale

enum class AppLanguage(val code: String) {
    ZH_CN("zh-CN"),
    EN_US("en-US");

    fun asLocale(): Locale = when (this) {
        ZH_CN -> Locale.SIMPLIFIED_CHINESE
        EN_US -> Locale.US
    }

    companion object {
        fun fromCode(raw: String?): AppLanguage = when (raw?.lowercase()) {
            "en-us", "en", "en_us" -> EN_US
            else -> ZH_CN
        }
    }
}
