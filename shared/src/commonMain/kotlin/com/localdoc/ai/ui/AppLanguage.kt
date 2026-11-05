package com.localdoc.ai.ui

/** Языки интерфейса. Названия даны на самом языке (автонимы) и не переводятся. */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    RU("ru", "Русский"),
    EN("en", "English"),
    UK("uk", "Українська"),
    DE("de", "Deutsch"),
    ES("es", "Español");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
