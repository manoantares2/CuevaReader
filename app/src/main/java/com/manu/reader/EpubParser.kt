package com.manu.reader

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

data class Chapter(val title: String, val start: Int)

class Book(val title: String, val chapters: List<Chapter>, val paragraphs: List<String>) {
    // Cumulative character counts; charOffsets[i] is where paragraph i starts.
    val charOffsets: IntArray by lazy {
        IntArray(paragraphs.size + 1).also { for (i in paragraphs.indices) it[i + 1] = it[i] + paragraphs[i].length }
    }

    fun chapterEnd(chapter: Int): Int = chapters.getOrNull(chapter + 1)?.start ?: paragraphs.size

    fun chapterIndexOf(paragraph: Int): Int {
        var index = 0
        for (i in chapters.indices) {
            if (chapters[i].start <= paragraph) index = i else break
        }
        return index
    }
}

object EpubParser {
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, pre, blockquote, dt, dd, figcaption, td"
    private const val MAX_CHUNK = 3000

    fun parse(context: Context, uri: Uri): Book {
        val tmp = File(context.cacheDir, "current.epub")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            return ZipFile(tmp).use { parseZip(context, it) }
        } finally {
            tmp.delete()
        }
    }

    private fun parseZip(context: Context, zip: ZipFile): Book {
        val container = readXml(zip, "META-INF/container.xml")
        val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?: error("Invalid EPUB: missing rootfile")
        val opf = readXml(zip, opfPath)
        val baseDir = opfPath.substringBeforeLast('/', "")

        val title = (opf.getElementsByTag("dc:title").firstOrNull() ?: opf.getElementsByTag("title").firstOrNull())
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.untitled)
        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }

        val chapters = mutableListOf<Chapter>()
        val paragraphs = mutableListOf<String>()
        // "path" and "path#id" -> index of the first paragraph at or after that point.
        val anchors = mutableMapOf<String, Int>()
        for (ref in opf.getElementsByTag("itemref")) {
            if (ref.attr("linear") == "no") continue
            val href = manifest[ref.attr("idref")] ?: continue
            val path = resolve(baseDir, href)
            val entry = zip.getEntry(path) ?: continue
            val html = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
            val body = html.body()
            val start = paragraphs.size
            anchors.putIfAbsent(path, start)

            for (el in body.allElements) {
                if (el.id().isNotEmpty()) anchors.putIfAbsent("$path#${el.id()}", paragraphs.size)
                // Leaf blocks only, avoids duplicated text.
                if (!el.`is`(BLOCKS) || el.select(BLOCKS).size != 1) continue
                val text = el.text().trim()
                if (text.isEmpty()) continue
                el.allElements.forEach { if (it.id().isNotEmpty()) anchors.putIfAbsent("$path#${it.id()}", paragraphs.size) }
                paragraphs += split(text)
            }
            if (paragraphs.size == start) body.text().trim().takeIf { it.isNotEmpty() }?.let { paragraphs += split(it) }
            if (paragraphs.size == start) continue

            val heading = body.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: html.title().trim().takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.chapter_number, chapters.size + 1)
            chapters += Chapter(heading, start)
        }
        if (paragraphs.isEmpty()) error(context.getString(R.string.error_no_text))
        val toc = runCatching { readToc(context, zip, opf, baseDir, anchors, paragraphs.size) }.getOrDefault(emptyList())
        return Book(title, if (toc.size >= 2) toc else chapters, paragraphs)
    }

    // Chapters from the EPUB3 nav document or EPUB2 NCX, mapped to paragraph indices.
    private fun readToc(context: Context, zip: ZipFile, opf: Document, baseDir: String, anchors: Map<String, Int>, total: Int): List<Chapter> {
        val items = opf.getElementsByTag("item")
        val entries = mutableListOf<Pair<String, String>>()

        items.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { item ->
            val navPath = resolve(baseDir, item.attr("href"))
            val navDir = navPath.substringBeforeLast('/', "")
            val doc = zip.getEntry(navPath)?.let { e -> zip.getInputStream(e).use { Jsoup.parse(it, "UTF-8", "") } }
            val nav = doc?.select("nav")?.let { navs -> navs.firstOrNull { it.attr("epub:type") == "toc" } ?: navs.firstOrNull() }
            nav?.select("a[href]")?.forEach { entries += it.text().trim() to resolveHref(navDir, it.attr("href")) }
        }

        if (entries.isEmpty()) {
            val tocId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc")
            val ncx = items.firstOrNull { it.attr("id") == tocId } ?: items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            if (ncx != null) {
                val ncxPath = resolve(baseDir, ncx.attr("href"))
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                for (point in readXml(zip, ncxPath).getElementsByTag("navPoint")) {
                    val label = point.children().firstOrNull { it.normalName() == "navlabel" }?.text()?.trim().orEmpty()
                    val src = point.children().firstOrNull { it.normalName() == "content" }?.attr("src") ?: continue
                    entries += label to resolveHref(ncxDir, src)
                }
            }
        }

        val chapters = entries.mapNotNull { (label, target) ->
            val start = anchors[target] ?: anchors[target.substringBefore('#')] ?: return@mapNotNull null
            if (start >= total) null else Chapter(label.ifEmpty { context.getString(R.string.section) }, start)
        }.sortedBy { it.start }.distinctBy { it.start }
        if (chapters.isEmpty()) return chapters
        return if (chapters.first().start > 0) listOf(Chapter(context.getString(R.string.start), 0)) + chapters else chapters
    }

    private fun resolveHref(dir: String, href: String): String {
        val path = resolve(dir, href)
        val fragment = Uri.decode(href.substringAfter('#', ""))
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }

    private fun readXml(zip: ZipFile, path: String): Document {
        val entry = zip.getEntry(path) ?: error("Invalid EPUB: missing $path")
        return zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
    }

    private fun resolve(baseDir: String, href: String): String {
        val relative = Uri.decode(href.substringBefore('#'))
        val parts = ArrayDeque<String>()
        for (segment in "$baseDir/$relative".split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    // TTS engines limit input length, so long paragraphs are split on sentence boundaries.
    private fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+"))) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > MAX_CHUNK) {
                chunks += current.toString()
                current.clear()
            }
            if (sentence.length > MAX_CHUNK) {
                sentence.chunked(MAX_CHUNK).forEach { chunks += it }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
