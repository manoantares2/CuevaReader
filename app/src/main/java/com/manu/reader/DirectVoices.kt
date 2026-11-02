package com.manu.reader

data class DirectVoice(val label: Int, val url: String)

object DirectVoices {
    private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.8/"
    val spanish = listOf(
        DirectVoice(R.string.voice_grandfather_es, BASE + "sherpa-onnx-1.13.8-arm64-v8a-spa-tts-engine-vits-piper-es_MX-claude-high.apk"),
        DirectVoice(R.string.voice_man_es, BASE + "sherpa-onnx-1.13.8-arm64-v8a-spa-tts-engine-vits-piper-es_ES-miro-high.apk"),
        DirectVoice(R.string.voice_woman_es, BASE + "sherpa-onnx-1.13.8-arm64-v8a-spa-tts-engine-vits-piper-es_AR-daniela-high.apk"),
    )
}