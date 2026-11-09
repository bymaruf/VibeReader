package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "highlights",
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
        Index(value = ["bookId", "pageIndex"]),
        Index(value = ["bookId", "chapterIndex"]),
    ],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    @ColumnInfo(name = "text") val selectedText: String,
    /** Generic location: EPUB spine index or PDF page index. */
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    /** Generic location: character offset inside [chapterIndex]. */
    @ColumnInfo(defaultValue = "0") val characterOffset: Int = 0,
    @ColumnInfo(defaultValue = "0") val endCharacterOffset: Int = 0,
    val startOffset: Int,
    val endOffset: Int,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val pageIndex: Int? = null,
    @ColumnInfo(defaultValue = "0") val startCharOffset: Int = 0,
    @ColumnInfo(defaultValue = "0") val endCharOffset: Int = 0,
    @ColumnInfo(defaultValue = "'#FFFF00'") val colorHex: String = "#FFFF00",
    val boundsJson: String? = null,
) {
    /** Backwards-compatible alias; the persisted column is still named `text`. */
    val text: String
        get() = selectedText
}
