package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val documentUri: String,
    val format: String,
    @ColumnInfo(defaultValue = "")
    val filePath: String = "",
    @ColumnInfo(defaultValue = "0")
    val fileSizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val sourceModifiedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val isDeviceScanned: Boolean = false,
    val scanSource: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val lastReadAt: Long = 0,
    val coverImagePath: String? = null,
)
