package com.example.vibereader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryRouterTest {
    @Test
    fun acceptsSingleWordsAndPunctuationAttachedToOneWord() {
        assertTrue(DictionaryRouter.isSingleWord("serendipity"))
        assertTrue(DictionaryRouter.isSingleWord("  word, "))
        assertTrue(DictionaryRouter.isSingleWord("l’amour"))
    }

    @Test
    fun rejectsBlankAndMultiWordSelections() {
        assertFalse(DictionaryRouter.isSingleWord(""))
        assertFalse(DictionaryRouter.isSingleWord("   "))
        assertFalse(DictionaryRouter.isSingleWord("two words"))
        assertFalse(DictionaryRouter.isSingleWord("line\nbreak"))
    }

    @Test
    fun buildsTrimmedDefinitionQuery() {
        assertEquals("define word", DictionaryRouter.definitionQuery("  word  "))
    }
}
