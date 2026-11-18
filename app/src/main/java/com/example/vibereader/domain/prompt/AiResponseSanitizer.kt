package com.example.vibereader.domain.prompt

import java.util.Locale

/**
 * Single sanitizer for every visible AI response.
 *
 * Providers leak internal reasoning in several different dialects:
 * `<think>...</think>`, `<thought>...</thought>`, `[THOUGHT]...[/THOUGHT]`,
 * partial/unclosed tags, Markdown fences around JSON, and conversational
 * preambles such as "Here are some quotes...". The Reader assistant and the
 * Wallpaper Studio both call this object before showing or parsing a response,
 * so no reasoning trace can reach a quote card or the chat sheet.
 *
 * The sanitizer deliberately does not try to rewrite the model's actual answer:
 * it only removes envelopes, tag pairs, fences, and obvious chatter.
 */
object AiResponseSanitizer {

    private val THINK_BLOCK = Regex(
        "<think\\b[^>]*>.*?</think\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THINK_UNCLOSED = Regex(
        "<think\\b[^>]*>.*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THINK_TAG = Regex("</?think\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val THOUGHT_TAG_BLOCK = Regex(
        "<thought\\b[^>]*>.*?</thought\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THOUGHT_TAG_UNCLOSED = Regex(
        "<thought\\b[^>]*>.*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THOUGHT_TAG = Regex("</?thought\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val BRACKET_THOUGHT_BLOCK = Regex(
        "\\[THOUGHT].*?\\[/THOUGHT]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val BRACKET_THOUGHT_UNCLOSED = Regex(
        "\\[THOUGHT].*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val BRACKET_THOUGHT_TAG = Regex("\\[/?THOUGHT]", RegexOption.IGNORE_CASE)

    private val FENCE_OPEN = Regex(
        "^\\s*```(?:json|javascript|markdown|md|text|txt)?\\s*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val FENCE_ANY = Regex("```(?:json|javascript|markdown|md|text|txt)?", RegexOption.IGNORE_CASE)

    private val LEADING_CHATTER = listOf(
        Regex("(?i)^\\s*(?:sure|certainly|of course|absolutely)\\b[^\\n]{0,180}$"),
        Regex("(?i)^\\s*(?:here (?:are|is)|below (?:are|is)|these are)\\b[^\\n]{0,180}$"),
        Regex("(?i)^\\s*(?:let me|i will|i'll)\\b[^\\n]{0,180}$"),
        Regex("(?i)^\\s*(?:the following|following are)\\b[^\\n]{0,180}$"),
        Regex("(?i)^\\s*as requested\\b[^\\n]{0,180}$"),
    )
    private val TRAILING_CHATTER = listOf(
        Regex("(?i)^\\s*(?:let me know|i hope this helps|hope this helps|feel free to)\\b[^\\n]*$"),
        Regex("(?i)^\\s*(?:would you like|if you (?:want|need|would like))\\b[^\\n]*$"),
        Regex("(?i)^\\s*(?:this should|that should|these should)\\b[^\\n]*$"),
    )
    /**
     * Same chatter written on the answer's first line, e.g.
     * `Sure! Here are the key insights: The passage ...`.
     */
    private val INLINE_LEADING_CHATTER = Regex(
        "(?is)^\\s*(?:(?:sure|certainly|of course|absolutely)[!,.]?\\s*)?" +
            "(?:here (?:are|is)|below (?:are|is)|these are|the following(?: are)?)" +
            "[^\\n:]{0,160}:\\s*",
    )
    /** Sign-off appended to the final answer line instead of its own line. */
    private val INLINE_TRAILING_CHATTER = Regex(
        "(?is)\\s*(?:i hope this helps|hope this helps|let me know if[^.!\\n]*|" +
            "would you like me to[^.!\\n]*)[.!]*\\s*$",
    )

    private val HEADING_PREFIX = Regex("""^\s{0,3}#{1,6}\s+""")
    private val BLOCKQUOTE_PREFIX = Regex("""^\s{0,3}>\s?""")
    private val BULLET_PREFIX = Regex("""^\s*[-*+]\s+""")
    private val INLINE_CODE = Regex("""`([^`]*)`""")
    private val BOLD_STAR = Regex("""\*\*([^*]+)\*\*""")
    private val BOLD_UNDERSCORE = Regex("""__([^_]+)__""")
    private val ITALIC_STAR_MARKERS = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
    private val ITALIC_UNDERSCORE_MARKERS = Regex("""(?<!_)_([^_\n]+)_(?!_)""")
    private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val EXCESS_BLANK_LINES = Regex("""\n{3,}""")

    /** Artifact markers that must never survive into a wallpaper quote. */
    private val QUOTE_ARTIFACT = Regex(
        "(?is)(?:</?think\\b|</?thought\\b|\\[/?THOUGHT]|<[a-z][^>]*>|```|" +
            "\\{\\s*\"(?:quote|text|content)\"|\\b(?:reasoning_content|reasoning)\\b\\s*[:=])",
    )
    /**
     * Code detection requires real syntax, not just a keyword. An earlier
     * version rejected any sentence beginning with "Object ..." because it
     * treated `object Name` as a Kotlin declaration, which silently discarded
     * perfectly good book quotes.
     */
    private val CODE_LIKE = Regex(
        "(?is)(?:" +
            "\\b(?:fun|val|var)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*[=(:]" +
            "|\\b(?:class|interface)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*[({<]" +
            "|\\bobject\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\{" +
            "|\\b(?:import|package)\\s+[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+" +
            "|\\b(?:public|private|protected)\\s+(?:static\\s+)?" +
            "(?:class|interface|enum|void|String|int|boolean)\\b" +
            "|\\bJSON\\s+array\\b" +
            ")",
    )

    /**
     * Sanitizes text for display or quote extraction.
     *
     * @param extractFencedPayload when true, a fenced JSON/text block is
     * preferred over prose outside the fence. Quote parsing uses this; the
     * Reader assistant uses false so ordinary Markdown answers keep their
     * surrounding text.
     */
    fun sanitize(
        raw: String,
        extractFencedPayload: Boolean = false,
        stripChatter: Boolean = true,
    ): String {
        if (raw.isBlank()) return ""

        var text = stripThinking(raw)
        if (extractFencedPayload) {
            extractFirstFence(text)?.let { fenced ->
                if (fenced.isNotBlank()) text = fenced
            }
        }
        text = stripFences(text)
        if (stripChatter) {
            // Inline cleanup runs first: a one-line "Sure! Here are ...: answer"
            // would otherwise be removed wholesale as a chatter line.
            text = text
                .replace(INLINE_LEADING_CHATTER, "")
                .replace(INLINE_TRAILING_CHATTER, "")
            text = stripConversationalChatter(text)
        }
        return text.trim()
    }

    /**
     * Display cleanup for Reader AI answers. In addition to reasoning and
     * envelope removal, this strips Markdown syntax so insights read as clean
     * prose in the assistant sheet. Quote extraction deliberately uses
     * [sanitize] instead: a quote may legitimately contain punctuation that
     * looks like Markdown.
     */
    fun sanitizeForDisplay(raw: String): String {
        val text = sanitize(raw)
        if (text.isBlank()) return ""
        val cleaned = text.lineSequence()
            .joinToString("\n") { line ->
                line
                    .replace(HEADING_PREFIX, "")
                    .replace(BLOCKQUOTE_PREFIX, "")
                    .replace(BULLET_PREFIX, "• ")
                    .replace(INLINE_CODE, "$1")
                    .replace(BOLD_STAR, "$1")
                    .replace(BOLD_UNDERSCORE, "$1")
                    .replace(ITALIC_STAR_MARKERS, "$1")
                    .replace(ITALIC_UNDERSCORE_MARKERS, "$1")
                    .replace(MARKDOWN_LINK, "$1")
            }
        return cleaned.replace(EXCESS_BLANK_LINES, "\n\n").trim()
    }

    /** True when a candidate quote still looks like model/tooling output. */
    fun isArtifact(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (QUOTE_ARTIFACT.containsMatchIn(trimmed)) return true
        if (CODE_LIKE.containsMatchIn(trimmed)) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower.startsWith("here are ") ||
            lower.startsWith("here is ") ||
            lower.startsWith("sure,") ||
            lower.startsWith("certainly,") ||
            lower.contains("json array") ||
            lower.contains("thinking process")
    }

    /** True when the model returned the unknown-book sentinel, including fences. */
    fun containsUnknownBookSentinel(text: String): Boolean =
        text.contains("UNKNOWN_BOOK", ignoreCase = true)

    private fun stripThinking(raw: String): String = raw
        .replace(THINK_BLOCK, " ")
        .replace(THINK_UNCLOSED, " ")
        .replace(THINK_TAG, " ")
        .replace(THOUGHT_TAG_BLOCK, " ")
        .replace(THOUGHT_TAG_UNCLOSED, " ")
        .replace(THOUGHT_TAG, " ")
        .replace(BRACKET_THOUGHT_BLOCK, " ")
        .replace(BRACKET_THOUGHT_UNCLOSED, " ")
        .replace(BRACKET_THOUGHT_TAG, " ")

    private fun extractFirstFence(text: String): String? {
        val opening = FENCE_ANY.find(text) ?: return null
        val contentStart = opening.range.last + 1
        val closing = text.indexOf("```", contentStart)
        if (closing < 0) return null
        return text.substring(contentStart, closing)
    }

    private fun stripFences(text: String): String = text
        .replace(FENCE_OPEN, "")
        .replace(FENCE_ANY, "")
        .replace(Regex("(?m)^\\s*```\\s*$"), "")
        .trim()

    private fun stripConversationalChatter(text: String): String {
        var lines = text.lines().toMutableList()

        // Remove leading chatter lines only while they remain at the top.
        while (lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first.isEmpty()) {
                lines.removeAt(0)
                continue
            }
            if (LEADING_CHATTER.any { it.matches(first) }) {
                lines.removeAt(0)
                continue
            }
            break
        }

        // Remove trailing sign-off lines.
        while (lines.isNotEmpty()) {
            val last = lines.last().trim()
            if (last.isEmpty()) {
                lines.removeAt(lines.lastIndex)
                continue
            }
            if (TRAILING_CHATTER.any { it.matches(last) }) {
                lines.removeAt(lines.lastIndex)
                continue
            }
            break
        }

        return lines.joinToString("\n")
    }
}
