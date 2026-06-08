package com.thievestrap

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import java.util.Locale

object LocaleHelper {

    fun applyLocale(context: Context) {
        val lang = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"
        val locale = if (lang == "ar") Locale("ar") else Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        // RTL support for Arabic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun applyRtl(view: View, context: Context) {
        val lang = getLang(context)
        view.layoutDirection = if (lang == "ar")
            View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    fun isRtl(context: Context): Boolean = getLang(context) == "ar"

    fun getLang(context: Context): String =
        context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"
}
