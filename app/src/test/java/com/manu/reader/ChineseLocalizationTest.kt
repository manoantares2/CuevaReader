package com.manu.reader

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN")
class ChineseLocalizationTest {
    @Test fun resolvesChineseTextAndFormatArguments() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("打开书籍", context.getString(R.string.open_epub))
        assertEquals("第 3 章，共 12 章 · 25%", context.getString(R.string.progress_label, 3, 12, 25))
        assertEquals("15 分钟", context.getString(R.string.sleep_timer_minutes, 15))
        assertEquals("语音引擎：SherpaTTS", context.getString(R.string.engine_label, "SherpaTTS"))
        assertTrue(context.getString(R.string.get_books_url).contains("=chinese"))
        assertTrue(context.getString(R.string.get_books_message).contains("https://calibre-ebook.com/download"))
    }

    @Test @Config(qualifiers = "zh-rCN-night")
    fun inflatesSetupInChineseInDarkMode() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ManuReader)
        val view = LayoutInflater.from(context).inflate(R.layout.setup_guide, null)
        assertEquals("无需安装", view.findViewById<TextView>(R.id.useGoogle).text.toString())
        assertEquals("安装新的免费语音", view.findViewById<TextView>(R.id.showInstallSteps).text.toString())
    }
}