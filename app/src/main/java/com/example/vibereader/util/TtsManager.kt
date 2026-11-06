package com.example.vibereader.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SpeechProgress(
    val startCharIndex: Int = 0,
    val endCharIndex: Int = 0,
    val utteranceId: String = "",
    val isActive: Boolean = false,
    val isApproximate: Boolean = false,
)

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var pendingText: String? = null
    private var currentText = ""
    private var currentCharIndex = 0
    private var utteranceCounter = 0
    private var finalUtteranceId: String? = null
    private var utteranceStartedAt = 0L
    private var activeUtteranceOffset = 0
    private val utteranceStartOffsets = mutableMapOf<String, Int>()
    private val _speechProgress = MutableStateFlow(SpeechProgress())
    val speechProgress: StateFlow<SpeechProgress> = _speechProgress.asStateFlow()

    val isSpeaking = mutableStateOf(false)
    val isPaused = mutableStateOf(false)
    val pitch = mutableFloatStateOf(1f)
    val speed = mutableFloatStateOf(1f)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        isInitialized = true
        engine.language = Locale.getDefault()
        engine.setPitch(pitch.floatValue)
        engine.setSpeechRate(speed.floatValue)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId !in utteranceStartOffsets) return
                utteranceStartedAt = SystemClock.elapsedRealtime()
                activeUtteranceOffset = utteranceStartOffsets[utteranceId] ?: 0
                isSpeaking.value = true
                isPaused.value = false
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId !in utteranceStartOffsets) return
                utteranceStartOffsets.remove(utteranceId)
                if (utteranceId == finalUtteranceId) {
                    isSpeaking.value = false
                    isPaused.value = false
                    currentText = ""
                    currentCharIndex = 0
                    finalUtteranceId = null
                    utteranceStartOffsets.clear()
                    _speechProgress.value = _speechProgress.value.copy(isActive = false)
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) {
                if (utteranceId !in utteranceStartOffsets) return
                isSpeaking.value = false
                isPaused.value = false
                utteranceStartOffsets.clear()
                finalUtteranceId = null
                _speechProgress.value = _speechProgress.value.copy(isActive = false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                val baseOffset = utteranceId?.let(utteranceStartOffsets::get) ?: return
                currentCharIndex = (baseOffset + start).coerceIn(0, currentText.length)
                _speechProgress.value = SpeechProgress(
                    startCharIndex = (baseOffset + start).coerceIn(0, currentText.length),
                    endCharIndex = (baseOffset + end).coerceIn(0, currentText.length),
                    utteranceId = utteranceId,
                    isActive = true,
                )
            }
        })
        pendingText?.let(::startSpeaking)
        pendingText = null
    }

    fun speak(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        currentText = cleanText
        currentCharIndex = 0
        _speechProgress.value = SpeechProgress(isActive = false)
        isPaused.value = false
        if (!isInitialized) {
            pendingText = cleanText
            return
        }
        startSpeaking(cleanText)
    }

    fun pause() {
        if (!isSpeaking.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val elapsedMs = (SystemClock.elapsedRealtime() - utteranceStartedAt).coerceAtLeast(0L)
            val estimatedCharacters = (elapsedMs * speed.floatValue * LEGACY_CHARS_PER_SECOND / 1000)
                .toInt()
            currentCharIndex = (activeUtteranceOffset + estimatedCharacters)
                .coerceIn(0, currentText.length)
        }
        finalUtteranceId = null
        utteranceStartOffsets.clear()
        tts?.stop()
        isSpeaking.value = false
        isPaused.value = currentCharIndex < currentText.length
        _speechProgress.value = _speechProgress.value.copy(isActive = false)
    }

    fun resume() {
        if (!isPaused.value || currentCharIndex >= currentText.length) return
        startSpeaking(currentText.substring(currentCharIndex), currentCharIndex)
    }

    fun setPitch(value: Float) {
        val bounded = value.coerceIn(0.5f, 2f)
        pitch.floatValue = bounded
        tts?.setPitch(bounded)
    }

    fun setSpeed(value: Float) {
        val bounded = value.coerceIn(0.5f, 2f)
        speed.floatValue = bounded
        tts?.setSpeechRate(bounded)
    }

    fun stop() {
        finalUtteranceId = null
        utteranceStartOffsets.clear()
        tts?.stop()
        pendingText = null
        currentText = ""
        currentCharIndex = 0
        isSpeaking.value = false
        isPaused.value = false
        _speechProgress.value = SpeechProgress()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun startSpeaking(text: String, startOffset: Int = 0) {
        val engine = tts ?: return
        engine.setPitch(pitch.floatValue)
        engine.setSpeechRate(speed.floatValue)
        val chunks = splitIntoChunks(text)
        if (chunks.isEmpty()) return
        utteranceStartOffsets.clear()
        var failed = false
        chunks.forEachIndexed { index, (relativeOffset, chunk) ->
            val utteranceId = "VibeReaderTTS-${++utteranceCounter}"
            utteranceStartOffsets[utteranceId] = startOffset + relativeOffset
            finalUtteranceId = utteranceId
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(chunk, queueMode, null, utteranceId)
            if (result == TextToSpeech.ERROR) failed = true
        }
        if (failed) {
            isSpeaking.value = false
            isPaused.value = false
        } else {
            isSpeaking.value = true
            isPaused.value = false
        }
        val firstOffset = startOffset.coerceIn(0, currentText.length)
        _speechProgress.value = SpeechProgress(
            startCharIndex = firstOffset,
            endCharIndex = firstOffset,
            utteranceId = finalUtteranceId.orEmpty(),
            isActive = true,
        )
    }

    private fun splitIntoChunks(text: String): List<Pair<Int, String>> {
        val chunks = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < text.length) {
            var end = (start + MAX_CHUNK_LENGTH).coerceAtMost(text.length)
            if (end < text.length) {
                val breakAt = text.lastIndexOf(' ', end)
                if (breakAt > start) end = breakAt + 1
            }
            chunks += start to text.substring(start, end)
            start = end
        }
        return chunks
    }

    private companion object {
        const val MAX_CHUNK_LENGTH = 3500
        const val LEGACY_CHARS_PER_SECOND = 14
    }
}
