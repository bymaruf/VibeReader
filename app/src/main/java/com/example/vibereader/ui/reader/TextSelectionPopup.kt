package com.example.vibereader.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TextSelectionPopup(
    modifier: Modifier = Modifier,
    isWorking: Boolean,
    foreground: Color,
    background: Color,
    onSaveQuote: () -> Unit,
    onHighlight: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(bottom = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (background == Color.Black) Color(0xFF171717) else background,
        contentColor = foreground,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            foreground.copy(alpha = 0.12f),
        ),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SelectionAction("Save", enabled = !isWorking, onClick = onSaveQuote)
            SelectionAction("Highlight", enabled = !isWorking, onClick = onHighlight)
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
