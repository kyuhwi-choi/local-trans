package dev.localtrans.app

import dev.localtrans.app.net.Http
import dev.localtrans.app.update.ApkDownloader
import dev.localtrans.app.update.UpdateManifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private val sample = """
        {"versionCode": 7, "versionName": "0.1.7",
         "apkUrl": "https://example.com/app.apk",
         "sha256": "ABC", "sizeBytes": 123, "futureField": true}
    """.trimIndent()

    @Test
    fun parsesManifestAndIgnoresUnknownKeys() {
        val m = UpdateManifest.parse(sample)
        assertEquals(7, m.versionCode)
        assertEquals("0.1.7", m.versionName)
        assertEquals("https://example.com/app.apk", m.apkUrl)
        assertEquals(123L, m.sizeBytes)
        assertEquals("", m.notes)
    }

    @Test
    fun onlyHigherVersionCodeIsAnUpdate() {
        val m = UpdateManifest.parse(sample)
        assertTrue(m.isNewerThan(6))
        assertFalse(m.isNewerThan(7))
        assertFalse(m.isNewerThan(8))
    }

    @Test
    fun copyComputesSha256OfContent() {
        val dest = File.createTempFile("apk", ".bin")
        try {
            val sha = ApkDownloader.copyWithDigest("abc".byteInputStream(), dest)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
            assertEquals("abc", dest.readText())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun cleartextOnlyWhenExplicitlyAllowed() {
        assertTrue(Http.isAllowed("https://github.com/x", allowCleartext = false))
        assertFalse(Http.isAllowed("http://10.0.2.2:8000/latest.json", allowCleartext = false))
        assertTrue(Http.isAllowed("http://10.0.2.2:8000/latest.json", allowCleartext = true))
        assertFalse(Http.isAllowed("file:///sdcard/x.apk", allowCleartext = true))
    }
}
