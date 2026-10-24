package com.manu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class PageMarkerTest {
    @Test fun removesStandalonePageMarkerLines() {
        val text = "Primer párrafo.\n\nPágina 12\n\nSegundo párrafo.\npág. 13 de 300\n— Page 4/10 —\nFin."
        assertEquals("Primer párrafo.\n\n\nSegundo párrafo.\nFin.", ImportViewModel.stripPageMarkers(text))
    }

    @Test fun keepsPageWordsInsideSentences() {
        val text = "Volvió a la página 12 del diario.\nPágina 3 de la historia continúa aquí."
        assertEquals(text, ImportViewModel.stripPageMarkers(text))
    }

    @Test fun pageOnlyTextBecomesBlank() {
        assertTrue(ImportViewModel.stripPageMarkers("  PÁGINA 7  \n").isBlank())
    }

    @Test fun removesBarePageNumbersOnlyAtPageEdges() {
        val page = "12 \ntejuelas, rodeada de casas. \nEn 1997 llegó. \n215) \n"
        assertEquals("tejuelas, rodeada de casas.\nEn 1997 llegó.\n", ImportViewModel.cleanPdfPage(page))
    }

    @Test fun joinsHyphenatedWordsAcrossLines() {
        val page = "Identifica so- \nlamente su voca-\n \nción. Bien-Estar sigue."
        assertEquals("Identifica solamente su vocación. Bien-Estar sigue.", ImportViewModel.cleanPdfPage(page))
    }

    @Test fun hiddenHeadingsAreNotWrittenAsReadableText() {
        val output = File.createTempFile("pages", ".epub")
        try {
            EpubWriter.write(output, "Libro", "und", listOf(ConvertedSection("Página 1", listOf("Texto"), showHeading = false)))
            ZipFile(output).use { zip ->
                val section = zip.getInputStream(zip.getEntry("OEBPS/section0.xhtml")).bufferedReader().readText()
                assertFalse(section.contains("<h1>"))
                assertTrue(section.contains("<title>Página 1</title>"))
                val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).bufferedReader().readText()
                assertTrue(nav.contains("Página 1"))
            }
        } finally { output.delete() }
    }
}
