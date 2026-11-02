package com.manu.reader

import android.content.Context
import android.net.Uri
import java.io.File

object DocxReader {
    fun isDocx(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri) == ImportViewModel.DOCX_MIME ||
            PdfReader.displayName(context, uri).endsWith(".docx", true)

    fun parse(context: Context, uri: Uri): Book {
        val source = File.createTempFile("reading-", ".docx", context.cacheDir)
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
            val sections = EpubWriter.readDocx(source)
            val title = PdfReader.displayName(context, uri).substringBeforeLast('.').ifBlank { context.getString(R.string.untitled) }
            val paragraphs = mutableListOf<String>()
            val chapters = mutableListOf<Chapter>()
            sections.forEachIndexed { index, section ->
                val text = buildList {
                    if (section.title.isNotBlank()) add(section.title)
                    addAll(section.paragraphs.filter(String::isNotBlank))
                }.flatMap(EpubParser::split)
                if (text.isNotEmpty()) {
                    chapters += Chapter(section.title.ifBlank {
                        if (sections.size == 1) title else context.getString(R.string.chapter_number, index + 1)
                    }, paragraphs.size)
                    paragraphs += text
                }
            }
            require(paragraphs.isNotEmpty()) { context.getString(R.string.error_no_docx_text) }
            return Book(title, chapters, paragraphs)
        } finally {
            source.delete()
        }
    }
}