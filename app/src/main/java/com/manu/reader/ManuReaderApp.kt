package com.manu.reader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ManuReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before any activity attaches its context, or the saved mode is only partially applied.
        AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
    }

    companion object {
        const val PREFS_UI = "ui"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
