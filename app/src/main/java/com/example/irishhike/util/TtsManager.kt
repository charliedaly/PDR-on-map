package com.example.irishhike.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(
    context: Context,
    private val onSpeechDone: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var isSpeaking = false

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                Log.d("TtsManager", "TTS Started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                Log.d("TtsManager", "TTS Done: $utteranceId")
                onSpeechDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                Log.e("TtsManager", "TTS Error: $utteranceId")
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                Log.e("TtsManager", "TTS Error ($errorCode): $utteranceId")
                onSpeechDone?.invoke()
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            isInitialized = true
            Log.i("TtsManager", "TextToSpeech initialized successfully.")
            onSpeechDone?.invoke()
        } else {
            Log.e("TtsManager", "TextToSpeech initialization failed with status: $status")
        }
    }

    fun isReady(): Boolean = isInitialized

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized || text.isBlank()) return

        val utteranceId = "HikeMsg_${System.currentTimeMillis()}"
        tts?.speak(text, queueMode, null, utteranceId)
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
            isSpeaking = false
        }
    }

    fun shutdown() {
        if (isInitialized) {
            tts?.stop()
            tts?.shutdown()
            isInitialized = false
            isSpeaking = false
        }
    }
}
