package com.manu.reader

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DocxReaderTest {
    private fun document(body: String): File = File.createTempFile("document", ".docx").also { file ->
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("word/document.xml"))
            it.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>").toByteArray())
            it.closeEntry()
        }
    }

    @Test fun readsDirectlyPreservingHeadingsOriginalAndCache() {
        val context = RuntimeEnvironment.getApplication()
        val file = document("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Primero</w:t></w:r></w:p><w:p><w:r><w:t>Hola mundo.</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Segundo</w:t></w:r></w:p><w:p><w:r><w:t>${"Texto largo. ".repeat(400)}</w:t></w:r></w:p>""")
        try {
            val before = file.readBytes()
            val cache = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
            val uri = Uri.fromFile(file)
            assertTrue(DocxReader.isDocx(context, uri))
            val book = DocxReader.parse(context, uri)
            assertEquals(listOf("Primero", "Segundo"), book.chapters.map { it.title })
            assertEquals(listOf(0, 2), book.chapters.map { it.start })
            assertEquals(listOf("Primero", "Hola mundo.", "Segundo"), book.paragraphs.take(3))
            assertTrue(book.paragraphs.all { it.length <= 3000 })
            assertEquals(book.paragraphs, DocxReader.parse(context, uri).paragraphs)
            assertArrayEquals(before, file.readBytes())
            assertEquals(cache, context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
        } finally { file.delete() }
    }

    @Test fun readsStandaloneHeadings() {
        val file = document("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Solo título</w:t></w:r></w:p>""")
        try {
            assertEquals(listOf("Solo título"), DocxReader.parse(RuntimeEnvironment.getApplication(), Uri.fromFile(file)).paragraphs)
        } finally { file.delete() }
    }

    @Test fun rejectsEmptyDocxAndRemovesTemporaryFile() {
        val context = RuntimeEnvironment.getApplication()
        val file = document("<w:p/>")
        val cache = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        try {
            try {
                DocxReader.parse(context, Uri.fromFile(file))
                fail("Empty document should be rejected")
            } catch (e: IllegalArgumentException) {
                assertEquals(context.getString(R.string.error_no_docx_text), e.message)
            }
            assertEquals(cache, context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(file.exists())
        } finally { file.delete() }
    }
}