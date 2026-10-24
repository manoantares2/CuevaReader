package com.manu.reader

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OnboardingTest {
    private fun setup(): View {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ManuReader)
        return LayoutInflater.from(context).inflate(R.layout.setup_guide, null)
    }

    @Test @Config(qualifiers = "es")
    fun spanishHeadingsMatchRequestedCopy() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("Dame una voz, hay dos opciones", context.getString(R.string.flow_voice_title))
        assertEquals("No instalar nada", context.getString(R.string.use_google_for_now))
        assertEquals("Usar software de Google en mi teléfono por ahora", context.getString(R.string.google_option_intro))
        assertEquals("Instala una nueva voz gratuita", context.getString(R.string.flow_install_free_voice))
        assertEquals("Tres pasos para leer", context.getString(R.string.flow_steps_title))
        assertEquals("Un lugar para tus libros", context.getString(R.string.flow_library_title))
        assertEquals("Lecturas gratis", context.getString(R.string.flow_books_title))
        assertTrue(context.getString(R.string.flow_document_intro).endsWith("Sólo el texto, sin Imágenes"))
    }

    @Test fun detailedAlternativesAreInitiallyCollapsed() {
        val view = setup()
        assertEquals(View.GONE, view.findViewById<View>(R.id.installSteps).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.engineList).visibility)
        val parent = view as android.view.ViewGroup
        assertTrue(parent.indexOfChild(view.findViewById(R.id.useGoogle)) < parent.indexOfChild(view.findViewById(R.id.showInstallSteps)))
        assertNotNull(view.findViewById<View>(R.id.installVoice))
        assertNotNull(view.findViewById<View>(R.id.openVoiceEngine))
        assertNotNull(view.findViewById<View>(R.id.checkAgain))
        assertNotNull(view.findViewById<View>(R.id.voiceHelp))
        assertNotNull(view.findViewById<View>(R.id.softwareFreedom))
    }

    @Test @Config(qualifiers = "es-night")
    fun darkSetupUsesTranslatedActions() {
        val view = setup()
        assertEquals("Otras voces", view.findViewById<TextView>(R.id.otherVoices).text.toString())
        assertEquals("Necesito ayuda", view.findViewById<TextView>(R.id.voiceHelp).text.toString())
    }
}