package com.example.vibereader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PageSliderOverlay(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onBackground,
    background: Color = MaterialTheme.colorScheme.background,
) {
    if (totalPages <= 0) return

    val lastPage = totalPages - 1
    var sliderValue by remember(totalPages) {
        mutableFloatStateOf(currentPage.coerceIn(0, lastPage).toFloat())
    }
    var isScrubbing by remember { mutableStateOf(false) }
    var pendingPage by remember { mutableIntStateOf(-1) }

    LaunchedEffect(currentPage, isScrubbing, pendingPage, totalPages) {
        val selectedPage = currentPage.coerceIn(0, lastPage)
        if (pendingPage >= 0 && selectedPage == pendingPage) {
            sliderValue = selectedPage.toFloat()
            pendingPage = -1
            isScrubbing = false
        } else if (pendingPage < 0 && !isScrubbing) {
            sliderValue = selectedPage.toFloat()
        }
    }

    fun jumpTo(page: Int) {
        val target = page.coerceIn(0, lastPage)
        sliderValue = target.toFloat()
        if (target == currentPage) {
            pendingPage = -1
            isScrubbing = false
        } else {
            pendingPage = target
            isScrubbing = true
            onPageChange(target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background.copy(alpha = 0.96f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${sliderValue.roundToInt().coerceIn(0, lastPage) + 1} / $totalPages",
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(foreground.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { jumpTo(0) },
                enabled = currentPage > 0,
                modifier = Modifier.semantics { contentDescription = "Jump to first page" },
            ) {
                Text("1", color = foreground, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = sliderValue.coerceIn(0f, lastPage.coerceAtLeast(1).toFloat()),
                onValueChange = { value ->
                    isScrubbing = true
                    pendingPage = -1
                    sliderValue = value
                },
                onValueChangeFinished = {
                    jumpTo(sliderValue.roundToInt())
                },
                valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
                enabled = totalPages > 1,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = foreground,
                    activeTrackColor = foreground,
                    inactiveTrackColor = foreground.copy(alpha = 0.24f),
                    disabledThumbColor = foreground.copy(alpha = 0.5f),
                    disabledActiveTrackColor = foreground.copy(alpha = 0.5f),
                    disabledInactiveTrackColor = foreground.copy(alpha = 0.16f),
                ),
            )
            IconButton(
                onClick = { jumpTo(lastPage) },
                enabled = currentPage < lastPage,
                modifier = Modifier.semantics { contentDescription = "Jump to last page" },
            ) {
                Text("N", color = foreground, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
