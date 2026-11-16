package com.example.vibereader.domain.prompt

import java.util.Locale

/**
 * Decides whether a question needs the exact passage the reader is looking at.
 *
 * The assistant sheet does not pass its selected scope to the ViewModel, so the
 * question itself is the only reliable signal. Questions that point at "this
 * passage", the current page/chapter, or a highlighted excerpt must use local
 * retrieval. Everything else can first try the zero-context metadata prompt and
 * only fall back to local retrieval when the model reports [UNKNOWN_BOOK].
 */
object BookQuestionScope {

    fun requiresPassageContext(question: String): Boolean {
        val normalized = question.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        if (normalized in SHORT_PASSAGE_PROMPTS) return true
        return PASSAGE_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    private val SHORT_PASSAGE_PROMPTS = setOf(
        "explain",
        "explain this",
        "explain that",
        "what does this mean",
        "what does that mean",
        "why",
        "how so",
    )

    private val PASSAGE_PATTERNS = listOf(
        Regex(
            """\b(?:this|that|these|those)\s+(?:current\s+)?""" +
                """(?:passage|paragraph|sentence|quote|excerpt|section|page|chapter|text|line|scene|part|portion|bit|moment)\b""",
        ),
        Regex(
            """\b(?:in|from|on|within)\s+(?:this|that|the current)\s+""" +
                """(?:passage|paragraph|sentence|section|page|chapter|text|scene|part|portion)\b""",
        ),
        Regex(
            """\b(?:the|this|that)\s+(?:current|selected|highlighted)\s+""" +
                """(?:passage|paragraph|sentence|quote|excerpt|section|page|chapter|text|line|scene|part|portion)\b""",
        ),
        Regex("""\b(?:highlighted|selected|underlined)\s+(?:text|passage|sentence|quote|portion|part)?\b"""),
        Regex("""\b(?:above|below|following)\s+(?:passage|text|paragraph|sentence|quote|excerpt|line)\b"""),
        Regex("""\bwhat\s+(?:does|do)\s+(?:this|that|it)\s+(?:mean|say|suggest|imply)\b"""),
        Regex("""\bwhat\s+is\s+(?:this|that)\s+(?:saying|about|referring to)\b"""),
        Regex("""\bexplain\s+(?:this|that|the\s+(?:passage|text|quote|line))\b"""),
        Regex("""\b(?:the\s+)?(?:author|writer|narrator|character)\s+(?:mean|means|suggest|suggests|imply|implies)\s+(?:by|with)\s+(?:this|that)\b"""),
        Regex("""\b(?:what happens|what happened|what is happening)\s+here\b"""),
        Regex("""\b(?:in this context|this part of the book|this bit|this line|this moment)\b"""),
    )
}
