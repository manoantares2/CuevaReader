package com.manu.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PdfConversionTest {
    @Test fun extractsPdfTextIntoSections() {
        val application = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(application)
        val file = File.createTempFile("pdftext", ".pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 12f)
                    it.newLineAtOffset(50f, 700f)
                    it.showText("A readable PDF for CuevaReader.")
                    it.endText()
                }
                document.save(file)
            }
            val sections = ImportViewModel(application).readPdf(file)
            assertEquals(1, sections.size)
            assertTrue(sections.single().paragraphs.joinToString(" ").contains("A readable PDF for CuevaReader."))
        } finally { file.delete() }
    }

    @Test fun pdfWithoutTextDoesNotProduceInventedContent() {
        val application = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(application)
        val file = File.createTempFile("pdfblank", ".pdf")
        try {
            PDDocument().use {
                it.addPage(PDPage())
                it.save(file)
            }
            assertTrue(ImportViewModel(application).readPdf(file).isEmpty())
        } finally { file.delete() }
    }
}