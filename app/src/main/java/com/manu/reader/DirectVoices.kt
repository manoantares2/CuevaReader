package com.manu.reader

data class DirectVoice(val label: Int, val url: String)

object DirectVoices {
    private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/2.0.1/"

    val spanish = listOf(
        DirectVoice(R.string.voice_grandfather_es, BASE + "sherpa-onnx-2.0.1-arm64-v8a-spa-tts-engine-vits-piper-es_MX-claude-high.apk"),
        DirectVoice(R.string.voice_man_es, BASE + "sherpa-onnx-2.0.1-arm64-v8a-spa-tts-engine-vits-piper-es_ES-miro-high.apk"),
        DirectVoice(R.string.voice_woman_es, BASE + "sherpa-onnx-2.0.1-arm64-v8a-spa-tts-engine-vits-piper-es_AR-daniela-high.apk"),
    )

    val english = listOf(
        DirectVoice(R.string.voice_amy_en, BASE + "sherpa-onnx-2.0.1-arm64-v8a-eng-tts-engine-vits-piper-en_US-amy-medium.apk"),
    )

    val portuguese = listOf(
        DirectVoice(R.string.voice_cadu_pt_br, BASE + "sherpa-onnx-2.0.1-arm64-v8a-por-tts-engine-vits-piper-pt_BR-cadu-medium.apk"),
    )

    val arabic = listOf(
        DirectVoice(R.string.voice_kareem_ar_jo, BASE + "sherpa-onnx-2.0.1-arm64-v8a-ara-tts-engine-vits-piper-ar_JO-kareem-medium.apk"),
    )

    val chinese = listOf(
        DirectVoice(R.string.voice_xiao_ya_zh_cn, BASE + "sherpa-onnx-2.0.1-arm64-v8a-zho-tts-engine-vits-piper-zh_CN-xiao_ya-medium.apk"),
    )

    fun forLanguage(language: String): List<DirectVoice>? = when (language) {
        "es" -> spanish
        "en" -> english
        "pt" -> portuguese
        "ar" -> arabic
        "zh" -> chinese
        else -> null
    }
}