package com.sistermesh.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Small TextToSpeech wrapper that selects only installed voices marked as offline-capable. */
class OfflineSpeechOutput(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("sistermesh_offline_voice", Context.MODE_PRIVATE)
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var selectedVoice: Voice? = null

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            val current = engine
            if (result != TextToSpeech.SUCCESS || current == null) return@TextToSpeech
            val voices = current.voices
                ?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                .orEmpty()
            val preferredName = preferences.getString("tts_voice", null)
            selectedVoice = voices.firstOrNull { it.name == preferredName } ?: voices.firstOrNull()
            val voice = selectedVoice ?: return@TextToSpeech
            current.voice = voice
            current.setSpeechRate(preferences.getFloat("tts_rate", 1f).coerceIn(0.7f, 1.5f))
            ready = true
            pendingText?.let(::speakNow)
            pendingText = null
        }
    }

    fun speak(text: String) {
        if (!ready) {
            pendingText = text
            return
        }
        speakNow(text)
    }

    fun close() {
        ready = false
        pendingText = null
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun speakNow(text: String) {
        val current = engine ?: return
        val voice = selectedVoice?.takeUnless { it.isNetworkConnectionRequired } ?: return
        current.voice = voice
        current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sistermesh-request-confirmation")
    }
}
