package com.example.vibereader.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeModelPolicyTest {
    @Test
    fun knownNonFreeCliModelIsRestricted() {
        assertTrue(OpenCodeModelPolicy.isRestrictedModel("big-pickle"))
    }

    @Test
    fun freeModelsStayVisibleButAreFlagged() {
        assertFalse(OpenCodeModelPolicy.isRestrictedModel("iceman-free"))
        assertFalse(OpenCodeModelPolicy.isRestrictedModel("deepseek-v4.1-flash-free"))
        assertTrue(OpenCodeModelPolicy.isFreeCliModel("iceman-free"))
        assertTrue(OpenCodeModelPolicy.isFreeCliModel("DeepSeek-V4.1-Flash-Free"))
    }

    @Test
    fun ordinaryOpenCodeModelsAreNotFlagged() {
        assertFalse(OpenCodeModelPolicy.isRestrictedModel("deepseek-v4.1-flash"))
        assertFalse(OpenCodeModelPolicy.isFreeCliModel("gpt-5-nano"))
        assertEquals("GPT-5 Nano", OpenCodeModelPolicy.displayName("gpt-5-nano", "GPT-5 Nano"))
    }

    @Test
    fun flaggedLabelContainsRequiredWarning() {
        val label = OpenCodeModelPolicy.displayName("iceman-free", "Iceman Free")
        assertTrue(label.startsWith("Iceman Free"))
        assertTrue(label.contains(OpenCodeModelPolicy.CLI_ONLY_WARNING))
        assertTrue(label.contains("CLI-only (may time out in mobile app)"))
    }

    @Test
    fun catalogMetadataCatchesFutureStealthModels() {
        assertTrue(
            OpenCodeModelPolicy.isRestrictedModel(
                id = "mystery-model",
                description = "Stealth model, free for a limited time",
            ),
        )
        assertTrue(
            OpenCodeModelPolicy.isRestrictedModel(
                id = "mystery-model",
                description = "CLI-only for now",
            ),
        )
        assertFalse(
            OpenCodeModelPolicy.isRestrictedModel(
                id = "mystery-free",
                description = "CLI-only for now",
            ),
        )
    }
}
