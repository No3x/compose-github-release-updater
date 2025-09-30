# Compose GitHub Release Updater

Android helper for surfacing and installing GitHub releases straight from your Jetpack Compose app. The library polls your repository for a newer APK, downloads the asset with progress updates, and hands it off to the package installer - no Play Store required.

## Features
- Talks to the GitHub Releases API with optional personal-access token auth
- SemVer-aware version comparison with tolerant tagging ("v1.2" -> "1.2.0")
- AssetFilter helpers to pick the right release artifact (content type or suffix)
- Persists ignored versions and throttles checks to stay within rate limits
- Launches the platform package installer once the APK download completes

## Requirements
- Android minSdk 24 (Android 7.0)
- Kotlin 2.2.10 (multiplatform project default)
- GitHub repository that publishes installable APKs

## Installation
The project is wired for JitPack and Maven publishing. After the first tagged release you can depend on it with:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
   repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
   repositories {
      mavenCentral()
      maven { url = uri("https://jitpack.io") }
   }
}
```

```kotlin
// module build.gradle.kts
kotlin {
   sourceSets {
      androidMain.dependencies {
         implementation("com.github.No3x.compose-github-release-updater:compose-github-release-updater:1.0.0")
      }
   }
}
```

If you publish a different version (for example a Git SHA on JitPack), update the coordinate accordingly. During development you can also run `./gradlew publishToMavenLocal` and point your consuming project at the local maven repository.

## Quickstart
1. **Expose your GitHub token** (optional for public repos, recommended to avoid rate limits). The sample app reads either the `GITHUB_RELEASES_TOKEN` environment variable or a `github.releases.token` Gradle property.
2. **Request permissions** in your manifest:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

   <application>
       <provider
           android:name="androidx.core.content.FileProvider"
           android:authorities="${applicationId}.fileprovider"
           android:exported="false"
           android:grantUriPermissions="true">
           <meta-data
               android:name="android.support.FILE_PROVIDER_PATHS"
               android:resource="@xml/filepaths" />
       </provider>
   </application>
   ```
3. **Create a `FileProvider` path** under `src/main/res/xml/filepaths.xml` (copy the one from the `sample` module if you do not have special requirements).
4. **Drive the updater from Compose**:
   ```kotlin
   @Composable
   fun UpdateChecker() {
       val context = LocalContext.current
       val scope = rememberCoroutineScope()
       val updater = remember {
           GithubAutoUpdater(
               context = context,
               owner = BuildConfig.GITHUB_REPO_OWNER,
               repo = BuildConfig.GITHUB_REPO_NAME,
               token = BuildConfig.GITHUB_RELEASES_TOKEN.takeIf { it.isNotBlank() },
               enabled = !BuildConfig.DEBUG
           )
       }

       var pendingRelease by remember { mutableStateOf<GithubRelease?>(null) }
       var downloadProgress by remember { mutableStateOf<Float?>(null) }
       var error by remember { mutableStateOf<Throwable?>(null) }

       LaunchedEffect(updater) {
           when (val update = updater.checkForUpdate()) {
               is UpdateCheckResult.UpdateAvailable -> {
                   error = null
                   pendingRelease = update.release
               }
               is UpdateCheckResult.NoUpdate -> {
                   pendingRelease = null
               }
               is UpdateCheckResult.Skipped -> Unit
               is UpdateCheckResult.Failed -> {
                   pendingRelease = null
                   error = update.error
               }
           }
       }

       pendingRelease?.let { release ->
           AlertDialog(
               onDismissRequest = {
                   updater.deferRelease(release)
                   pendingRelease = null
               },
               confirmButton = {
                   TextButton(onClick = {
                       scope.launch {
                           error = null
                           updater.downloadAndInstall(release) { progress ->
                               downloadProgress = progress
                           }.onFailure { throwable ->
                               error = throwable
                           }
                       }
                   }) {
                       Text("Update")
                   }
               },
               dismissButton = {
                   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                       TextButton(onClick = {
                           updater.createInstallPermissionIntent()?.let { context.startActivity(it) }
                       }) {
                           Text("Open settings")
                       }
                       TextButton(onClick = {
                           updater.deferRelease(release)
                           pendingRelease = null
                       }) {
                           Text("Later")
                       }
                   }
               },
               title = { Text("Update available") },
               text = {
                   Column {
                       Text("${release.name.ifBlank { release.tagName }} is ready to install")
                       release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                           Spacer(Modifier.height(12.dp))
                           Text(notes)
                       }
                       downloadProgress?.let { progress ->
                           Spacer(Modifier.height(12.dp))
                           if (progress.isFinite()) {
                               LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
                           } else {
                               LinearProgressIndicator()
                           }
                       }
                       error?.let { throwable ->
                           Spacer(Modifier.height(12.dp))
                           Text("Download failed: ${throwable.message ?: "unknown error"}")
                       }
                   }
               }
           )
       }
   }
   ```
   Use the `enabled` flag to skip update prompts in debug builds (as shown above).
   `checkForUpdate()` returns an `UpdateCheckResult` and respects the throttling interval by default, so reserve `force = true` for an explicit "Check for updates" action.
   Handle `UpdateCheckResult.Failed` if you want to surface richer UI; the helper also exposes a small `Result` based API so you can surface errors or ask the user to grant "install unknown apps" permission when needed.

A fuller Compose implementation is available in `sample/src/androidMain/`.

## Customisation
- **Build variants** - Pass `enabled = !BuildConfig.DEBUG` (or similar) to silence update checks during local development.
- **Asset selection** - Combine `AssetFilter` helpers to target a specific suffix or MIME type.
- **Version comparison** - Supply your own `VersionComparator` if you publish non-SemVer tags.
- **Network throttling** - Adjust `checkInterval` to control how often the GitHub API is queried.
- **User agent** - Provide a custom `userAgent` string if you want to distinguish update traffic in analytics.

See `docs/usage.md` for detailed scenarios and advanced patterns.

## Troubleshooting
- `MissingInstallPermissionException` signals that the app is not allowed to install packages. Prompt the user with `updater.createInstallPermissionIntent()`.
- GitHub returns HTTP 404 or 403 when the release cannot be fetched; check the repository name, visibility, and token scopes.
- The download progress callback may receive `null` when GitHub does not expose the content length. Treat this as an indeterminate state in your UI.

## Contributing
Improvements and bug fixes are very welcome. Feel free to open an issue or a PR.





