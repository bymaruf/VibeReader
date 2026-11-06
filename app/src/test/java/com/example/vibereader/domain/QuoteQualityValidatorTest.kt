package com.example.vibereader.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteQualityValidatorTest {
    @Test
    fun acceptsCompleteMeaningfulQuote() {
        assertTrue(
            QuoteQualityValidator.isValidQuote(
                "“The only way out is through the darkness.”",
            ),
        )
    }

    @Test
    fun rejectsShortOrSingleWordText() {
        assertFalse(QuoteQualityValidator.isValidQuote("Bright idea."))
        assertFalse(QuoteQualityValidator.isValidQuote("A single word"))
    }

    @Test
    fun rejectsLowercaseAndConjunctiveFragments() {
        assertFalse(
            QuoteQualityValidator.isValidQuote(
                "and after realizing the world had changed forever.",
            ),
        )
    }

    @Test
    fun rejectsDanglingOrEllipticalText() {
        assertFalse(QuoteQualityValidator.isValidQuote("We walked through the forest and"))
        assertFalse(QuoteQualityValidator.isValidQuote("We walked through the forest..."))
    }
}
