package com.yugahashimoto.andcode.feature.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import java.util.Locale

/**
 * The quiet row of actions under a finished assistant reply: copy, retry, read aloud, share, and a
 * small overflow. Icons share the timestamp's tint; each keeps a 48dp touch target.
 */
@Composable
internal fun ReplyActionRow(
    showRetry: Boolean,
    speaking: Boolean,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onRetry: () -> Unit,
    onToggleSpeech: () -> Unit,
    onShare: () -> Unit,
    onQuote: () -> Unit,
    onDetails: () -> Unit,
) {
    val copyMarkdown = stringResource(R.string.action_copy_markdown)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReplyActionIcon(
            icon = Icons.Outlined.ContentCopy,
            description = stringResource(R.string.reply_action_copy),
            onClick = onCopy,
            onLongClick = onCopyMarkdown,
            onLongClickLabel = copyMarkdown,
        )
        if (showRetry) {
            ReplyActionIcon(Icons.Outlined.Refresh, stringResource(R.string.reply_action_retry), onRetry)
        }
        if (speaking) {
            ReplyActionIcon(Icons.Outlined.StopCircle, stringResource(R.string.reply_action_stop_reading), onToggleSpeech)
        } else {
            ReplyActionIcon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.reply_action_read_aloud), onToggleSpeech)
        }
        ReplyActionIcon(Icons.Outlined.Share, stringResource(R.string.reply_action_share), onShare)
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            ReplyActionIcon(Icons.Outlined.MoreHoriz, stringResource(R.string.reply_action_more), { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(copyMarkdown) },
                    leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopyMarkdown()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reply_action_quote)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onQuote()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reply_action_details)) },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDetails()
                    },
                )
            }
        }
    }
}

@Composable
private fun ReplyActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a reply was: model, time taken, tokens and cost, each only when known. */
@Composable
internal fun ReplyDetailsSheet(
    details: List<ReplyDetail>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.reply_details_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            details.forEach { detail ->
                when (detail) {
                    is ReplyDetail.Model -> DetailRow(stringResource(R.string.reply_details_model), detail.name)
                    is ReplyDetail.Duration ->
                        DetailRow(stringResource(R.string.reply_details_time), DateUtils.formatElapsedTime(detail.millis / 1_000L))
                    is ReplyDetail.Tokens -> {
                        DetailRow(stringResource(R.string.usage_kind_input), formatTokenCount(detail.tokens.input))
                        DetailRow(stringResource(R.string.usage_kind_output), formatTokenCount(detail.tokens.output))
                        if (detail.tokens.reasoning > 0L) {
                            DetailRow(stringResource(R.string.usage_kind_reasoning), formatTokenCount(detail.tokens.reasoning))
                        }
                        if (detail.tokens.cacheRead > 0L) {
                            DetailRow(stringResource(R.string.usage_kind_cache_read), formatTokenCount(detail.tokens.cacheRead))
                        }
                    }
                    is ReplyDetail.Cost -> DetailRow(stringResource(R.string.usage_cost), formatUsd(detail.usd))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Asked before retrying a reply that changed files, when they can be put back: restoring drops the
 * reply's changes, so it is never done without asking.
 */
@Composable
internal fun RetryRestoreDialog(
    onRestoreAndRetry: () -> Unit,
    onRetryAsIs: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reply_retry_title)) },
        text = { Text(stringResource(R.string.reply_retry_body)) },
        confirmButton = { TextButton(onClick = onRestoreAndRetry) { Text(stringResource(R.string.reply_retry_restore)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = onRetryAsIs) { Text(stringResource(R.string.reply_retry_as_is)) }
            }
        },
    )
}

/**
 * Copies [text] with a short confirmation; text too long for the clipboard is shared as a file
 * instead, as the transcript's copy does. Returns whether it reached the clipboard.
 */
internal fun copyReplyText(
    context: Context,
    text: String,
    title: String,
    setClipboard: (String) -> Unit,
): Boolean {
    val copied = text.length <= MAX_CLIPBOARD_TRANSCRIPT_CHARS && runCatching { setClipboard(text) }.isSuccess
    if (copied) {
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.reply_copied, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, R.string.reply_copy_too_long, Toast.LENGTH_LONG).show()
        shareChatTranscript(context, title, text, context.getString(R.string.reply_action_share))
    }
    return copied
}

/** Opens the share sheet with [text]; text past the binder's comfort is shared as a file. */
internal fun shareReplyText(
    context: Context,
    text: String,
    title: String,
) {
    val chooserTitle = context.getString(R.string.reply_action_share)
    if (text.length > MAX_CLIPBOARD_TRANSCRIPT_CHARS) {
        shareChatTranscript(context, title, text, chooserTitle)
        return
    }
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Reads replies aloud with the device's speech engine, one at a time, in [locale] when the engine
 * has it and else in the device's default. [speakingKey] names the reply being read. Created on the
 * first reply read, released by [release]; [onUnavailable] reports a device with no usable engine.
 */
@Stable
internal class ReplySpeaker(
    context: Context,
    private val locale: Locale,
    private val onUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false

    /** The utterance ids and pieces of a reading waiting for the engine to start. */
    private var pending: Pair<List<String>, List<String>>? = null

    /** How many readings have started, and the id of the one in progress; see [speechUtteranceIds]. */
    private var readings = 0
    private var readingId: String? = null

    var speakingKey by mutableStateOf<String?>(null)
        private set

    private val progress =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            // The last piece of a reading carries the reading's id; the engine calls back off the main thread.
            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = failed(utteranceId)

            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) = failed(utteranceId)
        }

    /** Only the reading in progress ends here: a late callback from an earlier one, of the same reply too, is ignored. */
    private fun finished(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId != null && utteranceId == readingId) {
                readingId = null
                speakingKey = null
            }
        }
    }

    /** A piece the engine could not speak ends the reading: the pieces queued after it are dropped too. */
    private fun failed(utteranceId: String?) {
        val reading = utteranceId?.let(::speechReadingOf)
        mainHandler.post { if (reading != null && reading == readingId) stop() }
    }

    /** Reads [text] as [key], or stops if [key] is the reply being read. */
    fun toggle(
        key: String,
        text: String,
    ) {
        if (speakingKey == key) {
            stop()
            return
        }
        stop()
        val pieces = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (pieces.isEmpty()) return
        speakingKey = key
        readings += 1
        val ids = speechUtteranceIds(key, readings, pieces.size)
        readingId = ids.last()
        val tts = engine
        when {
            tts == null -> {
                pending = ids to pieces
                engine = TextToSpeech(appContext) { status -> mainHandler.post { initialized(status) } }
            }
            ready -> say(tts, ids, pieces)
            else -> pending = ids to pieces
        }
    }

    private fun initialized(status: Int) {
        val tts = engine ?: return
        ready = status == TextToSpeech.SUCCESS && (speaks(tts, locale) || speaks(tts, Locale.getDefault()))
        if (!ready) {
            release()
            onUnavailable()
            return
        }
        tts.setOnUtteranceProgressListener(progress)
        pending?.let { (ids, pieces) -> say(tts, ids, pieces) }
        pending = null
    }

    private fun speaks(
        tts: TextToSpeech,
        locale: Locale,
    ): Boolean = tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE

    private fun say(
        tts: TextToSpeech,
        ids: List<String>,
        pieces: List<String>,
    ) {
        pieces.forEachIndexed { index, piece ->
            val id = ids[index]
            val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(piece, queue, null, id) == TextToSpeech.ERROR) {
                // The pieces already queued would otherwise go on being read.
                stop()
                return
            }
        }
    }

    fun stop() {
        pending = null
        engine?.stop()
        readingId = null
        speakingKey = null
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}

/** A [ReplySpeaker] in the app's language, released when the screen leaves. */
@Composable
internal fun rememberReplySpeaker(onUnavailable: () -> Unit): ReplySpeaker {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val speaker = remember(locale) { ReplySpeaker(context, locale) { currentOnUnavailable() } }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    return speaker
}
