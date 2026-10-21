package com.manu.reader

import android.app.Application
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookSearchTest {
    @Test fun providersPreserveExactTitleAndPdfFilter() {
        for (provider in listOf(BookSearchProvider.DUCKDUCKGO, BookSearchProvider.GOOGLE)) {
            val uri = provider.searchUri("  Cien años & más #1  ")
            assertEquals("\"Cien años & más #1\" filetype:pdf", uri.getQueryParameter("q"))
            assertEquals("https", uri.scheme)
            assertEquals(if (provider == BookSearchProvider.DUCKDUCKGO) "duckduckgo.com" else "www.google.com", uri.host)
        }
    }

    @Test fun ciudadSevaAppendsTitleAsAnEncodedPathWithoutQuery() {
        val uri = BookSearchProvider.CIUDAD_SEVA.searchUri("  Un título / con ? y #  ")
        assertEquals("https", uri.scheme)
        assertEquals("z-lib.sk", uri.host)
        assertEquals(listOf("s", "Un título / con ? y #"), uri.pathSegments)
        assertNull(uri.query)
        assertNull(uri.fragment)
    }

    @Test fun preferredProviderIsFirstAndRecycledRowsResetIcons() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BookSearchProviderAdapter(context)
        val parent = FrameLayout(context)
        assertEquals(BookSearchProvider.CIUDAD_SEVA, adapter.getItem(0))
        val google = adapter.getView(2, null, parent) as TextView
        assertEquals("", google.text.toString())
        assertNotNull(google.compoundDrawablesRelative[0])
        val duck = adapter.getView(1, google, parent) as TextView
        assertEquals("🦆", duck.text.toString())
        assertNull(duck.compoundDrawablesRelative[0])
        assertEquals(context.getString(R.string.search_duckduckgo_preferred), duck.contentDescription)
        val dropdown = adapter.getDropDownView(2, null, parent) as TextView
        assertNotNull(dropdown.compoundDrawablesRelative[0])
        val moon = adapter.getView(0, dropdown, parent) as TextView
        assertEquals("🌙★", moon.text.toString())
        assertNull(moon.compoundDrawablesRelative[0])
        assertEquals(context.getString(R.string.search_ciudad_seva), moon.contentDescription)
    }
}