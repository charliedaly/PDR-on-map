package com.example.irishhike.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
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

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TtsManager", "TTS Started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d("TtsManager", "TTS Done: $utteranceId")
                onSpeechDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e("TtsManager", "TTS Error: $utteranceId")
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e("TtsManager", "TTS Error ($errorCode): $utteranceId")
                onSpeechDone?.invoke()
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configure audio attributes as navigation guidance so it routes to Bluetooth headphones cleanly
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            isInitialized = true
            Log.i("TtsManager", "TextToSpeech initialized with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.")
            onSpeechDone?.invoke()
        } else {
            Log.e("TtsManager", "TextToSpeech initialization failed with status: $status")
        }
    }

    fun isReady(): Boolean = isInitialized

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized || text.isBlank()) return

        val utteranceId = "HikeMsg_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, queueMode, params, utteranceId)
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
        }
    }

    fun shutdown() {
        if (isInitialized) {
            tts?.stop()
            tts?.shutdown()
            isInitialized = false
        }
    }
}