package com.manu.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LibraryTransferTest {
    @Test fun preservesAvailableName() {
        assertEquals("book.epub", Library.uniqueName("book.epub", setOf("other.epub")))
    }

    @Test fun neverOverwritesExistingNames() {
        assertEquals("book (3).pdf", Library.uniqueName("book.pdf", setOf("book.pdf", "book (2).pdf")))
    }

    @Test fun supportsNamesWithoutExtensionsAndMultipleDots() {
        assertEquals("book (2)", Library.uniqueName("book", setOf("book")))
        assertEquals("my.book (2).docx", Library.uniqueName("my.book.docx", setOf("my.book.docx")))
    }
}