package com.manu.reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class NeedsDestination(val file: File, val name: String, val source: Uri) : State
        data class Done(val uri: Uri, val converted: Boolean) : State
        data class Failed(val message: String) : State
    }

    private val context = application
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    var destinationRequested = false

    fun acknowledge() {
        mutableState.value = State.Idle
    }

    fun restoreExport(path: String?, name: String?, source: String?) {
        if (mutableState.value != State.Idle || path == null || name == null || source == null) return
        val file = File(path)
        if (file.parentFile == context.cacheDir && file.exists()) {
            mutableState.value = State.NeedsDestination(file, name, Uri.parse(source))
            destinationRequested = true
        }
    }

    fun open(uri: Uri) {
        if (mutableState.value == State.Busy || mutableState.value is State.NeedsDestination) return
        mutableState.value = State.Busy
        viewModelScope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) { import(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = State.Failed(context.getString(R.string.conversion_failed, e.message.orEmpty()))
            }
        }
    }

    private fun import(uri: Uri): State {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val isDocx = mime == DOCX_MIME || name.endsWith(".docx", true)
        if (!isPdf && !isDocx) return State.Done(uri, false)
        val source = File.createTempFile("source-", if (isPdf) ".pdf" else ".docx", context.cacheDir)
        val result = File.createTempFile("converted-", ".epub", context.cacheDir)
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
            val sections = if (isPdf) readPdf(source) else EpubWriter.readDocx(source)
            require(sections.any { it.paragraphs.any(String::isNotBlank) }) {
                context.getString(if (isPdf) R.string.conversion_no_pdf_text else R.string.error_no_text)
            }
            val title = name.substringBeforeLast('.').ifBlank { context.getString(R.string.untitled) }
            EpubWriter.write(result, title, "und", sections)
            val filename = title.replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(100) + ".epub"
            val tree = Library.folder(context)?.takeIf { folder ->
                context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isWritePermission }
            }
            if (tree != null) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val target = runCatching {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val names = mutableSetOf<String>()
                    context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                        while (it.moveToNext()) names += it.getString(0).orEmpty()
                    }
                    var candidate = filename
                    var suffix = 2
                    while (candidate in names) candidate = filename.removeSuffix(".epub") + " (${suffix++}).epub"
                    DocumentsContract.createDocument(context.contentResolver, parent, EPUB_MIME, candidate)
                        ?: error("Cannot create document")
                }.getOrNull()
                if (target != null) {
                    try {
                        writeTo(result, target)
                        result.delete()
                        return State.Done(target, true)
                    } catch (_: Exception) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                    }
                }
            }
            destinationRequested = false
            return State.NeedsDestination(result, filename, uri)
        } catch (e: Exception) {
            result.delete()
            throw e
        } finally {
            source.delete()
        }
    }

    internal fun readPdf(file: File): List<ConvertedSection> {
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
                    val paragraphs = text.replace("\r", "")
                        .split(Regex("\n\\s*\n"))
                        .map { it.replace(Regex("(?<=\\S)\n(?=\\S)"), " ").trim() }
                        .filter(String::isNotBlank)
                    if (paragraphs.isNotEmpty()) add(ConvertedSection(context.getString(R.string.converted_page, page), paragraphs))
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0).orEmpty()
            }
        }
        return uri.lastPathSegment ?: "document"
    }

    private fun writeTo(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: error(context.getString(R.string.conversion_cannot_save))
    }

    fun save(uri: Uri?) {
        val pending = mutableState.value as? State.NeedsDestination ?: return
        if (uri == null) {
            pending.file.delete()
            destinationRequested = false
            acknowledge()
            return
        }
        mutableState.value = State.Busy
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeTo(pending.file, uri)
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                mutableState.value = State.Done(uri, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                mutableState.value = State.Failed(context.getString(R.string.conversion_failed, e.message.orEmpty()))
            } finally {
                pending.file.delete()
                destinationRequested = false
            }
        }
    }

    companion object {
        const val EPUB_MIME = "application/epub+zip"
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}