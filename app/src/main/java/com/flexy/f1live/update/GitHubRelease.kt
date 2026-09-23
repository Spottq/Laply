package com.flexy.f1live.update

import com.flexy.f1live.data.LenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The fields of GitHub's `GET /repos/{owner}/{repo}/releases/latest` the update check reads. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    /** The version this release ships, without the tag's `v` prefix ("v1.1" -> "1.1"). */
    val version: String get() = AppVersion.display(tagName)

    /** Direct download of the release's APK, when one is attached. */
    val apkUrl: String?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.downloadUrl

    /**
     * One line for the notification: the first line of the release notes with Markdown heading,
     * list and emphasis markers stripped, or null when the notes are empty.
     */
    val summary: String?
        get() = body.orEmpty().lineSequence()
            .map { line -> line.trim().trimStart('#', '-', '*', '>', ' ').replace("**", "").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > MAX_SUMMARY) it.take(MAX_SUMMARY - 1).trimEnd() + "…" else it }

    companion object {
        private const val MAX_SUMMARY = 120

        fun parse(json: String): GitHubRelease = LenientJson.decodeFromString(serializer(), json)
    }
}
