package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.ui.components.SessionStatus

// What the drawer derives from the app's state, kept free of Compose so it can be unit tested.

private object DrawerModelLimits {
    /** How many chats the drawer lists. */
    const val RECENT_CHATS = 25
}

/**
 * The drawer's chat list: every runtime's top-level chats, newest first as the catalog orders them.
 *
 * Every runtime's chats, not just the selected one's: switching agent used to look like the history
 * had been wiped. Each row carries the runtime that owns it so opening one can switch. Subagent
 * chats are left out, and so are chats waiting out the undo window after a delete, which already
 * look gone.
 */
internal fun drawerRecentSessions(
    allSessions: List<RuntimeSessionRef>,
    activeSessionIds: Set<String>,
    completedSessionIds: Set<String>,
    pendingDeletion: Set<String>,
    timeLabel: (epochMillis: Long) -> String,
): List<DrawerRecentSession> =
    allSessions
        .filter { it.session.parentId == null && it.session.id !in pendingDeletion }
        .take(DrawerModelLimits.RECENT_CHATS)
        .map { ref ->
            val session = ref.session
            val running = session.id in activeSessionIds
            val unread = session.id in completedSessionIds
            DrawerRecentSession(
                id = session.id,
                title = session.title.ifBlank { session.slug ?: session.id },
                relativeTime = timeLabel(session.time.updated ?: session.time.created),
                directory = session.directory,
                isActive = running,
                hasUnread = unread,
                status =
                    when {
                        running -> SessionStatus.RUNNING
                        unread -> SessionStatus.COMPLETED_UNREAD
                        else -> SessionStatus.IDLE
                    },
                runtimeId = ref.runtimeId,
                agent = ref.agent,
            )
        }

/** The launcher shortcuts for the pinned chats among [recentSessions]. */
internal fun pinnedChatShortcuts(
    recentSessions: List<DrawerRecentSession>,
    pinnedSessionIds: Set<String>,
): List<PinnedChatShortcut> =
    recentSessions
        .filter { it.id in pinnedSessionIds }
        .map { PinnedChatShortcut(it.id, it.title, it.runtimeId) }

/**
 * Local agents that are actually provisioned. A target reports Unavailable while its agent is
 * missing, which is exactly the case the switcher must not offer. [states] is the observed state of
 * each target; a target not in it yet falls back to its current value.
 */
internal fun offeredDrawerAgents(
    runtimeTargets: List<RuntimeTarget>,
    states: Map<String, RuntimeState>,
): List<DrawerAgent> =
    runtimeTargets.mapNotNull { target ->
        val agent = target.agent ?: return@mapNotNull null
        val state = states[target.id] ?: target.state.value
        DrawerAgent(target.id, agent).takeIf { state !is RuntimeState.Unavailable }
    }

/** [collapsed] with [section] flipped between collapsed and expanded. */
internal fun toggledSection(
    collapsed: Set<String>,
    section: String,
): Set<String> = if (section in collapsed) collapsed - section else collapsed + section
