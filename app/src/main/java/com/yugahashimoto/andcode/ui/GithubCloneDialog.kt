package com.yugahashimoto.andcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.settings.GitHubRepo
import com.yugahashimoto.andcode.runtime.local.GitCloneResult
import kotlinx.coroutines.launch

private enum class CloneSource { REPOS, URL }

@Composable
internal fun GithubCloneDialog(
    githubConfigured: Boolean,
    onClone: suspend (String) -> GitCloneResult,
    onListRepos: suspend () -> List<GitHubRepo>,
    onCloned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by rememberSaveable { mutableStateOf(if (githubConfigured) CloneSource.REPOS else CloneSource.URL) }
    var url by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoadingRepos by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var isCloning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cloneFailedMessage = stringResource(R.string.workspace_clone_failed)

    LaunchedEffect(githubConfigured) {
        if (githubConfigured) {
            isLoadingRepos = true
            repos = onListRepos()
            isLoadingRepos = false
        }
    }

    fun startClone(cloneUrl: String) {
        isCloning = true
        error = null
        scope.launch {
            val result = onClone(cloneUrl)
            if (result.exitCode == 0) {
                onCloned(result.serverPath)
                onDismiss()
            } else {
                error = result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: cloneFailedMessage.format(result.exitCode)
                isCloning = false
            }
        }
    }

    val filteredRepos =
        remember(repos, search) {
            if (search.isBlank()) {
                repos
            } else {
                repos.filter {
                    it.fullName.contains(search, ignoreCase = true)
                }
            }
        }

    AlertDialog(
        onDismissRequest = { if (!isCloning) onDismiss() },
        title = { Text(stringResource(R.string.workspace_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!githubConfigured) {
                    Text(
                        text = stringResource(R.string.workspace_clone_requires_auth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = source == CloneSource.REPOS,
                            onClick = { source = CloneSource.REPOS },
                            label = { Text(stringResource(R.string.workspace_clone_my_repos)) },
                        )
                        FilterChip(
                            selected = source == CloneSource.URL,
                            onClick = { source = CloneSource.URL },
                            label = { Text(stringResource(R.string.workspace_clone_url_tab)) },
                        )
                    }
                }

                if (source == CloneSource.REPOS && githubConfigured) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text(stringResource(R.string.workspace_clone_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isLoadingRepos) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.workspace_clone_loading_repos), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                        ) {
                            items(filteredRepos, key = { it.fullName }) { repo ->
                                RepoRow(
                                    repo = repo,
                                    enabled = !isCloning,
                                    onClick = { startClone(repo.cloneUrl) },
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.workspace_clone_url_label)) },
                        placeholder = { Text(stringResource(R.string.workspace_clone_url_placeholder)) },
                        singleLine = true,
                        enabled = !isCloning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isCloning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.workspace_clone_running),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (source == CloneSource.URL || !githubConfigured) {
                Button(
                    enabled = url.isNotBlank() && !isCloning,
                    onClick = { startClone(url.trim()) },
                ) {
                    Text(stringResource(R.string.workspace_clone_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCloning) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RepoRow(
    repo: GitHubRepo,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Folder,
                contentDescription = stringResource(R.string.cd_repository),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
