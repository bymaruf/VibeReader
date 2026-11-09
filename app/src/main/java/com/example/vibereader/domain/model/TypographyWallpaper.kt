package com.example.vibereader.domain.model

import android.graphics.Bitmap

enum class WallpaperLayoutVariant(val label: String) {
    MINIMAL("Minimal"),
    EMPHASIS("Emphasis"),
    OVERLAY("Overlay"),
    SPLIT("Split"),
    CLASSIC("Classic");
}

/** Light/dark canvas theme used when painting the typography wallpaper. */
enum class WallpaperTheme(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
}

/** A single typography wallpaper design generated from a book quote. */
data class TypographyWallpaper(
    val id: String,
    val quote: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val progressText: String?,
    val designIndex: Int,
    val layoutVariant: WallpaperLayoutVariant,
    val theme: WallpaperTheme = WallpaperTheme.DARK,
    val bitmap: Bitmap? = null,
)
