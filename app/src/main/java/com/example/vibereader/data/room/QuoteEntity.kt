package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "quotes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "chapterIndex"]),
    ],
)
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    @ColumnInfo(name = "text") val selectedText: String,
    val chapter: String? = null,
    /** Generic location: EPUB spine index or PDF page index. */
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    /** Generic location: character offset inside [chapterIndex]. */
    @ColumnInfo(defaultValue = "0") val characterOffset: Int = 0,
    @ColumnInfo(defaultValue = "0") val endCharacterOffset: Int = 0,
    /**
     * Human-in-the-loop gate: only quotes explicitly ticked by the user are
     * allowed to be rendered as wallpapers (carousel or background worker).
     */
    @ColumnInfo(defaultValue = "0") val isApprovedForWallpaper: Boolean = false,
    /**
     * Persisted origin of the quote. Older rows migrate to [QuoteSource.USER],
     * which keeps human-curated passages in the safe default bucket.
     */
    @ColumnInfo(defaultValue = "'USER'") val source: QuoteSource = QuoteSource.USER,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Backwards-compatible alias; the persisted column is still named `text`. */
    val text: String
        get() = selectedText
}
