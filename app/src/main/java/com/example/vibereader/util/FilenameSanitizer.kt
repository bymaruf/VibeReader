package com.example.vibereader.util

/**
 * Converts raw file names and download-site tags into a human-readable book
 * title before the value reaches the library or the wallpaper canvas.
 *
 * Examples:
 *  - `Atomic habits \( PDFDrive.com \).pdf` -> `Atomic habits`
 *  - `Deep Work (PDFDrive.com) [EPUB].epub` -> `Deep Work`
 *  - `Sapiens. A Brief History of Humankind.pdf` -> `Sapiens. A Brief History of Humankind`
 */
object FilenameSanitizer {
    private val extensionPattern = Regex(
        """(?i)\.(pdf|epub|mobi|azw3?|fb2|djvu|txt|rtf|docx?|odt)$""",
    )
    private val bracketedPattern = Regex("""[\(\[\{].*?[\)\]\}]""")
    private val domainPattern = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:https?://)?(?:www\.)?""" +
            """[\p{L}\p{N}][\p{L}\p{N}-]*(?:\.[\p{L}\p{N}-]+)*\.""" +
            """(?:com|net|org|info|biz|io|co|me|xyz|top|club|site|online|store|app|dev|pdf|epub)""" +
            """(?:/[^\s]*)?(?![\p{L}\p{N}])""",
    )
    private val releaseTagPattern = Regex(
        """(?i)\b(?:pdfdrive|pdf drive|z-?lib(?:rary)?|libgen|oceanofpdf|epdf|pdfroom|""" +
            """bookfi|manybooks|freebooks|ebookbrowse|anna'?s archive)\b""",
    )
    private val whitespacePattern = Regex("""\s+""")
    private val trailingSeparators = charArrayOf(
        ' ', '-', '_', '.', ',', ';', ':', '·', '|', '/',
    )

    /**
     * Returns [rawName] without its extension, bracketed metadata, download-site
     * tags, escape characters or duplicated whitespace.
     *
     * No fallback title is invented here; callers decide what an empty result
     * should display (for example `VibeReader` or `Untitled Book`).
     */
    fun cleanBookTitle(rawName: String): String {
        if (rawName.isBlank()) return ""
        var cleaned = rawName.trim()
        cleaned = bracketedPattern.replace(cleaned, " ")
        cleaned = cleaned.replace('\\', ' ')
        cleaned = extensionPattern.replace(cleaned, "")
        cleaned = domainPattern.replace(cleaned, " ")
        cleaned = releaseTagPattern.replace(cleaned, " ")
        cleaned = cleaned.replace('_', ' ')
        // A tag can leave the extension at the end (e.g. "Title.pdf - PDFDrive.com").
        cleaned = extensionPattern.replace(cleaned, "")
        cleaned = whitespacePattern.replace(cleaned, " ")
        return cleaned.trim(*trailingSeparators)
    }
}
