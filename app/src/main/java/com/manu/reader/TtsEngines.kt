package com.manu.reader

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import androidx.core.content.edit

data class EngineInfo(val name: String, val packageName: String, val url: String, @StringRes val description: Int)

data class InstalledEngine(val packageName: String, val label: String)

object TtsEngines {
    const val GOOGLE_PACKAGE = "com.google.android.tts"

    // Open source engines, in order of preference.
    val recommended = listOf(
        EngineInfo(
            "SherpaTTS",
            "org.woheller69.ttsengine",
            "https://f-droid.org/packages/org.woheller69.ttsengine/",
            R.string.engine_sherpa_description,
        ),
        EngineInfo(
            "RHVoice",
            "com.github.olga_yakovleva.rhvoice.android",
            "https://f-droid.org/packages/com.github.olga_yakovleva.rhvoice.android/",
            R.string.engine_rhvoice_description,
        ),
        EngineInfo(
            "eSpeak NG",
            "com.reecedunn.espeak",
            "https://f-droid.org/packages/com.reecedunn.espeak/",
            R.string.engine_espeak_description,
        ),
    )

    fun installedPackages(context: Context): Set<String> =
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
            .toSet()

    fun preferredInstalled(context: Context): EngineInfo? {
        val installed = installedPackages(context)
        return recommended.firstOrNull { it.packageName in installed }
    }

    fun canStartReading(context: Context): Boolean = preferredInstalled(context) != null ||
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null) == GOOGLE_PACKAGE &&
            GOOGLE_PACKAGE in installedPackages(context))

    fun installedEngines(context: Context): List<InstalledEngine> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { InstalledEngine(it.serviceInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
    }

    // In-app choice, overridden whenever the system default engine changes; falls back to an open source one.
    fun selected(context: Context): InstalledEngine? {
        val installed = installedEngines(context)
        if (installed.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val systemDefault = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        val lastSeenDefault = prefs.getString(KEY_LAST_SYSTEM_DEFAULT, null)
        if (systemDefault != null && systemDefault != lastSeenDefault) {
            prefs.edit {
                putString(KEY_LAST_SYSTEM_DEFAULT, systemDefault)
                // On first run keep the open source default instead of the preinstalled system engine.
                if (lastSeenDefault != null && installed.any { it.packageName == systemDefault }) {
                    putString(KEY_ENGINE, systemDefault)
                }
            }
        }

        val chosen = prefs.getString(KEY_ENGINE, null)
        return installed.firstOrNull { it.packageName == chosen }
            ?: recommended.firstNotNullOfOrNull { r -> installed.firstOrNull { it.packageName == r.packageName } }
            ?: installed.first()
    }

    fun select(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_ENGINE, packageName) }
    }

    private const val PREFS = "engine"
    private const val KEY_ENGINE = "engine"
    private const val KEY_LAST_SYSTEM_DEFAULT = "last_system_default"
}
