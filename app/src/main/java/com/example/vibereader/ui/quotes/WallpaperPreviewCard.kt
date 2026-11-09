package com.example.vibereader.ui.quotes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vibereader.domain.model.TypographyWallpaper
import com.example.vibereader.domain.model.WallpaperLayoutVariant
import kotlin.math.abs

@Composable
fun WallpaperPreviewCard(
    wallpaper: TypographyWallpaper,
    isFocused: Boolean,
    pageOffset: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember { mutableStateOf(1f) }
    LaunchedEffect(isFocused) {
        scale.value = if (isFocused) 1.02f else 1f
    }

    val pageScale = 1f + (0.08f * (1f - abs(pageOffset)).coerceIn(0f, 1f))
    val elevation = if (isFocused) 8.dp else 4.dp
    val borderWidth = if (isFocused) 2f else 0f
    val cornerRadius = if (isFocused) 16.dp else 12.dp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        border = if (borderWidth > 0) {
            androidx.compose.foundation.BorderStroke(
                width = borderWidth.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 6.dp else 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            wallpaper.bitmap?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Typography wallpaper: ${wallpaper.quote.take(30)}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = pageScale
                            scaleY = pageScale
                        },
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0xFF000000.toInt()).copy(alpha = 0.85f),
                                    ),
                                ),
                            ),
                    ) {
                        Text(
                            text = "“${wallpaper.quote}”",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (wallpaper.bookTitle.isNotBlank()) {
                            Text(
                                text = "— ${wallpaper.bookTitle}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            } ?: run {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "Loading wallpaper…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                Text(
                    text = wallpaper.layoutVariant.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp),
                        ),
                )
            }
        }
    }
}
