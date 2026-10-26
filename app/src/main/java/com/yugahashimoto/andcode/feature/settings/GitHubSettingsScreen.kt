package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

/** Creates a classic token with the scopes the app's Git and PR features use. */
const val GITHUB_NEW_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=AndCode"

@Composable
fun GitHubSettingsScreen(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenVerification: (String) -> Unit,
    onBack: () -> Unit,
    onSignInWithToken: (String) -> Unit = {},
    onCreateToken: () -> Unit = {},
) {
    LaunchedEffect(state.githubVerificationUrl) {
        state.githubVerificationUrl?.let(onOpenVerification)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.github_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenEdge, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(stringResource(R.string.github_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AccountCard(login = state.githubLogin, onDisconnect = onDisconnect)
            state.githubMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.githubLogin != null) return@Column

            // One-tap sign-in needs an OAuth app's client ID baked into the build. Without one the
            // token below is the way in, so say that rather than show a dead button.
            if (state.githubConfigured) {
                state.githubUserCode?.let { code ->
                    GithubDeviceCodeCard(code, state.githubVerificationUrl, onOpenVerification)
                } ?: Button(onClick = onConnect, enabled = !state.githubPolling, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (state.githubPolling) R.string.github_waiting_for_authorization else R.string.github_connect,
                        ),
                    )
                }
            } else {
                Text(stringResource(R.string.github_client_id_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TokenSignIn(
                verifying = state.githubVerifyingToken,
                primary = !state.githubConfigured,
                onSignIn = onSignInWithToken,
                onCreateToken = onCreateToken,
            )
        }
    }
}

@Composable
private fun AccountCard(
    login: String?,
    onDisconnect: () -> Unit,
) {
    Surface(
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SettingsIconTile { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) }
            Text(
                text = login?.let { "@$it" } ?: stringResource(R.string.github_not_connected),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (login != null) {
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.github_disconnect), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Sign-in with a personal access token. The token field hides what is typed, and the token is
 * only kept once GitHub has accepted it.
 */
@Composable
private fun TokenSignIn(
    verifying: Boolean,
    primary: Boolean,
    onSignIn: (String) -> Unit,
    onCreateToken: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.github_token_section), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.github_token_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.github_token_hint)) },
            singleLine = true,
            enabled = !verifying,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("github-token-field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            val signIn = { onSignIn(token) }
            if (primary) {
                Button(onClick = signIn, enabled = token.isNotBlank() && !verifying) { TokenButtonLabel(verifying) }
            } else {
                OutlinedButton(onClick = signIn, enabled = token.isNotBlank() && !verifying) { TokenButtonLabel(verifying) }
            }
            TextButton(onClick = onCreateToken) { Text(stringResource(R.string.github_token_create)) }
        }
    }
}

@Composable
private fun TokenButtonLabel(verifying: Boolean) {
    if (verifying) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    } else {
        Text(stringResource(R.string.github_token_sign_in))
    }
}
