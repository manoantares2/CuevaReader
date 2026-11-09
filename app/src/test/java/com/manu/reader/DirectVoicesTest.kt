package com.manu.reader

import org.junit.Assert.*
import org.junit.Test

class DirectVoicesTest {
    @Test fun spanishDownloadsKeepRequestedModelsAndOrder() {
        assertEquals(3, DirectVoices.spanish.size)
        val models = listOf("es_MX-claude", "es_ES-miro", "es_AR-daniela")
        DirectVoices.spanish.zip(models).forEach { (voice, model) ->
            assertTrue(voice.url.startsWith("https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/2.0.1/"))
            assertTrue(voice.url.contains("sherpa-onnx-2.0.1-arm64-v8a-spa-tts-engine"))
            assertTrue(voice.url.endsWith("$model-high.apk"))
            assertTrue(voice.url.contains("arm64-v8a-spa-tts-engine"))
        }
    }

    @Test fun recognizesVerifiedSherpaOnnxPackageWithoutMatchingUnrelatedApps() {
        assertTrue(TtsEngines.isSherpaOnnx("com.k2fsa.sherpa.onnx.tts.engine"))
        assertFalse(TtsEngines.isSherpaOnnx("com.google.android.tts"))
        assertFalse(TtsEngines.isSherpaOnnx("com.k2fsa.sherpa.onnx.tts.enginefake"))
    }

    @Test fun providesCurrentMediumVoiceForEachOtherSupportedLanguage() {
        val voices = mapOf(
            "en" to DirectVoices.english.single(),
            "pt" to DirectVoices.portuguese.single(),
            "ar" to DirectVoices.arabic.single(),
            "zh" to DirectVoices.chinese.single(),
        )
        val models = listOf("en_US-amy", "pt_BR-cadu", "ar_JO-kareem", "zh_CN-xiao_ya")
        voices.values.zip(models).forEach { (voice, model) ->
            assertTrue(voice.url.contains("tts-engine-new/2.0.1/"))
            assertTrue(voice.url.contains("arm64-v8a"))
            assertTrue(voice.url.endsWith("$model-medium.apk"))
        }
        voices.forEach { (language, voice) -> assertEquals(voice, DirectVoices.forLanguage(language)?.single()) }
        assertNull(DirectVoices.forLanguage("fr"))
    }
}