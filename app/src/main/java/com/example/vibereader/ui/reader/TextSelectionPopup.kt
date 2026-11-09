package com.example.vibereader.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
    isHighlighted: Boolean,
    isSingleWord: Boolean,
    foreground: Color,
    background: Color,
    onSaveQuote: () -> Unit,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onDictionary: () -> Unit,
    onUnhighlight: () -> Unit,
    onHighlight: () -> Unit,
    onAskAi: () -> Unit,
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
            if (isHighlighted) {
                SelectionAction(
                    label = "Unhighlight",
                    enabled = !isWorking,
                    onClick = onUnhighlight,
                )
                SelectionAction(
                    label = "Ask AI",
                    enabled = !isWorking,
                    onClick = onAskAi,
                )
            } else {
                SelectionAction(
                    label = "Highlight",
                    enabled = !isWorking,
                    onClick = onHighlight,
                )
                SelectionAction(
                    label = "Ask AI",
                    enabled = !isWorking,
                    onClick = onAskAi,
                )
            }
            SelectionAction("Copy", enabled = !isWorking, onClick = onCopy)
            SelectionAction("🌐 Translate", enabled = !isWorking, onClick = onTranslate)
            if (isSingleWord) {
                SelectionAction(
                    label = "📖 Dictionary",
                    enabled = !isWorking,
                    onClick = onDictionary,
                )
            }
            SelectionAction("Save Quote", enabled = !isWorking, onClick = onSaveQuote)
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

/**
 * Opens the user's native text processor (Google Translate or the system
 * default translator) without touching the AI backend.
 */
fun launchNativeTranslator(context: Context, text: String) {
    try {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            type = "text/plain"
        }
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No translation app installed (e.g., Google Translate)",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
