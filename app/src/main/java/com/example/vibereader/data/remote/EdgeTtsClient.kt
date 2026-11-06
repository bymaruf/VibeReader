package com.example.vibereader.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTtsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    suspend fun synthesize(text: String, voiceName: String): ByteArray {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Text to speak cannot be empty." }
        require(voiceName in SUPPORTED_VOICES) { "Unsupported Edge TTS voice." }

        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url("$SYNTHESIS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connectionId")
            .header("Origin", "https://www.microsoft.com")
            .header("User-Agent", USER_AGENT)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val audio = ByteArrayOutputStream()
            var receivedAudio = false
            val socket = httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val configSent = webSocket.send(SPEECH_CONFIG)
                        val ssmlSent = configSent && webSocket.send(createSsml(cleanText, voiceName))
                        if (!ssmlSent) {
                            webSocket.cancel()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Could not start Edge neural speech synthesis."),
                                )
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end", ignoreCase = true)) {
                            if (continuation.isActive) {
                                val result = audio.toByteArray()
                                if (receivedAudio && result.isNotEmpty()) {
                                    continuation.resume(result)
                                } else {
                                    continuation.resumeWithException(
                                        IOException("Edge TTS returned no audio."),
                                    )
                                }
                            }
                            webSocket.close(1000, "Synthesis complete")
                        } else if (text.contains("Path:turn.error", ignoreCase = true)) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Edge TTS reported a synthesis error."),
                                )
                            }
                            webSocket.cancel()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = bytes.toByteArray()
                        if (frame.size < BINARY_HEADER_LENGTH_BYTES) return
                        val headerLength = ((frame[0].toInt() and 0xff) shl 8) or
                            (frame[1].toInt() and 0xff)
                        val audioOffset = BINARY_HEADER_LENGTH_BYTES + headerLength
                        if (audioOffset >= frame.size) return
                        synchronized(audio) {
                            audio.write(frame, audioOffset, frame.size - audioOffset)
                            receivedAudio = true
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException(
                                    response?.let { "Edge TTS connection failed (HTTP ${it.code})." }
                                        ?: "Could not connect to Edge TTS.",
                                    t,
                                ),
                            )
                        }
                    }
                },
            )
            continuation.invokeOnCancellation {
                socket.cancel()
            }
        }
    }

    private fun createSsml(text: String, voiceName: String): String {
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val requestId = UUID.randomUUID().toString().replace("-", "")
        return "X-RequestId:$requestId\r\n" +
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "Path:ssml\r\n\r\n" +
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"en-US\"><voice name=\"$voiceName\">" +
            "<prosody rate=\"0%\" pitch=\"0%\">$escapedText</prosody>" +
            "</voice></speak>\r\n"
    }

    private companion object {
        const val SYNTHESIS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EA5E4076B96E345D5684A108"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
        const val BINARY_HEADER_LENGTH_BYTES = 2
        const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        const val SPEECH_CONFIG =
            "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
        val SUPPORTED_VOICES = setOf("en-US-JennyNeural", "en-US-GuyNeural")
    }
}
