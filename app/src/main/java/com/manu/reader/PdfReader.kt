package com.manu.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfReader {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun isPdf(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri) == "application/pdf" || displayName(context, uri).endsWith(".pdf", true)

    fun parse(context: Context, uri: Uri): Book {
        val source = File.createTempFile("reading-", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 100 * 1024 * 1024L) { context.getString(R.string.conversion_too_large) }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(context.getString(R.string.conversion_cannot_read))
            val sections = readSections(context, source)
            require(sections.isNotEmpty()) { context.getString(R.string.conversion_no_pdf_text) }
            val paragraphs = mutableListOf<String>()
            val chapters = sections.map { section ->
                val chapter = Chapter(section.title, paragraphs.size)
                paragraphs += section.paragraphs.flatMap(EpubParser::split)
                chapter
            }
            val title = displayName(context, uri).substringBeforeLast('.').ifBlank { context.getString(R.string.untitled) }
            return Book(title, chapters, paragraphs)
        } finally {
            source.delete()
        }
    }

    internal fun readSections(context: Context, file: File): List<ConvertedSection> {
        PDFBoxResourceLoader.init(context)
        return PDDocument.load(file).use { document ->
            require(document.currentAccessPermission.canExtractContent()) { context.getString(R.string.conversion_pdf_restricted) }
            require(document.numberOfPages <= 500) { context.getString(R.string.conversion_too_large) }
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            buildList {
                var extracted = 0L
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(document)
                    extracted += text.length
                    require(extracted <= 20 * 1024 * 1024L) { context.getString(R.string.conversion_too_large) }
                    val paragraphs = ImportViewModel.cleanPdfPage(text)
                        .split(Regex("\n\\s*\n"))
                        .map { it.replace(Regex("(?<=\\S)\n(?=\\S)"), " ").trim() }
                        .filter(String::isNotBlank)
                    if (paragraphs.isNotEmpty()) {
                        add(ConvertedSection(context.getString(R.string.converted_page, page), paragraphs, showHeading = false))
                    }
                }
            }
        }
    }
}