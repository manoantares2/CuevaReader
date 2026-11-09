package com.manu.reader

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUpdatesTest {
    private fun release(tag: String, prerelease: Boolean = true, draft: Boolean = false,
                        url: String = "https://github.com/manoantares2/CuevaReader/releases/download/$tag/app.apk") =
        """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"assets":[{"name":"app.apk","state":"uploaded","browser_download_url":"$url"}]}"""

    @Test fun comparesNumericVersionsNotAlphabeticalOrder() {
        assertTrue(AppUpdates.isNewer("QA-v1.10-pre", "QA-v1.9-pre"))
        assertFalse(AppUpdates.isNewer("QA-v1.6-pre", "QA-v1.6-pre"))
        assertFalse(AppUpdates.isNewer("qa", "QA-v1.6-pre"))
        assertTrue(AppUpdates.isNewer("v1.6", "QA-v1.6-pre"))
    }

    @Test fun selectsNewestQaReleaseIgnoringDrafts() {
        val json = "[${release("QA-v1.7-pre")},${release("QA-v1.10-pre")},${release("QA-v9.0-pre", draft = true)}]"
        assertEquals("QA-v1.10-pre", AppUpdates.select(json, "QA-v1.6-pre")?.version)
        assertNull(AppUpdates.select(json, "1.6"))
    }

    @Test fun rejectsForeignDownloadLinksAndOlderVersions() {
        assertNull(AppUpdates.select("[${release("QA-v1.7-pre", url = "https://example.com/app.apk")}]", "QA-v1.6-pre"))
        assertNull(AppUpdates.select("[${release("QA-v1.5-pre")}]", "QA-v1.6-pre"))
        assertNull(AppUpdates.select("[${release("QA-v1.7-pre", url = "https://github.com/other/repo/releases/download/QA-v1.7-pre/app.apk")}]", "QA-v1.6-pre"))
    }

    @Test fun stableClientsAcceptStableRelease() {
        assertEquals("v2.0", AppUpdates.select("[${release("v2.0", prerelease = false)}]", "1.6")?.version)
    }
}