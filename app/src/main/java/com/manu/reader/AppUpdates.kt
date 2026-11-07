package com.manu.reader

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AppUpdate(val version: String, val apkUrl: String)

object AppUpdates {
    private const val REPO = "https://github.com/manoantares2/CuevaReader/releases/download/"
    private val versionPattern = Regex("^(QA-)?v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(-pre)?$")

    private fun version(value: String): List<Int>? {
        val match = versionPattern.matchEntire(value) ?: return null
        return listOf(match.groupValues[2].toIntOrNull() ?: return null,
            match.groupValues[3].toIntOrNull() ?: return null,
            match.groupValues[4].ifEmpty { "0" }.toIntOrNull() ?: return null,
            if (match.groupValues[5].isEmpty()) 1 else 0)
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = version(candidate) ?: return false
        val b = version(current) ?: return false
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    internal fun select(json: String, current: String): AppUpdate? {
        val releases = JSONArray(json)
        val qa = current.startsWith("QA-") || current.endsWith("-pre")
        var best: AppUpdate? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            val tag = release.optString("tag_name")
            if (release.optBoolean("draft") || (!qa && (release.optBoolean("prerelease") || tag.startsWith("QA-") || tag.endsWith("-pre")))) continue
            if (!isNewer(tag, best?.version ?: current)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val url = asset.optString("browser_download_url")
                val uri = runCatching { URI(url) }.getOrNull() ?: continue
                if (asset.optString("state") != "uploaded" || !asset.optString("name").endsWith(".apk", true)) continue
                if (!url.startsWith(REPO) || uri.host != "github.com" || uri.scheme != "https" || uri.rawQuery != null || uri.fragment != null || uri.path.contains("/../")) continue
                if (!uri.path.substringAfter("/releases/download/").startsWith("$tag/")) continue
                best = AppUpdate(tag, url)
                break
            }
        }
        return best
    }

    fun fetch(current: String): AppUpdate? {
        val connection = URL("https://api.github.com/repos/manoantares2/CuevaReader/releases?per_page=50").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "CuevaReader/$current")
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }
            return select(bytes.toString(Charsets.UTF_8), current)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
    }
}