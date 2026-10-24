package com.manu.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit

data class LibraryBook(val name: String, val uri: Uri)

object Library {
    private const val PREFS = "library"
    private const val KEY_FOLDER = "folder"
    private const val KEY_PROMPTED = "prompted"
    private const val MAX_DEPTH = 4

    // Opens the folder picker at /storage/emulated/0/Reader when it already exists.
    val suggestedFolder: Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Reader")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? {
        val uri = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return null
        return uri.takeIf { tree -> context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } }
    }

    fun setFolder(context: Context, tree: Uri) = prefs(context).edit { putString(KEY_FOLDER, tree.toString()) }

    // Unlike folder(), this also checks the provider: a persisted grant can outlive the folder.
    fun writableFolder(context: Context): Uri? = folder(context)?.takeIf { tree ->
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } &&
            runCatching {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                context.contentResolver.query(root, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
                    it.moveToFirst() && it.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE != 0
                } == true
            }.getOrDefault(false)
    }

    fun contains(context: Context, tree: Uri, source: Uri): Boolean = runCatching {
        if (tree.authority != source.authority) return@runCatching false
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.getDocumentId(root) == DocumentsContract.getDocumentId(source) ||
            DocumentsContract.isChildDocument(context.contentResolver, root, source)
    }.getOrDefault(false)

    data class Transfer(val uri: Uri, val originalDeleted: Boolean)

    // Copy first, close both streams, and only then ask the source provider to delete.
    fun moveInto(context: Context, source: Uri, tree: Uri): Transfer {
        val resolver = context.contentResolver
        var stage = "check library folder"
        var target: Uri? = null
        try {
            check(writableFolder(context) == tree) { "Library folder is missing or not writable: $tree" }
            stage = "read source name"
            var name = source.lastPathSegment ?: "document"
            resolver.query(source, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
            stage = "read source type"
            val mime = resolver.getType(source) ?: when (name.substringAfterLast('.').lowercase()) {
                "epub" -> ImportViewModel.EPUB_MIME
                "pdf" -> "application/pdf"
                "docx" -> ImportViewModel.DOCX_MIME
                else -> "application/octet-stream"
            }
            stage = "list library folder"
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val names = mutableSetOf<String>()
            resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) names += it.getString(0).orEmpty()
            } ?: error("Library folder query returned no cursor")
            val candidate = uniqueName(name, names)
            stage = "create '$candidate' ($mime)"
            target = DocumentsContract.createDocument(resolver, parent, mime, candidate)
                ?: error("Provider refused to create the document")
            stage = "copy contents"
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output) }
                    ?: error("Cannot open the library copy for writing")
            } ?: error("Cannot open the source for reading")
        } catch (e: Exception) {
            target?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw IngestionException(stage, e)
        }
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, source) }
            .onFailure { IngestionLog.warn(context, "move to library", source, "Original kept: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        return Transfer(target!!, deleted)
    }

    internal fun uniqueName(name: String, names: Set<String>): String {
        val extension = name.substringAfterLast('.', "")
        val stem = if (extension.isEmpty()) name else name.substringBeforeLast('.')
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var candidate = name
        var number = 2
        while (candidate in names) candidate = "$stem (${number++})$suffix"
        return candidate
    }

    fun wasPrompted(context: Context) = prefs(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) = prefs(context).edit { putBoolean(KEY_PROMPTED, true) }

    fun folderName(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "/" }

    fun list(context: Context, tree: Uri): List<LibraryBook> {
        val books = mutableListOf<LibraryBook>()
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)

        fun walk(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) walk(id, depth + 1)
                    } else if (listOf(".epub", ".pdf", ".docx").any { name.endsWith(it, true) } ||
                        mime in listOf(ImportViewModel.EPUB_MIME, "application/pdf", ImportViewModel.DOCX_MIME)
                    ) {
                        val label = if (name.endsWith(".epub", true)) name.substringBeforeLast('.') else name
                        books += LibraryBook(label, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return books.sortedBy { it.name.lowercase() }
    }
}
