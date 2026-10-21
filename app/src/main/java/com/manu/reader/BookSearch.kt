package com.manu.reader

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

enum class BookSearchProvider(val endpoint: String, val label: Int) {
    CIUDAD_SEVA("https://z-lib.sk/s/", R.string.search_ciudad_seva),
    DUCKDUCKGO("https://duckduckgo.com/", R.string.search_duckduckgo_preferred),
    GOOGLE("https://www.google.com/search", R.string.search_google);

    fun searchUri(title: String): Uri = Uri.parse(endpoint).buildUpon().apply {
        if (this@BookSearchProvider == CIUDAD_SEVA) {
            appendPath(title.trim())
        } else {
            appendQueryParameter("q", "\"${title.trim()}\" filetype:pdf")
        }
    }.build()
}

class BookSearchProviderAdapter(context: Context) : ArrayAdapter<BookSearchProvider>(
    context, android.R.layout.simple_spinner_item, BookSearchProvider.entries.toTypedArray(),
) {
    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun bind(view: View, position: Int): View {
        val provider = getItem(position)!!
        return (view as TextView).apply {
            text = when (provider) {
                BookSearchProvider.DUCKDUCKGO -> "🦆"
                BookSearchProvider.GOOGLE -> ""
                BookSearchProvider.CIUDAD_SEVA -> "🌙★"
            }
            contentDescription = context.getString(provider.label)
            val icon = if (provider == BookSearchProvider.GOOGLE) {
                ContextCompat.getDrawable(context, R.drawable.ic_google_g)?.apply {
                    val size = (24 * resources.displayMetrics.density).toInt()
                    setBounds(0, 0, size, size)
                }
            } else null
            setCompoundDrawablesRelative(icon, null, null, null)
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(super.getView(position, convertView, parent), position)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(super.getDropDownView(position, convertView, parent), position)
}