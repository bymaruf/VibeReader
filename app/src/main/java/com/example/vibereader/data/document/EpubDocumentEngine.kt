package com.example.vibereader.data.document

import com.example.vibereader.domain.model.EBookChapter
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB engine built on the container/OPF metadata and a ZIP + HTML extractor.
 *
 * Metadata is read from `META-INF/container.xml` and the package document's DC
 * tags. Chapters follow the OPF spine order with an HTML fallback for books
 * that ship an incomplete manifest. No chapter text is needed for indexing
 * metadata, but EPUB text is cheap enough to persist at import time.
 */
class EpubDocumentEngine : EBookParserEngine {
    override val format: EBookFormat = EBookFormat.EPUB

    override fun readMetadata(file: File, fallbackTitle: String): EBookMetadata =
        ZipFile(file).use { epub ->
            val packageInfo = readPackage(epub)
            val metadata = packageInfo.document.select("*").firstOrNull {
                it.normalizedTagName() == "metadata"
            }
            EBookMetadata(
                title = metadata?.firstDescendantText("title")
                    ?: fallbackTitle.withoutExtension(),
                author = metadata?.firstDescendantText("creator"),
                language = metadata?.firstDescendantText("language"),
                identifier = metadata?.firstDescendantText("identifier"),
            )
        }

    override fun readChapters(file: File): List<EBookChapter> =
        ZipFile(file).use { epub ->
            val packageInfo = readPackage(epub)
            val chapterPaths = resolveChapterPaths(epub, packageInfo)
            if (chapterPaths.none { epub.getEntry(it) != null }) {
                throw IOException("No readable chapters were found in this EPUB.")
            }
            if (chapterPaths.size > MAX_CHAPTERS) {
                throw IOException("This EPUB contains too many chapters to import.")
            }

            var totalTextBytes = 0L
            val chapters = mutableListOf<EBookChapter>()
            for (path in chapterPaths) {
                val entry = epub.getEntry(path) ?: continue
                if (!isHtmlPath(path)) continue
                val chapterDocument = parseChapter(readEntry(epub, entry))
                val text = chapterDocument.body()?.text()
                    .orEmpty()
                    .ifBlank { chapterDocument.text() }
                    .trim()
                if (text.isBlank()) continue
                totalTextBytes += text.toByteArray(StandardCharsets.UTF_8).size
                if (totalTextBytes > MAX_TOTAL_TEXT_BYTES) {
                    throw IOException("The extracted EPUB text is too large to import.")
                }
                val chapterTitle = chapterDocument.selectFirst("h1, h2, title")
                    ?.text()
                    ?.takeIf(String::isNotBlank)
                    ?: "Chapter ${chapterPaths.takeWhile { it != path }.count(::isHtmlPath) + 1}"
                chapters += EBookChapter(
                    chapterIndex = chapters.size,
                    title = chapterTitle,
                    text = text,
                )
            }
            if (chapters.isEmpty()) {
                throw IOException("No readable text was found in this EPUB.")
            }
            chapters
        }

    override fun readChapterText(file: File, chapterIndex: Int): String {
        if (chapterIndex < 0) return ""
        return readChapters(file).firstOrNull { it.chapterIndex == chapterIndex }?.text.orEmpty()
    }

    override fun extractCover(file: File, destination: File): Boolean =
        try {
            val coverBytes = FileInputStream(file).use { input ->
                EpubReader().readEpub(input).coverImage?.data
            } ?: throw IOException("The EPUB does not contain a cover image.")
            destination.outputStream().buffered().use { output ->
                output.write(coverBytes)
            }
            true
        } catch (error: Exception) {
            false
        }

    private fun readPackage(epub: ZipFile): EpubPackageInfo {
        val containerEntry = epub.getEntry(CONTAINER_PATH)
            ?: throw IOException("This EPUB has no package descriptor.")
        val container = parseXml(readEntry(epub, containerEntry))
        val rootFile = container.select("*").firstOrNull {
            it.normalizedTagName() == "rootfile"
        } ?: throw IOException("This EPUB has an invalid package descriptor.")
        val packagePath = rootFile.attr("full-path")
        val packageEntry = epub.getEntry(packagePath)
            ?: throw IOException("This EPUB package file is missing.")
        val packageDocument = parseXml(readEntry(epub, packageEntry))
        return EpubPackageInfo(packageDocument, packagePath)
    }

    private fun resolveChapterPaths(epub: ZipFile, packageInfo: EpubPackageInfo): List<String> {
        val manifestItems = packageInfo.document.select("*").filter {
            it.normalizedTagName() == "item"
        }
        val manifest = buildMap {
            manifestItems.forEach { item ->
                put(item.attr("id"), item.attr("href"))
            }
        }
        val orderedPaths = mutableListOf<String>()
        val spineItems = packageInfo.document.select("*").filter {
            it.normalizedTagName() == "itemref"
        }
        for (item in spineItems) {
            manifest[item.attr("idref")]?.let { href ->
                resolveArchivePath(packageInfo.packagePath, href)?.let(orderedPaths::add)
            }
        }
        val fallbackPaths = listZipEntryPaths(epub).filter(::isHtmlPath)
        return (orderedPaths + fallbackPaths).distinct()
    }

    private fun parseChapter(bytes: ByteArray): Document {
        val document = Jsoup.parse(
            ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8.name(),
            "",
            Parser.xmlParser(),
        )
        document.select("script, style, link[rel=stylesheet], nav").remove()
        document.select("*").forEach { element ->
            element.removeAttr("style")
            element.removeAttr("class")
        }
        return document
    }

    private fun parseXml(bytes: ByteArray): Document = Jsoup.parse(
        ByteArrayInputStream(bytes),
        StandardCharsets.UTF_8.name(),
        "",
        Parser.xmlParser(),
    )

    private fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray {
        if (entry.size > MAX_ARCHIVE_ENTRY_BYTES) {
            throw IOException("An EPUB component is too large to import.")
        }
        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ARCHIVE_ENTRY_BYTES) {
                    throw IOException("An EPUB component is too large to import.")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun resolveArchivePath(packagePath: String, href: String): String? {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val resolved = java.net.URI(packagePath).resolve(cleanHref).normalize().path
            ?.removePrefix("/")
            ?: return null
        if (resolved.isBlank() || resolved == ".." || resolved.startsWith("../")) return null
        return resolved
    }

    private fun org.jsoup.nodes.Element.firstDescendantText(localName: String): String? =
        select("*")
            .firstOrNull { it.normalizedTagName() == localName }
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun org.jsoup.nodes.Element.normalizedTagName(): String =
        tagName().substringAfter(':').lowercase()

    private fun isHtmlPath(path: String): Boolean =
        path.endsWith(".xhtml", true) || path.endsWith(".html", true) ||
            path.endsWith(".htm", true)

    private fun listZipEntryPaths(zip: ZipFile): List<String> {
        val paths = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (!name.endsWith("/")) paths += name
            if (paths.size > MAX_ARCHIVE_ENTRIES) {
                throw IOException("This EPUB contains too many archive entries.")
            }
        }
        return paths
    }

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private data class EpubPackageInfo(
        val document: Document,
        val packagePath: String,
    )

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_ARCHIVE_ENTRY_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_TEXT_BYTES = 128L * 1024 * 1024
        const val MAX_CHAPTERS = 2_000
        const val MAX_ARCHIVE_ENTRIES = 20_000
    }
}
