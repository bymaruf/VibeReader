package com.example.vibereader.util

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.example.vibereader.data.remote.EdgeTtsClient
import com.example.vibereader.model.ReaderTtsVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class FreeAiTtsPlayer(
    context: Context,
    private val edgeTtsClient: EdgeTtsClient = EdgeTtsClient(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var cachedAudioFile: File? = null
    private var synthesisJob: kotlinx.coroutines.Job? = null
    private var progressJob: Job? = null
    private var speechText = ""
    private var playbackGeneration = 0L
    private val _speechProgress = MutableStateFlow(SpeechProgress())
    val speechProgress: StateFlow<SpeechProgress> = _speechProgress.asStateFlow()

    val isLoading = mutableStateOf(false)
    val isPlaying = mutableStateOf(false)
    val isPaused = mutableStateOf(false)
    val isUsingNativeFallback = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    fun speak(
        text: String,
        voice: ReaderTtsVoice,
        onNativeFallback: ((String) -> Unit)? = null,
    ) {
        val voiceName = voice.edgeVoiceName ?: return
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            error.value = "There is no text to read."
            return
        }
        mediaPlayer?.stopSafely()
        mediaPlayer?.release()
        mediaPlayer = null
        cachedAudioFile?.delete()
        cachedAudioFile = null
        synthesisJob?.cancel()
        progressJob?.cancel()
        val generation = ++playbackGeneration
        speechText = cleanText
        _speechProgress.value = SpeechProgress()
        isPlaying.value = false
        isPaused.value = false
        isLoading.value = true
        isUsingNativeFallback.value = false
        error.value = null

        synthesisJob = scope.launch {
            try {
                val audioFile = withContext(Dispatchers.IO) {
                    val audio = edgeTtsClient.synthesize(cleanText, voiceName)
                    val cacheDir = File(appContext.cacheDir, CACHE_DIRECTORY)
                    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                        throw IOException("Could not create speech audio cache.")
                    }
                    File.createTempFile(AUDIO_PREFIX, AUDIO_SUFFIX, cacheDir).apply {
                        writeBytes(audio)
                    }
                }
                if (generation != playbackGeneration) {
                    audioFile.delete()
                    return@launch
                }
                cachedAudioFile = audioFile
                val player = MediaPlayer()
                mediaPlayer = player
                player.setDataSource(audioFile.absolutePath)
                player.setOnPreparedListener { preparedPlayer ->
                    if (generation != playbackGeneration) {
                        preparedPlayer.release()
                        return@setOnPreparedListener
                    }
                    isLoading.value = false
                    isPlaying.value = true
                    isPaused.value = false
                    preparedPlayer.start()
                    startProgressUpdates(preparedPlayer)
                }
                player.setOnCompletionListener {
                    if (generation != playbackGeneration) return@setOnCompletionListener
                    isPlaying.value = false
                    isPaused.value = false
                    progressJob?.cancel()
                    _speechProgress.value = _speechProgress.value.copy(isActive = false)
                    releasePlaybackFile()
                }
                player.setOnErrorListener { _, what, extra ->
                    if (generation != playbackGeneration) return@setOnErrorListener true
                    val exception = IOException(
                        "Edge TTS audio playback failed (what=$what, extra=$extra).",
                    )
                    Log.w(TAG, "Edge TTS audio playback failed; using native TTS.", exception)
                    fallbackToNative(cleanText, onNativeFallback, exception, generation)
                    true
                }
                player.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (generation != playbackGeneration) return@launch
                isLoading.value = false
                isPlaying.value = false
                isPaused.value = false
                mediaPlayer?.release()
                mediaPlayer = null
                cachedAudioFile?.delete()
                cachedAudioFile = null
                fallbackToNative(cleanText, onNativeFallback, exception, generation)
            }
        }
    }

    fun togglePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            isPlaying.value = false
            isPaused.value = true
        } else {
            player.start()
            isPlaying.value = true
            isPaused.value = false
            startProgressUpdates(player)
        }
    }

    fun stop() {
        playbackGeneration++
        synthesisJob?.cancel()
        synthesisJob = null
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.stopSafely()
        mediaPlayer?.release()
        mediaPlayer = null
        releasePlaybackFile()
        isLoading.value = false
        isPlaying.value = false
        isPaused.value = false
        isUsingNativeFallback.value = false
        error.value = null
        speechText = ""
        _speechProgress.value = SpeechProgress()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun releasePlaybackFile() {
        mediaPlayer?.release()
        mediaPlayer = null
        cachedAudioFile?.delete()
        cachedAudioFile = null
    }

    private fun fallbackToNative(
        text: String,
        onNativeFallback: ((String) -> Unit)?,
        cause: Exception,
        generation: Long,
    ) {
        if (generation != playbackGeneration) return
        isLoading.value = false
        isPlaying.value = false
        isPaused.value = false
        progressJob?.cancel()
        progressJob = null
        releasePlaybackFile()
        _speechProgress.value = _speechProgress.value.copy(isActive = false)
        if (onNativeFallback == null) {
            Log.e(TAG, "Edge TTS failed and no native fallback was provided.", cause)
            error.value = cause.localizedMessage ?: "Edge TTS is currently unavailable."
            return
        }

        Log.w(TAG, "Edge TTS failed; switching to native Android TTS.", cause)
        isUsingNativeFallback.value = true
        error.value = null
        try {
            onNativeFallback(text)
        } catch (fallbackError: Exception) {
            Log.e(TAG, "Native Android TTS fallback could not start.", fallbackError)
            isUsingNativeFallback.value = false
            error.value = fallbackError.localizedMessage
                ?: "Could not start Android system speech."
        }
    }

    private fun startProgressUpdates(player: MediaPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (player === mediaPlayer && player.isPlaying && speechText.isNotEmpty()) {
                val duration = player.duration
                if (duration > 0) {
                    val characterIndex = (
                        speechText.length.toLong() * player.currentPosition / duration
                    ).toInt().coerceIn(0, speechText.lastIndex)
                    var start = characterIndex
                    while (start > 0 && !speechText[start - 1].isWhitespace()) start--
                    var end = characterIndex
                    while (end < speechText.length && !speechText[end].isWhitespace()) end++
                    _speechProgress.value = SpeechProgress(
                        startCharIndex = start,
                        endCharIndex = end.coerceAtLeast(start + 1),
                        utteranceId = EDGE_UTTERANCE_ID,
                        isActive = true,
                        isApproximate = true,
                    )
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun MediaPlayer.stopSafely() {
        try {
            if (isPlaying) stop()
        } catch (_: IllegalStateException) {
            // MediaPlayer may already have completed or failed.
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "edge_tts"
        const val AUDIO_PREFIX = "speech_"
        const val AUDIO_SUFFIX = ".mp3"
        const val TAG = "FreeAiTtsPlayer"
        const val EDGE_UTTERANCE_ID = "edge-tts"
        const val PROGRESS_INTERVAL_MS = 100L
    }
}
