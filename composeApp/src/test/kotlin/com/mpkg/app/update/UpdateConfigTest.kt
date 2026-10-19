package com.mpkg.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateConfigTest {
    @Test fun publisherManifestControlsDownloadWithBuiltInFallback() {
        assertEquals("https://publisher.example/release.exe",
            UpdateConfig.downloadUrl(UpdateInfo("2.0.0", "https://publisher.example/release.exe")))
        assertEquals(UpdateConfig.FALLBACK_DOWNLOAD_URL, UpdateConfig.downloadUrl(UpdateInfo("2.0.0")))
        assertEquals(UpdateConfig.FALLBACK_DOWNLOAD_URL, UpdateConfig.downloadUrl(UpdateInfo("2.0.0", " ")))
    }
}
