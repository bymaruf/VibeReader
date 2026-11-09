package com.example.vibereader.model

import com.example.vibereader.data.remote.DiscoveredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {
    @Test
    fun openCodeUsesDocumentedChatAndCatalogEndpoints() {
        assertEquals(
            "https://opencode.ai/inference/openai/v1/chat/completions",
            AiProvider.OPENCODE.chatCompletionsUrl,
        )
        assertEquals(
            "https://opencode.ai/inference/v1/models",
            AiProvider.OPENCODE.modelsUrl,
        )
        assertEquals("deepseek-v4.1-flash", AiProvider.OPENCODE.defaultModel)
    }

    @Test
    fun openAiCompatibleProvidersAppendChatCompletions() {
        AiProvider.entries
            .filterNot { it.usesAnthropicMessagesApi }
            .forEach { provider ->
                assertTrue(
                    "${provider.name} should target chat/completions",
                    provider.chatCompletionsUrl.endsWith("/chat/completions"),
                )
                assertFalse(provider.chatCompletionsUrl.contains("//chat"))
            }
    }

    @Test
    fun anthropicUsesNativeMessagesApi() {
        assertTrue(AiProvider.ANTHROPIC.usesAnthropicMessagesApi)
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiProvider.ANTHROPIC.chatCompletionsUrl,
        )
    }

    @Test
    fun tierLabelsDistinguishFreePaidAndProModels() {
        assertEquals("FREE", DiscoveredModel(id = "free-model", name = "Free", isFree = true).tierLabel)
        assertEquals(
            "PAID",
            DiscoveredModel(id = "standard-model", name = "Standard").tierLabel,
        )
        assertEquals(
            "PRO",
            DiscoveredModel(
                id = "premium-model",
                name = "Premium",
                isHighPrecision = true,
            ).tierLabel,
        )
    }

    @Test
    fun freeModelsNeverShowProBadge() {
        val freeReasoningModel = DiscoveredModel(
            id = "free-reasoner",
            name = "Free Reasoner",
            isFree = true,
            isHighPrecision = true,
        )
        assertFalse(freeReasoningModel.isPro)
        assertEquals("FREE", freeReasoningModel.tierLabel)
    }
}
