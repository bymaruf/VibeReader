package com.example.vibereader.data.room

/**
 * Where a quote came from. Persisted as the enum name so user-highlighted
 * passages can always be separated from AI-generated suggestions.
 */
enum class QuoteSource {
    /** Saved by the reader from a manual text selection. */
    USER,

    /** Extracted or fetched by an AI provider. */
    AI,
}
