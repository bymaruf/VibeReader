package com.example.vibereader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    ],
    version = 9,
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
                )
                    .build()
                    .also { instance = it }
            }
    }
}
