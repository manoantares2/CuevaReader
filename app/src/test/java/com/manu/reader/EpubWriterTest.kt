package com.manu.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class EpubWriterTest {
    @Test fun epubHasUncompressedFirstMimetypeAndValidMetadata() {
        val output = File.createTempFile("book", ".epub")
        try {
            EpubWriter.write(output, "A & B <test>", "und", listOf(ConvertedSection("One", listOf("Hola العربية português\u0001"))))
            ZipFile(output).use { zip ->
                val first = zip.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                assertEquals("application/epub+zip", zip.getInputStream(first).bufferedReader().readText())
                val metadata = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).bufferedReader().readText()
                val xml = Jsoup.parse(metadata, "", Parser.xmlParser())
                assertEquals("A & B <test>", xml.getElementsByTag("dc:title").text())
                assertEquals(1, xml.getElementsByTag("itemref").size)
                val text = zip.getInputStream(zip.getEntry("OEBPS/section0.xhtml")).bufferedReader().readText()
                assertFalse(text.contains('\u0001'))
                assertTrue(text.contains("العربية"))
                assertNotNull(zip.getEntry("META-INF/container.xml"))
                assertNotNull(zip.getEntry("OEBPS/nav.xhtml"))
            }
        } finally { output.delete() }
    }

    @Test fun docxPreservesParagraphsRunsAndHeadings() {
        val source = File.createTempFile("source", ".docx")
        try {
            ZipOutputStream(source.outputStream()).use {
                it.putNextEntry(ZipEntry("word/document.xml"))
                it.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter A</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter B</w:t></w:r></w:p><w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                it.closeEntry()
            }
            val sections = EpubWriter.readDocx(source)
            assertEquals(2, sections.size)
            assertEquals("Chapter A", sections[0].title)
            assertEquals(listOf("Hello world"), sections[0].paragraphs)
            assertEquals("Chapter B", sections[1].title)
        } finally { source.delete() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBookIsRejected() {
        val output = File.createTempFile("empty", ".epub")
        try { EpubWriter.write(output, "Empty", "und", emptyList()) } finally { output.delete() }
    }

    @Test fun generatedNavIncludesEverySectionInOrder() {
        val output = File.createTempFile("chapters", ".epub")
        try {
            EpubWriter.write(output, "Title", "und", listOf(ConvertedSection("First", listOf("one")), ConvertedSection("Second", listOf("two"))))
            ZipFile(output).use { zip ->
                val nav = Jsoup.parse(zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).bufferedReader().readText())
                assertEquals(listOf("section0.xhtml", "section1.xhtml"), nav.select("a").map { it.attr("href") })
                assertEquals(listOf("First", "Second"), nav.select("a").map { it.text() })
            }
        } finally { output.delete() }
    }
}