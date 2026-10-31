package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.feature.assistant.SpeechRecognizerManager
import com.yugahashimoto.andcode.feature.assistant.SpeechResult
import com.yugahashimoto.andcode.feature.assistant.SpeechTranscriptAccumulator
import com.yugahashimoto.andcode.feature.assistant.VoiceDictationOutcome
import com.yugahashimoto.andcode.feature.assistant.VoiceDictationPolicy
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.wakeword.WakeWordService
import kotlinx.coroutines.delay
import java.util.UUID

private object VoiceDictationTiming {
    /** The pause before listening again after the recogniser ends a segment. */
    const val RESTART_DELAY_MS = 200L
}

/**
 * Dictates into the composer until the speech recogniser gives up or the caller cancels.
 *
 * The recogniser ends a segment on each pause, so it is started again until [VoiceDictationPolicy]
 * says to stop, and the transcript of every segment is kept. The caller has already called
 * [ChatViewModel.startListening]; this always ends with [ChatViewModel.stopListening].
 */
internal suspend fun runVoiceDictation(
    speechManager: SpeechRecognizerManager,
    localeTag: () -> String,
    chatViewModel: ChatViewModel,
) {
    val transcript = SpeechTranscriptAccumulator()
    // Wake-word detection records straight from the microphone, while this dictation records
    // through SpeechRecognizer in another process. Left running, detection wins the platform's
    // arbitration and the recogniser is handed silence.
    val micToken = UUID.randomUUID().toString()
    WakeWordService.holdMicrophone(micToken)
    try {
        var silentSegments = 0
        while (true) {
            var outcome = VoiceDictationOutcome.RESTART
            speechManager.startListening(localeTag()).collect { result ->
                when (result) {
                    SpeechResult.Ready,
                    SpeechResult.Listening,
                    -> Unit
                    SpeechResult.Processing -> chatViewModel.showSpeechProcessing()
                    is SpeechResult.PartialResult -> {
                        chatViewModel.updateSpeechPartial(transcript.preview(result.text))
                    }
                    is SpeechResult.Result -> {
                        silentSegments = 0
                        transcript.append(result.text)
                        chatViewModel.updateSpeechPartial(transcript.text)
                    }
                    is SpeechResult.Error -> {
                        silentSegments += 1
                        outcome =
                            VoiceDictationPolicy.outcomeFor(
                                code = result.code,
                                hasTranscript = transcript.text.isNotBlank(),
                                consecutiveFailures = silentSegments,
                            )
                        if (outcome == VoiceDictationOutcome.REPORT) {
                            chatViewModel.reportSpeechError(result.message)
                        }
                    }
                }
            }
            if (outcome != VoiceDictationOutcome.RESTART) break
            delay(VoiceDictationTiming.RESTART_DELAY_MS)
            chatViewModel.startListening()
            if (transcript.text.isNotBlank()) {
                chatViewModel.updateSpeechPartial(transcript.text)
            }
        }
    } finally {
        WakeWordService.releaseMicrophone(micToken)
        chatViewModel.stopListening()
    }
}
