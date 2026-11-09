package com.example.vibereader.util

import com.example.vibereader.data.room.BookEntity

/**
 * Display-safe title for a persisted book row.
 *
 * Imports are sanitized before insertion, but rows created before
 * [FilenameSanitizer] existed may still contain raw download filenames. They
 * are cleaned again at render time without rewriting the database.
 */
fun BookEntity.displayTitle(): String =
    FilenameSanitizer.cleanBookTitle(title).ifBlank { "Untitled Book" }
