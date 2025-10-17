package com.developerfromjokela.opencarwings.sms

import android.content.Context
import android.content.SharedPreferences

class PrefsHelper(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ENCRYPTION = "encryption"

    }

    var server: String?
        get() = prefs.getString(KEY_SERVER_URL, "biz.viaaq.eu")
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).commit().let { value }

    var encryption: String?
        get() = prefs.getString(KEY_ENCRYPTION, null)
        set(value) = prefs.edit().putString(KEY_ENCRYPTION, value).commit().let { value }


    fun clearAll() {
        prefs.edit().clear().commit()
    }
}