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
