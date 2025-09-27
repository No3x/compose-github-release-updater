package de.no3x.compose.githubreleaseupdater.sample

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.no3x.compose.githubupdater.GithubAutoUpdater
import de.no3x.compose.githubupdater.GithubRelease
import de.no3x.compose.githubupdater.VersionComparator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun App() {
    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    val updater = remember(context.applicationContext) {
        GithubAutoUpdater(
            context = context.applicationContext,
            owner = BuildConfig.GITHUB_REPO_OWNER,
            repo = BuildConfig.GITHUB_REPO_NAME,
            token = BuildConfig.GITHUB_RELEASES_TOKEN.takeIf { it.isNotBlank() }
        )
    }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.None) }

    LaunchedEffect(updater) {
        val release = updater.checkForUpdate()
        updateState = if (release != null) {
            UpdateUiState.Available(release)
        } else {
            UpdateUiState.NotAvailable
        }
    }

    fun launchDownload(release: GithubRelease) {
        updateScope.launch {
            updater.clearDeferredRelease()
            updateState = UpdateUiState.Downloading(release, null)
            val result = updater.downloadAndInstall(release) { progress ->
                updateState = UpdateUiState.Downloading(release, progress)
            }
            if (result.isSuccess) {
                Toast.makeText(
                    context,
                    context.getString(R.string.update_install_started),
                    Toast.LENGTH_LONG
                ).show()
                updateState = UpdateUiState.None
            } else {
                val exception = result.exceptionOrNull()
                when (exception) {
                    is GithubAutoUpdater.MissingInstallPermissionException -> {
                        val intent = updater.createInstallPermissionIntent()
                        updateState = if (intent != null) {
                            UpdateUiState.PermissionRequired(release, intent)
                        } else {
                            UpdateUiState.Error(
                                release,
                                context.getString(R.string.update_error_generic)
                            )
                        }
                    }

                    else -> {
                        val message = exception?.localizedMessage
                            ?: context.getString(R.string.update_error_generic)
                        updateState = UpdateUiState.Error(release, message)
                    }
                }
            }
        }
    }

    when (val state = updateState) {
        is UpdateUiState.NotAvailable -> {
            // you don't need this state in production.
            Text("No update available")
        }
        is UpdateUiState.Available -> {
            val release = state.release
            AlertDialog(
                onDismissRequest = {
                    updater.deferRelease(release)
                    updateState = UpdateUiState.None
                },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.update_available_message,
                                release.versionName,
                                BuildConfig.VERSION_NAME
                            )
                        )
                        release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.update_release_notes_heading),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(notes)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { launchDownload(release) }) {
                        Text(stringResource(R.string.update_now))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val viewIntent = Intent(
                                Intent.ACTION_VIEW,
                                release.htmlUrl.toUri()
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(viewIntent) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.update_view_release_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }) {
                            Text(stringResource(R.string.update_view_release))
                        }
                        TextButton(onClick = {
                            updater.deferRelease(release)
                            updateState = UpdateUiState.None
                        }) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
            )
        }

        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val progress = state.progress
                        if (progress != null && progress.isFinite()) {
                            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
                            Spacer(Modifier.height(8.dp))
                            Text("${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%")
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }

        is UpdateUiState.PermissionRequired -> {
            val release = state.release
            AlertDialog(
                onDismissRequest = {
                    updater.deferRelease(release)
                    updateState = UpdateUiState.None
                },
                title = { Text(stringResource(R.string.update_permission_title)) },
                text = { Text(stringResource(R.string.update_permission_message)) },
                confirmButton = {
                    TextButton(onClick = { runCatching { context.startActivity(state.intent) } }) {
                        Text(stringResource(R.string.update_permission_open_settings))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { launchDownload(release) }) {
                            Text(stringResource(R.string.update_retry))
                        }
                        TextButton(onClick = {
                            updater.deferRelease(release)
                            updateState = UpdateUiState.None
                        }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = { updateState = UpdateUiState.None },
                title = { Text(stringResource(R.string.update_error_title)) },
                text = {
                    Text(stringResource(R.string.update_error_message, state.message))
                },
                confirmButton = {
                    state.release?.let { release ->
                        TextButton(onClick = { launchDownload(release) }) {
                            Text(stringResource(R.string.update_retry))
                        }
                    } ?: TextButton(onClick = { updateState = UpdateUiState.None }) {
                        Text(stringResource(id = android.R.string.ok))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.release?.let { release ->
                            TextButton(onClick = {
                                val viewIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    release.htmlUrl.toUri()
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(viewIntent) }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.update_view_release_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }) {
                                Text(stringResource(R.string.update_view_release))
                            }
                        }
                        TextButton(onClick = { updateState = UpdateUiState.None }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            )
        }

        UpdateUiState.None -> Unit
    }

}

private sealed interface UpdateUiState {
    data object None : UpdateUiState
    data object NotAvailable : UpdateUiState
    data class Available(val release: GithubRelease) : UpdateUiState
    data class Downloading(val release: GithubRelease, val progress: Float?) : UpdateUiState
    data class PermissionRequired(val release: GithubRelease, val intent: Intent) : UpdateUiState
    data class Error(val release: GithubRelease?, val message: String) : UpdateUiState
}

