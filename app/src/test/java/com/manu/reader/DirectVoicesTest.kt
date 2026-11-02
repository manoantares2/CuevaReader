package com.manu.reader

import org.junit.Assert.*
import org.junit.Test

class DirectVoicesTest {
    @Test fun spanishDownloadsKeepRequestedModelsAndOrder() {
        assertEquals(3, DirectVoices.spanish.size)
        val models = listOf("es_MX-claude", "es_ES-miro", "es_AR-daniela")
        DirectVoices.spanish.zip(models).forEach { (voice, model) ->
            assertTrue(voice.url.startsWith("https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/"))
            assertTrue(voice.url.endsWith("$model-high.apk"))
            assertTrue(voice.url.contains("arm64-v8a-spa-tts-engine"))
        }
    }

    @Test fun recognizesVerifiedSherpaOnnxPackageWithoutMatchingUnrelatedApps() {
        assertTrue(TtsEngines.isSherpaOnnx("com.k2fsa.sherpa.onnx.tts.engine"))
        assertFalse(TtsEngines.isSherpaOnnx("com.google.android.tts"))
        assertFalse(TtsEngines.isSherpaOnnx("com.k2fsa.sherpa.onnx.tts.enginefake"))
    }
}