package com.example.vibereader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vibereader.data.room.AiResponseDao
import com.example.vibereader.data.room.AiResponseEntity
import com.example.vibereader.data.room.BookDao
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.BookReadingThemeDao
import com.example.vibereader.data.room.BookReadingThemeEntity
import com.example.vibereader.data.room.BookChapterDao
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.data.room.HighlightDao
import com.example.vibereader.data.room.HighlightEntity
import com.example.vibereader.data.room.QuoteDao
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.ReadingProgressDao
import com.example.vibereader.data.room.ReadingProgressEntity
import com.example.vibereader.data.room.ReaderPreferenceDao
import com.example.vibereader.data.room.ReaderPreferenceEntity
import com.example.vibereader.data.room.AppThemePreferenceDao
import com.example.vibereader.data.room.AppThemePreferenceEntity

@Database(
    entities = [
        BookEntity::class,
        BookChapterEntity::class,
        QuoteEntity::class,
        HighlightEntity::class,
        ReadingProgressEntity::class,
        ReaderPreferenceEntity::class,
        AppThemePreferenceEntity::class,
        BookReadingThemeEntity::class,
        AiResponseEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class VibeReaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookChapterDao(): BookChapterDao
    abstract fun quoteDao(): QuoteDao
    abstract fun highlightDao(): HighlightDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun readerPreferenceDao(): ReaderPreferenceDao
    abstract fun appThemePreferenceDao(): AppThemePreferenceDao
    abstract fun bookReadingThemeDao(): BookReadingThemeDao
    abstract fun aiResponseDao(): AiResponseDao

    companion object {
        private const val DATABASE_NAME = "vibereader.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN filePath TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN fileSizeBytes INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_chapters (
                        bookId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        PRIMARY KEY(bookId, chapterIndex),
                        FOREIGN KEY(bookId) REFERENCES books(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_chapters_bookId ON book_chapters(bookId)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN sourceModifiedAt INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN isDeviceScanned INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE books ADD COLUMN scanSource TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reader_preferences (
                        id INTEGER NOT NULL PRIMARY KEY,
                        scrollMode TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_theme_preferences (
                        id INTEGER NOT NULL PRIMARY KEY,
                        themeOption TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverImagePath TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_reading_themes (
                        bookId TEXT NOT NULL PRIMARY KEY,
                        readingTheme TEXT NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN pageIndex INTEGER")
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN startCharOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN endCharOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN colorHex TEXT NOT NULL DEFAULT '#FFFF00'",
                )
                db.execSQL("ALTER TABLE highlights ADD COLUMN boundsJson TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_highlights_bookId_pageIndex " +
                        "ON highlights(bookId, pageIndex)",
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Universal highlight location. Existing PDF highlights are
                // backfilled from their page/character fields.
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN chapterIndex INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN characterOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE highlights ADD COLUMN endCharacterOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    UPDATE highlights SET
                        chapterIndex = COALESCE(pageIndex, 0),
                        characterOffset = CASE
                            WHEN startCharOffset != 0 THEN startCharOffset
                            ELSE startOffset
                        END,
                        endCharacterOffset = CASE
                            WHEN endCharOffset != 0 THEN endCharOffset
                            ELSE endOffset
                        END
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_highlights_bookId_chapterIndex " +
                        "ON highlights(bookId, chapterIndex)",
                )

                // Universal quote location.
                db.execSQL(
                    "ALTER TABLE quotes ADD COLUMN chapterIndex INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE quotes ADD COLUMN characterOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE quotes ADD COLUMN endCharacterOffset INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_quotes_bookId_chapterIndex " +
                        "ON quotes(bookId, chapterIndex)",
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Human-in-the-loop wallpaper curation: existing quotes start
                // unapproved until the user explicitly ticks them.
                db.execSQL(
                    "ALTER TABLE quotes ADD COLUMN isApprovedForWallpaper " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Persisted origin so user highlights and AI suggestions can be
                // listed separately. Existing rows are treated as user-saved,
                // the safest possible default.
                db.execSQL(
                    "ALTER TABLE quotes ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'",
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Historical repair: attempted to reclassify legacy AI quotes.
                // Its chapter==book.title heuristic can misclassify user-saved
                // quotes, so 13->14 reverses this specific unsafe change.
                db.execSQL(
                    """
                    UPDATE quotes
                    SET source = 'AI'
                    WHERE source = 'USER'
                        AND chapter IS NOT NULL
                        AND EXISTS (
                            SELECT 1
                            FROM books b
                            WHERE b.id = quotes.bookId
                                AND lower(trim(b.title)) = lower(trim(quotes.chapter))
                        )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reverse 12->13: "chapter == book title" is not a reliable marker
                // (manual saves may use the document/chapter title). Defaulting
                // ambiguous rows back to USER is safer; new AI fetches always set
                // source=AI explicitly via QuoteRepository.appendAiQuotes().
                db.execSQL(
                    """
                    UPDATE quotes
                    SET source = 'USER'
                    WHERE source = 'AI'
                        AND chapter IS NOT NULL
                        AND EXISTS (
                            SELECT 1
                            FROM books b
                            WHERE b.id = quotes.bookId
                                AND lower(trim(b.title)) = lower(trim(quotes.chapter))
                        )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Local AI-response cache: repeated questions, page summaries,
                // and quote batches are served without another provider call.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_response_cache (
                        bookId TEXT NOT NULL,
                        cacheKind TEXT NOT NULL,
                        queryHash TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(bookId, cacheKind, queryHash),
                        FOREIGN KEY(bookId) REFERENCES books(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_response_cache_bookId " +
                        "ON ai_response_cache(bookId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_response_cache_bookId_cacheKind " +
                        "ON ai_response_cache(bookId, cacheKind)",
                )
            }
        }

        @Volatile
        private var instance: VibeReaderDatabase? = null

        fun getInstance(context: Context): VibeReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VibeReaderDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
