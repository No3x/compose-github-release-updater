# Usage Guide

This document expands on the README with practical integration notes.

## Configure GitHub access
1. Generate a fine grained personal access token in GitHub with at least the `contents:read` scope for the repository that hosts your releases. Public repositories can skip this but you will hit the unauthenticated rate limit quickly.
2. Store the token outside version control. The sample project reads `GITHUB_RELEASES_TOKEN` from the environment and falls back to a Gradle property.
   ```properties
   # local.properties (never commit)
   github.releases.token=ghp_your_token_here
   ```
3. Expose the token to your app at build time. One option is to mirror the sample module:
   ```kotlin
   defaultConfig {
       buildConfigField("String", "GITHUB_REPO_OWNER", "\"No3x\"")
       buildConfigField("String", "GITHUB_REPO_NAME", "\"compose-github-release-updater\"")
       val githubToken = providers.environmentVariable("GITHUB_RELEASES_TOKEN").orNull
           ?: providers.gradleProperty("github.releases.token").orNull
           ?: error("Nor GITHUB_RELEASES_TOKEN or github.releases.token are set")
       val escapedGithubToken = githubToken
           .replace("\\", "\\\\")
           .replace("\"", "\\\"")
       buildConfigField("String", "GITHUB_RELEASES_TOKEN", "\"$escapedGithubToken\"")
   }
   ```
   Wrap the token in `takeIf { it.isNotBlank() }` when instantiating the updater so public repositories keep working without credentials.

## Manifest configuration
Add the required permissions and a `FileProvider`. The provider definition must live in your manifest and the corresponding `filepaths.xml`.
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/filepaths" />
</provider>
```

`res/xml/filepaths.xml` can be kept simple:
```xml
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-files-path name="external_downloads" path="." />
    <files-path name="internal_downloads" path="." />
</paths>
```

## Working with `GithubAutoUpdater`
Constructor parameters:
- `context` - application or activity context used to access storage, toasts, and the package manager.
- `owner` and `repo` - GitHub coordinates.
- `token` - optional bearer token string.
- `userAgent` - defaults to `<package>.updater` and can be overridden.
- `enabled` - gate the updater behind a build flag; set it to `false` for debug variants to disable update prompts.
- `checkInterval` - minimum duration between non forced checks. Default is six hours.
- `assetFilter` - matches the asset to download. Defaults to APK MIME type or `.apk`.
- `versionComparator` - decides whether a release is newer. Defaults to SemVer comparison with tolerant tags.

Public API:
- `suspend fun checkForUpdate(force: Boolean = false, currentVersion: String = installedVersion())`: returns a `GithubRelease?`.
- `suspend fun downloadAndInstall(release, onProgress)`: downloads the asset and returns `Result<Unit>`.
- `fun deferRelease(release)` and `fun clearDeferredRelease()`: skip the given version until the next forced check.
- `fun createInstallPermissionIntent()`: returns an `Intent` that opens the system settings on Android O+ when the user must allow installing unknown apps.

`checkForUpdate()` honours `checkInterval` by default. Pass `force = true` only when the user explicitly requests an immediate refresh (for example from a settings button).

The helper posts a toast on network failures during `checkForUpdate`. You can surface richer UI by inspecting the returned `Result` from `downloadAndInstall`:
```kotlin
scope.launch {
    updater.downloadAndInstall(release) { progress ->
        uiState = uiState.copy(progress = progress)
    }.onFailure { throwable ->
        when (throwable) {
            is GithubAutoUpdater.MissingInstallPermissionException -> {
                permissionIntent = updater.createInstallPermissionIntent()
            }
            else -> uiState = uiState.copy(errorMessage = throwable.message ?: "Unknown error")
        }
    }
}
```


## Disable in debug builds
Pass `enabled = !BuildConfig.DEBUG` (or a similar build flag) when constructing the updater to keep debug builds silent. When disabled, `checkForUpdate()` returns `null` and `downloadAndInstall` yields a failed `Result`.

## Custom asset matching
Combine the supplied filters to target a specific file name:
```kotlin
val arm64Apk = AssetFilter.bySuffix("-arm64-v8a.apk")
val universalApk = AssetFilter.bySuffix("-universal.apk")
val updater = GithubAutoUpdater(
    context = context,
    owner = "...",
    repo = "...",
    assetFilter = arm64Apk or universalApk
)
```
You can also chain filters with `and` to ensure the MIME type matches.

## Alternative version strategies
If you publish calendar version tags such as `2024.10`, provide a custom comparator:
```kotlin
val comparator = VersionComparator { current, candidate ->
    candidate.replace(".", "").toInt() > current.replace(".", "").toInt()
}
val updater = GithubAutoUpdater(
    context = context,
    owner = "...",
    repo = "...",
    versionComparator = comparator
)
```

## Deferring updates
Call `deferRelease` when the user taps "Later". The updater writes the version name to shared preferences and skips it on the next non forced `checkForUpdate`. Call `clearDeferredRelease` to purge that override (for example after a successful installation or when the user revisits a settings screen).

## Scheduling background checks
`checkForUpdate()` already respects `checkInterval`. To keep the UI snappy, schedule a background worker that runs daily:
```kotlin
class GitHubUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val updater = GithubAutoUpdater(
            context = applicationContext,
            owner = BuildConfig.GITHUB_REPO_OWNER,
            repo = BuildConfig.GITHUB_REPO_NAME,
            token = BuildConfig.GITHUB_RELEASES_TOKEN.takeIf { it.isNotBlank() }
        )
        val release = updater.checkForUpdate() ?: return Result.success()
        // Notify the user via NotificationManager, store the release info, etc.
        return Result.success()
    }
}
```
Trigger the worker from your app entry point or a periodic `WorkManager` request.

## Testing tips
- Override `checkInterval` with a short duration (for example `30.minutes`) in debug builds.
- Use `force = true` to bypass throttling when you expose a "Check for updates" button.
- Host a fake release JSON on a local server during instrumentation tests and point the library at it by injecting a different base URL via dependency injection if needed.

