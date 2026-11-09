package com.example.vibereader.data.room

import com.example.vibereader.domain.model.EBookLocation
import com.example.vibereader.domain.model.EBookQuote

/**
 * Maps persisted highlights and quotes onto the format-independent quote model
 * used by AI wallpaper curation.
 */
fun HighlightEntity.toEBookQuote(): EBookQuote = EBookQuote(
    selectedText = selectedText,
    location = EBookLocation(
        bookId = bookId,
        chapterIndex = chapterIndex,
        characterOffset = characterOffset,
        endCharacterOffset = endCharacterOffset,
    ),
    chapterTitle = note,
    createdAt = createdAt,
)

fun QuoteEntity.toEBookQuote(): EBookQuote = EBookQuote(
    selectedText = selectedText,
    location = EBookLocation(
        bookId = bookId,
        chapterIndex = chapterIndex,
        characterOffset = characterOffset,
        endCharacterOffset = endCharacterOffset,
    ),
    chapterTitle = chapter,
    createdAt = createdAt,
)
