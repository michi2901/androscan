package com.androscan.app.util

import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object IdGenerator {
    private val formatter = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun hardwareId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    fun create(context: Context, atMillis: Long = System.currentTimeMillis()): String {
        val hw = hardwareId(context)
        val ts = formatter.format(Date(atMillis))
        return "$hw-$ts"
    }
}
