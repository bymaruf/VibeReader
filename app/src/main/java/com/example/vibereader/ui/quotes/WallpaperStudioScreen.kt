package com.example.vibereader.ui.quotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.domain.model.TypographyWallpaper

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WallpaperStudioScreen() {
    val context = LocalContext.current
    val database = VibeReaderDatabase.getInstance(context)
    val viewModel: WallpaperStudioViewModel = viewModel(
        factory = WallpaperStudioViewModel.Factory(context, database),
    )

    val wallpapers by viewModel.wallpapers.collectAsState(emptyList())
    val isGenerating by viewModel.isGenerating.collectAsState(false)
    val error by viewModel.error.collectAsState(null as String?)
    val currentBook by viewModel.currentBook.collectAsState(null as com.example.vibereader.data.room.BookEntity?)

    val pagerState = rememberPagerState(
        pageCount = { wallpapers.size },
        initialPage = 0,
    )

    LaunchedEffect(pagerState.currentPage, wallpapers.size) {
        if (wallpapers.size > 0 && pagerState.currentPage >= wallpapers.size - 2) {
            viewModel.generateBatch(wallpapers.size + 4)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadCurrentBook()
        if (wallpapers.isEmpty() && currentBook != null) {
            viewModel.generateBatch(8)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Wallpaper Studio")
                },
                actions = {
                    Button(
                        onClick = {
                            viewModel.generateBatch(8)
                        },
                        enabled = !isGenerating,
                    ) {
                        Text("🔄 Refresh Batch")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (currentBook == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Save a quote or highlight while reading to generate wallpapers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (wallpapers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Generating your wallpaper batch…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 32.dp),
                ) { page ->
                    val wallpaper = wallpapers[page]
                    WallpaperPreviewCard(
                        wallpaper = wallpaper,
                        isFocused = pagerState.currentPage == page,
                        pageOffset = 0f,
                        onClick = { viewModel.setActiveWallpaper(wallpaper) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val current = wallpapers[pagerState.currentPage]
                            viewModel.setActiveWallpaper(current)
                        },
                        enabled = !isGenerating,
                    ) {
                        Text("📱 Set as Active Wallpaper")
                    }
                    Button(
                        onClick = {
                            viewModel.generateBatch(8)
                        },
                        enabled = !isGenerating,
                    ) {
                        Text("🔄 Refresh Batch")
                    }
                }

                if (isGenerating) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                error?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
