package com.miaoxiaohu.tvinstall

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** User-visible settings, persisted in SharedPreferences. */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tv_apk_installer", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()

    var pinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PIN_ENABLED, value).apply()

    /** Six digits, generated once on first launch. */
    val pin: String
        get() = prefs.getString(KEY_PIN, null) ?: generatePin().also { newPin ->
            prefs.edit().putString(KEY_PIN, newPin).apply()
        }

    /** Install as soon as an upload finishes, without waiting for the web page to ask. */
    var autoInstall: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSTALL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_INSTALL, value).apply()

    /** Bring the service back after a reboot - TVs rarely get their app reopened by hand. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** Delete an APK from the pending list once it has been installed. */
    var removeAfterInstall: Boolean
        get() = prefs.getBoolean(KEY_REMOVE_AFTER_INSTALL, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOVE_AFTER_INSTALL, value).apply()

    fun newPin(): String = generatePin().also {
        prefs.edit().putString(KEY_PIN, it).apply()
    }

    private fun generatePin(): String {
        val random = SecureRandom()
        val sb = StringBuilder(PIN_LENGTH)
        repeat(PIN_LENGTH) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_PORT = 10000
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val PIN_LENGTH = 6

        private const val KEY_PORT = "port"
        private const val KEY_PIN = "pin"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_REMOVE_AFTER_INSTALL = "remove_after_install"
    }
}