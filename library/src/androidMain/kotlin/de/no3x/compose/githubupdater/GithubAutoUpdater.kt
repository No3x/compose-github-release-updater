package de.no3x.compose.githubupdater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import de.no3x.compose.githubupdater.AssetFilter.Companion.default
import io.github.z4kn4fein.semver.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private const val API_BASE = "https://api.github.com/repos"
private const val PREFS_NAME = "github_auto_updater"
private const val KEY_LAST_CHECK = "last_check"
private const val KEY_IGNORED_VERSION = "ignored_version"

/** Information about a GitHub release that can be installed. */
data class GithubRelease(
    val tagName: String,
    val name: String,
    val notes: String?,
    val htmlUrl: String,
    val assetDownloadUrl: String,
) {
    val versionName: String = tagName.trimStart('v', 'V')
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: GithubRelease) : UpdateCheckResult

    sealed interface NoUpdate : UpdateCheckResult {
        data object UpToDate : NoUpdate
        data object NoCompatibleRelease : NoUpdate
        data object Disabled : NoUpdate
    }

    sealed interface Skipped : UpdateCheckResult {
        data object Throttled : Skipped
        data class Deferred(val versionName: String) : Skipped
    }

    data class Failed(val error: Throwable) : UpdateCheckResult
}

/**
 * Handles checking and downloading application updates published on GitHub.
 */
class GithubAutoUpdater(
    private val context: Context,
    private val owner: String,
    private val repo: String,
    private val token: String? = null,
    private val userAgent: String = "${context.packageName}-updater",
    private val checkInterval: Duration = 6.hours,
    private val assetFilter: AssetFilter = default(),
    private val versionComparator: VersionComparator = VersionComparator.default(),
    private val enabled: Boolean = true
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks the GitHub releases feed for a version newer than [currentVersion].
     */
    suspend fun checkForUpdate(
        force: Boolean = false,
        currentVersion: String = installedVersion(),
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!enabled) {
            return@withContext UpdateCheckResult.NoUpdate.Disabled
        }
        if (!force && shouldSkipCheck()) {
            return@withContext UpdateCheckResult.Skipped.Throttled
        }

        val now = System.currentTimeMillis()
        val ignoredVersion = prefs.getString(KEY_IGNORED_VERSION, null)
        val state = runCatching { fetchLatestRelease() }
            .fold(
                onSuccess = { release ->
                    when {
                        release == null -> UpdateCheckResult.NoUpdate.NoCompatibleRelease
                        !versionComparator.isNewerVersion(currentVersion, release.versionName) -> UpdateCheckResult.NoUpdate.UpToDate
                        !force && release.versionName == ignoredVersion -> UpdateCheckResult.Skipped.Deferred(release.versionName)
                        else -> UpdateCheckResult.UpdateAvailable(release)
                    }
                },
                onFailure = { error ->
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Update check failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                    UpdateCheckResult.Failed(error)
                }
            )

        prefs.edit { putLong(KEY_LAST_CHECK, now) }
        state
    }

    /**
     * Downloads the release asset and triggers the Android package installer.
     * [onProgress] receives a value from 0.0 to 1.0 when the download size is known, or null when
     * the progress is indeterminate.
     */
    suspend fun downloadAndInstall(
        release: GithubRelease,
        onProgress: (Float?) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!enabled) {
            return@withContext Result.failure(IllegalStateException("GithubAutoUpdater is disabled"))
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) throw MissingInstallPermissionException()

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val apkFile = File(downloadDir, "$repo-${release.versionName}.apk")

            openGet(
                url = release.assetDownloadUrl,
                accept = "application/octet-stream"
            ).useChecked { conn ->
                val contentLength = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyToWithProgress(output, contentLength, onProgress)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val authority = "${context.packageName}.fileprovider"
                val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val packageManager = context.packageManager
                if (installIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(installIntent)
                    onProgress(1f)
                } else {
                    throw ActivityNotFoundException("No installer available")
                }
            }
        }
    }

    fun deferRelease(release: GithubRelease) {
        prefs.edit { putString(KEY_IGNORED_VERSION, release.versionName) }
    }

    fun clearDeferredRelease() {
        prefs.edit { remove(KEY_IGNORED_VERSION) }
    }

    fun createInstallPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null

    private fun installedVersion(): String = context.packageManager.versionNameOf(context.packageName)

    private fun shouldSkipCheck(): Boolean =
        (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)) < checkInterval.inWholeMilliseconds

    private fun fetchLatestRelease(): GithubRelease? =
        openGet(
            url = "$API_BASE/$owner/$repo/releases/latest",
            accept = "application/vnd.github+json"
        ).useChecked { conn ->
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseRelease(JSONObject(response))
        }

    private fun parseRelease(json: JSONObject): GithubRelease? {
        val tagName = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = json.optJSONArray("assets") ?: return null

        val downloadUrl = (0 until assets.length())
            .asSequence()
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { asset ->
                val contentType = asset.optString("content_type").takeIf { it.isNotBlank() }
                val name = asset.optString("name")
                assetFilter.matches(contentType, name)
            }
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return GithubRelease(
            tagName = tagName,
            name = json.optString("name"),
            notes = json.optString("body"),
            htmlUrl = json.optString("html_url"),
            assetDownloadUrl = downloadUrl
        )
    }

    private fun openGet(
        url: String,
        accept: String
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = if (accept == "application/octet-stream") 30_000 else 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return conn
    }

    /** Ensures HTTP 200 and auto-disconnects. */
    private inline fun <T> HttpURLConnection.useChecked(block: (HttpURLConnection) -> T): T =
        try {
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode")
            }
            block(this)
        } finally {
            disconnect()
        }

    /** Copy input → output with progress callback (null once if content length unknown). */
    private fun java.io.InputStream.copyToWithProgress(
        out: java.io.OutputStream,
        contentLength: Long,
        onProgress: (Float?) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloaded = 0L
        var reportedIndeterminate = false
        while (true) {
            val read = this.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            downloaded += read
            if (contentLength > 0) {
                onProgress(downloaded.toFloat() / contentLength)
            } else if (!reportedIndeterminate) {
                onProgress(null)
                reportedIndeterminate = true
            }
        }
    }

    /** PackageManager helper to avoid SDK checks duplication. */
    private fun PackageManager.versionNameOf(pkg: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName ?: "0"
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(pkg, 0).versionName ?: "0"
        }

    class MissingInstallPermissionException : Exception()
}

fun interface VersionComparator {
    fun isNewerVersion(current: String, candidate: String): Boolean

    companion object {

        fun default(): VersionComparator {
            return VersionComparator.semver()
        }
    }
}

fun VersionComparator.Companion.semver(): VersionComparator =
    VersionComparator { current, candidate ->
        Version.parse(candidate.normalizeSemver()) > Version.parse(current.normalizeSemver())
    }

private fun String.normalizeSemver(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return trimmed

    val metadataIndex = trimmed.indexOf('+')
    val metadata = if (metadataIndex >= 0) trimmed.substring(metadataIndex) else ""
    val withoutMetadata = if (metadataIndex >= 0) trimmed.substring(0, metadataIndex) else trimmed

    val prereleaseIndex = withoutMetadata.indexOf('-')
    val prerelease = if (prereleaseIndex >= 0) withoutMetadata.substring(prereleaseIndex) else ""
    val core = if (prereleaseIndex >= 0) withoutMetadata.substring(0, prereleaseIndex) else withoutMetadata

    val parts = core.split('.')
    val normalizedCore = when {
        parts.any { it.isEmpty() } -> core
        parts.size == 1 -> "${parts[0]}.0.0"
        parts.size == 2 -> "${parts[0]}.${parts[1]}.0"
        else -> core
    }

    return normalizedCore + prerelease + metadata
}

class AssetFilter private constructor(
    private val matcher: (contentType: String?, name: String) -> Boolean
) {
    fun matches(contentType: String?, name: String): Boolean = matcher(contentType, name)

    infix fun or(other: AssetFilter): AssetFilter =
        AssetFilter { contentType, name -> this.matches(contentType, name) || other.matches(contentType, name) }

    infix fun and(other: AssetFilter): AssetFilter =
        AssetFilter { contentType, name -> this.matches(contentType, name) && other.matches(contentType, name) }

    companion object {
        fun byContentType(type: String): AssetFilter =
            AssetFilter { contentType, _ -> contentType == type }

        fun bySuffix(suffix: String): AssetFilter =
            AssetFilter { _, name -> name.endsWith(suffix, ignoreCase = true) }

        fun default(): AssetFilter =
            byContentType("application/vnd.android.package-archive") or bySuffix(".apk")
    }
}
