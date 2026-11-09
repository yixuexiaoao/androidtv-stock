package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.update.ReleaseParser
import io.github.yixuexiaoao.tvstock.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private fun assets(version: String) = listOf("armeabi-v7a", "arm64-v8a", "universal").joinToString(",") {
        """{"name":"androidtv-stock-$version-$it-release.apk","browser_download_url":"https://x/$version/$it","size":100,"digest":"sha256:ABC"}"""
    }

    private fun release(version: String, pre: Boolean, draft: Boolean = false, assets: String = assets(version)) =
        """{"tag_name":"v$version","prerelease":$pre,"draft":$draft,"body":"notes $version","html_url":"https://x/$version","assets":[$assets]}"""

    @Test fun versionOrdering() {
        assertTrue(Versions.compare("0.2.0", "0.1.9") > 0)
        assertTrue(Versions.compare("0.10.0", "0.9.0") > 0)
        assertTrue(Versions.compare("0.2.0", "0.2.0-pre.99") > 0)
        assertTrue(Versions.compare("0.2.0-pre.10", "0.2.0-pre.9") > 0)
        assertTrue(Versions.compare("0.2.1-pre.1", "0.2.0") > 0)
        assertEquals(0, Versions.compare("v0.2.0", "0.2.0"))
        assertEquals(0, Versions.compare("0.2.0-pre.3", "0.2.0-pre.3"))
        assertTrue(Versions.compare("1.0.0-rc1", "1.0.0-pre.9") < 0)
        assertTrue(Versions.isPrerelease("0.2.0-pre.1"))
        assertFalse(Versions.isPrerelease("0.2.0"))
    }

    @Test fun picksAssetForDeviceAbi() {
        val json = "[${release("0.2.0", false)}]"
        assertEquals("https://x/0.2.0/arm64-v8a", ReleaseParser.parse(json, listOf("arm64-v8a", "armeabi-v7a")).single().apkUrl)
        assertEquals("https://x/0.2.0/armeabi-v7a", ReleaseParser.parse(json, listOf("armeabi-v7a", "armeabi")).single().apkUrl)
        assertEquals("https://x/0.2.0/universal", ReleaseParser.parse(json, listOf("x86_64")).single().apkUrl)
    }

    @Test fun parsesMetadataAndNormalizesDigest() {
        val r = ReleaseParser.parse("[${release("0.2.0-pre.5", true)}]", listOf("arm64-v8a")).single()
        assertEquals("0.2.0-pre.5", r.version)
        assertTrue(r.prerelease)
        assertEquals("notes 0.2.0-pre.5", r.notes)
        assertEquals(100L, r.apkSize)
        assertEquals("abc", r.sha256)
    }

    @Test fun missingDigestStillAccepted() {
        val assets = """{"name":"a-1.0.0-universal-release.apk","browser_download_url":"u","size":1}"""
        assertEquals("", ReleaseParser.parse("[${release("1.0.0", false, assets = assets)}]", listOf("x")).single().sha256)
    }

    @Test fun skipsDraftsAndReleasesWithoutApk() {
        val json = "[${release("9.0.0", false, draft = true)},${release("8.0.0", false, assets = "")},${release("1.0.0", false)}]"
        assertEquals(listOf("1.0.0"), ReleaseParser.parse(json, listOf("arm64-v8a")).map { it.version })
    }

    @Test fun channelFiltering() {
        val list = ReleaseParser.parse(
            "[${release("0.3.0-pre.7", true)},${release("0.2.0", false)},${release("0.2.0-pre.6", true)}]",
            listOf("arm64-v8a"),
        )
        assertEquals("0.3.0-pre.7", ReleaseParser.newest(list, true)!!.version)
        assertEquals("0.2.0", ReleaseParser.newest(list, false)!!.version)
        assertNull(ReleaseParser.newest(emptyList(), true))
    }
}
