package com.yugahashimoto.andcode.feature.chat

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Dictation into the composer and reading answers aloud, for [ChatViewModel]: the listening and
 * processing flags, the partial transcript, and the [TextToSpeech] engine the screen hands over.
 */
internal class ChatVoice(
    private val uiState: MutableStateFlow<ChatUiState>,
) {
    private var tts: TextToSpeech? = null

    fun setTTS(textToSpeech: TextToSpeech) {
        tts = textToSpeech
    }

    fun startListening() {
        uiState.update {
            it.copy(
                isListening = true,
                isSpeechProcessing = false,
                partialText = "",
                error = null,
            )
        }
    }

    fun updateSpeechPartial(text: String) {
        uiState.update {
            it.copy(
                isListening = true,
                isSpeechProcessing = false,
                partialText = text,
            )
        }
    }

    fun showSpeechProcessing() {
        uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = true,
                partialText = it.partialText,
            )
        }
    }

    fun reportSpeechError(message: String) {
        uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = false,
                error = message,
            )
        }
    }

    fun stopListening() {
        uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = false,
                partialText = it.partialText,
            )
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        uiState.update { it.copy(isSpeaking = false) }
    }

    /** Stops speaking and lets go of the engine; the view model is being cleared. */
    fun shutDown() {
        tts?.stop()
        tts = null
    }
}
