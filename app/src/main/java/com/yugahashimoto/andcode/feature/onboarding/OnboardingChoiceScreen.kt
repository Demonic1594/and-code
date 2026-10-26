package com.yugahashimoto.andcode.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

private enum class OnboardingOption { ANDROID, REMOTE }

/**
 * First run requires one usable execution path before chat can start.
 *
 * What the app does comes first, in three lines, so the choice below reads as "where should it
 * run" rather than as a form. The button stays pinned to the bottom edge, where the thumb already
 * is, and says what it will do for the option picked.
 */
@Composable
fun OnboardingChoiceScreen(
    onSelectAndroid: () -> Unit,
    onSelectRemote: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(OnboardingOption.ANDROID) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(40.dp))
                Surface(
                    shape = AppShapes.xl,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                    Image(
                        painter = painterResource(R.drawable.andcode_logo),
                        contentDescription = stringResource(R.string.cd_app_logo),
                        modifier = Modifier.padding(Spacing.lg).size(40.dp),
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
                Text(
                    // The app's own name, not a literal in eight translations: this greeting still said
                    // "Welcome to OpenCode" after the app was renamed, in every language.
                    text = stringResource(R.string.onboarding_welcome_title, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(Spacing.xxl))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                    FeatureLine(Icons.Outlined.Terminal, stringResource(R.string.onboarding_feature_agents))
                    FeatureLine(Icons.Outlined.RateReview, stringResource(R.string.onboarding_feature_review))
                    FeatureLine(Icons.Outlined.NotificationsActive, stringResource(R.string.onboarding_feature_anywhere))
                }

                Spacer(Modifier.height(Spacing.xxl))
                Column(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OnboardingCard(
                        icon = Icons.Default.Android,
                        title = stringResource(R.string.onboarding_android_title),
                        description = stringResource(R.string.onboarding_android_desc),
                        badge = stringResource(R.string.onboarding_recommended_badge),
                        selected = selected == OnboardingOption.ANDROID,
                        onClick = { selected = OnboardingOption.ANDROID },
                    )
                    OnboardingCard(
                        icon = Icons.Default.Computer,
                        title = stringResource(R.string.onboarding_remote_title),
                        description = stringResource(R.string.onboarding_remote_desc),
                        badge = null,
                        selected = selected == OnboardingOption.REMOTE,
                        onClick = { selected = OnboardingOption.REMOTE },
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Button(
                    onClick = { if (selected == OnboardingOption.ANDROID) onSelectAndroid() else onSelectRemote() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = AppShapes.pill,
                ) {
                    // The label changes with the choice; a quick cross-fade says it changed.
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_MS)) },
                        label = "onboardingButton",
                    ) { option ->
                        Text(
                            text =
                                stringResource(
                                    if (option == OnboardingOption.ANDROID) {
                                        R.string.onboarding_primary_button_android
                                    } else {
                                        R.string.onboarding_primary_button_remote
                                    },
                                ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.onboarding_switch_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FeatureLine(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Surface(shape = AppShapes.md, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(Spacing.sm).size(18.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OnboardingCard(
    icon: ImageVector,
    title: String,
    description: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(MOTION_MS),
        label = "onboardingCardBorder",
    )
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(MOTION_MS),
        label = "onboardingCardContainer",
    )
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = AppShapes.lg,
        color = container,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.lg, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Surface(
                shape = AppShapes.md,
                color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.sm).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (badge != null) {
                    Surface(shape = AppShapes.pill, color = accent.copy(alpha = 0.14f)) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.hairline),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            // The card is the radio button; this one only shows its state.
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private const val MOTION_MS = 220

@Preview(showBackground = true)
@Composable
private fun OnboardingChoiceScreenPreview() {
    AndCodeTheme {
        OnboardingChoiceScreen(onSelectAndroid = {}, onSelectRemote = {})
    }
}
