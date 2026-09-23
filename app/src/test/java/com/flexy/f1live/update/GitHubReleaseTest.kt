package com.flexy.f1live.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseTest {

    private val json = """
        {
          "url": "https://api.github.com/repos/Spottq/Laply/releases/1",
          "html_url": "https://github.com/Spottq/Laply/releases/tag/v1.1",
          "id": 1,
          "tag_name": "v1.1",
          "name": "Laply 1.1",
          "draft": false,
          "prerelease": false,
          "author": { "login": "Spottq" },
          "assets": [
            { "name": "checksums.txt", "browser_download_url": "https://example.invalid/checksums.txt" },
            { "name": "Laply-1.1.apk", "browser_download_url": "https://github.com/Spottq/Laply/releases/download/v1.1/Laply-1.1.apk" }
          ],
          "body": "## What's new\r\n\r\n- **Faster** live timing\r\n- Bug fixes"
        }
    """.trimIndent()

    @Test
    fun parsesTheFieldsTheCheckerUses() {
        val release = GitHubRelease.parse(json)
        assertEquals("v1.1", release.tagName)
        assertEquals("1.1", release.version)
        assertEquals("https://github.com/Spottq/Laply/releases/tag/v1.1", release.htmlUrl)
        assertEquals(
            "https://github.com/Spottq/Laply/releases/download/v1.1/Laply-1.1.apk",
            release.apkUrl,
        )
        assertEquals("What's new", release.summary)
    }

    @Test
    fun summaryStripsMarkdownAndSkipsBlankLines() {
        val release = GitHubRelease.parse(
            """{"tag_name":"v2.0","html_url":"u","body":"\n\n- **Faster** live timing\n- more"}""",
        )
        assertEquals("Faster live timing", release.summary)
    }

    @Test
    fun missingBodyAndAssetsAreTolerated() {
        val release = GitHubRelease.parse("""{"tag_name":"v1.0","html_url":"u","body":null,"name":null}""")
        assertNull(release.summary)
        assertNull(release.apkUrl)
    }

    @Test
    fun longSummaryIsEllipsized() {
        val long = "x".repeat(300)
        val release = GitHubRelease.parse("""{"tag_name":"v1.0","html_url":"u","body":"$long"}""")
        assertEquals(120, release.summary!!.length)
    }
}
