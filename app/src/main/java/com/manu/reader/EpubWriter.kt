package com.manu.reader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ConvertedSection(val title: String, val paragraphs: List<String>)

object EpubWriter {
    private const val MAX_XML_BYTES = 20 * 1024 * 1024

    fun readDocx(file: File): List<ConvertedSection> = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("Invalid DOCX")
        val bytes = zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_XML_BYTES) { "DOCX text is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val xml = Jsoup.parse(String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
        val sections = mutableListOf<ConvertedSection>()
        var title = ""
        val paragraphs = mutableListOf<String>()
        fun flush() {
            if (paragraphs.isNotEmpty()) sections += ConvertedSection(title, paragraphs.toList())
            paragraphs.clear()
        }
        for (p in xml.getElementsByTag("w:p")) {
            val text = buildString {
                for (el in p.allElements) {
                    when (el.tagName()) {
                        "w:t" -> append(el.wholeText())
                        "w:tab" -> append(' ')
                        "w:br", "w:cr" -> append('\n')
                    }
                }
            }.trim()
            if (text.isEmpty()) continue
            val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty()
            if (style.equals("Heading1", true) || style.equals("Title", true)) {
                flush()
                title = text
            } else {
                paragraphs += text
            }
        }
        flush()
        sections
    }

    fun write(file: File, title: String, language: String, sections: List<ConvertedSection>) {
        require(sections.any { it.paragraphs.any(String::isNotBlank) }) { "No readable text" }
        ZipOutputStream(file.outputStream()).use { zip ->
            val mime = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val labels = sections.mapIndexed { i, s -> s.title.ifBlank { if (sections.size == 1) title else "${i + 1}" } }
            val manifest = sections.indices.joinToString("") { """<item id="s$it" href="section$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = sections.indices.joinToString("") { """<itemref idref="s$it"/>""" }
            val modified = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
            entry("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${escape(title)}</dc:title><dc:language>${escape(language)}</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
            val links = sections.indices.joinToString("") { """<li><a href="section$it.xhtml">${escape(labels[it])}</a></li>""" }
            entry("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${escape(title)}</title></head><body><nav epub:type="toc"><ol>$links</ol></nav></body></html>""")
            for ((i, section) in sections.withIndex()) {
                val paragraphs = section.paragraphs.joinToString("") { "<p>${escape(it)}</p>" }
                entry("OEBPS/section$i.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${escape(language)}"><head><title>${escape(labels[i])}</title></head><body><h1>${escape(labels[i])}</h1>$paragraphs</body></html>""")
            }
        }
    }

    private fun escape(text: String): String = buildString {
        text.codePoints().forEach { code ->
            when (code) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                39 -> append("&apos;")
                9, 10, 13 -> appendCodePoint(code)
                in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(code)
            }
        }
    }
}