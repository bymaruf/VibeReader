package com.example.vibereader.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.vibereader.model.AiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WallpaperImageGenerator(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    fun generate(provider: AiProvider, apiKey: String, prompt: String): Bitmap {
        require(provider == AiProvider.OPENAI || provider == AiProvider.OPENROUTER) {
            "Choose OpenAI or OpenRouter with an image-generation key in AI settings."
        }
        val model = if (provider == AiProvider.OPENAI) OPENAI_IMAGE_MODEL else OPENROUTER_IMAGE_MODEL
        val requestJson = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", if (provider == AiProvider.OPENAI) "1024x1792" else "1024x1536")
            .put("n", 1)
            .apply {
                if (provider == AiProvider.OPENAI) put("response_format", "b64_json")
            }
        val url = if (provider == AiProvider.OPENAI) {
            "https://api.openai.com/v1/images/generations"
        } else {
            "https://openrouter.ai/api/v1/images/generations"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val imageData = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw WallpaperImageApiException(
                    response.code,
                    "Image generation failed (HTTP ${response.code}). Check the image API key, model access, and quota.",
                )
            }
            val firstImage = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
                ?: throw IOException("The image provider returned no wallpaper image.")
            firstImage.optString("b64_json").takeIf(String::isNotBlank)?.let {
                ImageData.Base64Image(it)
            } ?: firstImage.optString("url").takeIf(String::isNotBlank)?.let {
                ImageData.RemoteImage(it)
            } ?: throw IOException("The image provider returned an unsupported image response.")
        }

        val bytes = when (imageData) {
            is ImageData.Base64Image -> Base64.decode(imageData.value, Base64.DEFAULT)
            is ImageData.RemoteImage -> downloadImage(imageData.value)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("The generated wallpaper image could not be decoded.")
    }

    private fun downloadImage(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WallpaperImageApiException(
                    response.code,
                    "Could not download the generated wallpaper (HTTP ${response.code}).",
                )
            }
            response.body?.bytes() ?: throw IOException("The image provider returned an empty image.")
        }
    }

    private sealed interface ImageData {
        data class Base64Image(val value: String) : ImageData
        data class RemoteImage(val value: String) : ImageData
    }

    companion object {
        private const val OPENAI_IMAGE_MODEL = "dall-e-3"
        private const val OPENROUTER_IMAGE_MODEL = "black-forest-labs/flux.1-schnell"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class WallpaperImageApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)
