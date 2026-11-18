package com.example.vibereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vibereader.domain.prompt.AiResponseSanitizer
import com.example.vibereader.model.ReaderTtsVoice
import com.example.vibereader.ui.reader.AiTaskResult
import com.example.vibereader.util.FreeAiTtsPlayer
import com.example.vibereader.util.TtsManager

private enum class AssistantScope(val label: String) {
    SELECTION("Selection"),
    CURRENT_PAGE("Current page"),
    CURRENT_CHAPTER("Chapter"),

    /**
     * Always available. Used automatically when there is no selection and no
     * current page/chapter text, so Ask Book never depends on a passage being
     * open before the user can type a question.
     */
    ENTIRE_BOOK("Entire book"),
}

/**
 * Internal request used by the "Entire book" Summarize button. The ViewModel
 * intercepts it before normal question handling and sends only title/author.
 */
internal const val ENTIRE_BOOK_SUMMARY_COMMAND = "__vibereader_entire_book_summary__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantBottomSheet(
    ttsManager: TtsManager,
    edgeTtsPlayer: FreeAiTtsPlayer,
    ttsVoice: ReaderTtsVoice,
    onSpeakText: (String) -> Unit,
    selectionText: String?,
    pageLabel: String,
    currentPageIndex: Int?,
    pageText: String?,
    chapterTitle: String?,
    chapterText: String?,
    isPdf: Boolean,
    isWorking: Boolean,
    onSummarizeSelection: () -> Unit,
    onExplainSelection: () -> Unit,
    onExtractQuote: () -> Unit,
    onSummarizePage: () -> Unit,
    onSummarizeChapter: () -> Unit,
    onSpeakPage: () -> Unit,
    onAskBookAi: (String) -> Unit,
    aiResult: AiTaskResult?,
    onDismissAiResult: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSpeaking by ttsManager.isSpeaking
    val isPaused by ttsManager.isPaused
    val isEdgePlaying by edgeTtsPlayer.isPlaying
    val isEdgePaused by edgeTtsPlayer.isPaused
    val isEdgeLoading by edgeTtsPlayer.isLoading
    val isNativeFallback by edgeTtsPlayer.isUsingNativeFallback
    val useNativePlayback = ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM || isNativeFallback
    val pitch by ttsManager.pitch
    val speed by ttsManager.speed
    val selectionAvailable = !selectionText.isNullOrBlank()
    val currentPageAvailable = isPdf && currentPageIndex != null
    val chapterAvailable = !isPdf && !chapterText.isNullOrBlank()
    val scopes = buildList {
        if (selectionAvailable) add(AssistantScope.SELECTION)
        if (currentPageAvailable) add(AssistantScope.CURRENT_PAGE)
        if (chapterAvailable) add(AssistantScope.CURRENT_CHAPTER)
        // Always offered (and used as the automatic fallback) so a question can
        // be asked even before any page/chapter text has loaded.
        add(AssistantScope.ENTIRE_BOOK)
    }
    var selectedScope by remember(
        selectionText,
        pageText,
        chapterText,
        pageLabel,
        chapterTitle,
        isPdf,
        currentPageIndex,
    ) {
        mutableStateOf(
            when {
                selectionAvailable -> AssistantScope.SELECTION
                currentPageAvailable -> AssistantScope.CURRENT_PAGE
                chapterAvailable -> AssistantScope.CURRENT_CHAPTER
                else -> AssistantScope.ENTIRE_BOOK
            },
        )
    }
    val activeScope = selectedScope.takeIf(scopes::contains) ?: scopes.first()
    val activeText = when (activeScope) {
        AssistantScope.SELECTION -> selectionText.orEmpty()
        AssistantScope.CURRENT_PAGE -> pageText.orEmpty()
        AssistantScope.CURRENT_CHAPTER -> chapterText.orEmpty()
        AssistantScope.ENTIRE_BOOK -> ""
    }
    val activeLabel = when (activeScope) {
        AssistantScope.SELECTION -> "Selected passage"
        AssistantScope.CURRENT_PAGE -> pageLabel
        AssistantScope.CURRENT_CHAPTER -> chapterTitle ?: pageLabel
        AssistantScope.ENTIRE_BOOK -> "Entire book"
    }
    val speechButtonLabel = when {
        (useNativePlayback && isSpeaking) ||
            (!useNativePlayback && isEdgePlaying) -> "Pause"
        (useNativePlayback && isPaused) ||
            (!useNativePlayback && isEdgePaused) -> "Resume"
        isEdgeLoading -> "Preparing…"
        else -> "Play"
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A concrete cap guarantees finite constraints even if a
                    // future sheet implementation measures with an unbounded
                    // height; verticalScroll throws in that case.
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text("BOOK ASSISTANT", style = MaterialTheme.typography.titleLarge)
            AudioPlayerOverlay(
                player = edgeTtsPlayer,
                onStop = {
                    edgeTtsPlayer.stop()
                    ttsManager.stop()
                },
            )
            selectionText?.takeIf(String::isNotBlank)?.let { context ->
                val snippet = context.take(CONTEXT_PREVIEW_CHARS)
                Text(
                    text = "Context: \"$snippet${if (context.length > snippet.length) "..." else ""}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (
                activeScope == AssistantScope.CURRENT_PAGE &&
                currentPageIndex != null &&
                !pageText.isNullOrBlank()
            ) {
                val snippet = pageText.take(PAGE_CONTEXT_CHARS)
                Text(
                    text = "Processing Page $currentPageIndex " +
                        "(${snippet}${if (pageText.length > snippet.length) "..." else ""})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (
                activeScope == AssistantScope.CURRENT_CHAPTER &&
                !chapterText.isNullOrBlank()
            ) {
                val snippet = chapterText.take(PAGE_CONTEXT_CHARS)
                Text(
                    text = "Processing Chapter ${chapterTitle ?: ""} " +
                        "(${snippet}${if (chapterText.length > snippet.length) "..." else ""})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text("Choose a scope", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                scopes.forEachIndexed { index, scope ->
                    SegmentedButton(
                        selected = activeScope == scope,
                        onClick = { selectedScope = scope },
                        shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                    ) {
                        Text(scope.label)
                    }
                }
            }
            if (activeScope == AssistantScope.ENTIRE_BOOK) {
                Text(
                    text = "No passage selected — Ask Book will answer from the whole book.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (activeScope == AssistantScope.SELECTION && selectionText != null) {
                Text(
                    selectionText.take(MAX_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("AI actions", style = MaterialTheme.typography.labelLarge)
            Button(
                onClick = {
                    when (activeScope) {
                        AssistantScope.SELECTION -> onSummarizeSelection()
                        AssistantScope.CURRENT_PAGE -> onSummarizePage()
                        AssistantScope.CURRENT_CHAPTER -> onSummarizeChapter()
                        AssistantScope.ENTIRE_BOOK -> onAskBookAi(ENTIRE_BOOK_SUMMARY_COMMAND)
                    }
                },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isWorking) "Working…" else "Summarize")
            }
            var askQuestion by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = askQuestion,
                    onValueChange = { askQuestion = it },
                    label = { Text("Ask this book…") },
                    modifier = Modifier.weight(1f),
                    enabled = !isWorking,
                )
                Button(
                    onClick = {
                        if (askQuestion.isNotBlank()) {
                            onAskBookAi(askQuestion.trim())
                            askQuestion = ""
                        }
                    },
                    enabled = !isWorking && askQuestion.isNotBlank(),
                ) {
                    Text("Ask")
                }
            }
            aiResult?.let { result ->
                // The sheet body is already vertically scrollable. Nesting a
                // second Modifier.verticalScroll here (or below on the Text)
                // makes Compose measure it with infinite max height and throw
                // "Vertically scrollable component was measured with an
                // infinity maximum height constraints" as soon as a result
                // appears — the crash reported after pressing Summarize/Ask.
                val containerColor = if (result.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
                val contentColor = if (result.isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    contentColor = contentColor,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (result.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                        )
                        Text(
                            // Defence in depth: even a cached/replayed answer
                            // from an older app version is stripped of thinking
                            // tags, fences, Markdown, and conversational
                            // preambles here.
                            text = remember(result.content) {
                                AiResponseSanitizer.sanitizeForDisplay(result.content)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (result.isError && result.retry != null) {
                                TextButton(
                                    onClick = { result.retry.invoke() },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = contentColor,
                                    ),
                                ) {
                                    Text("Retry")
                                }
                            }
                            TextButton(
                                onClick = onDismissAiResult,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = contentColor,
                                ),
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
            if (activeScope == AssistantScope.SELECTION) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onExplainSelection,
                        enabled = !isWorking,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Explain")
                    }
                    TextButton(
                        onClick = onExtractQuote,
                        enabled = !isWorking,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Extract quote")
                    }
                }
            }

            Text("Listen · $activeLabel", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        when {
                            useNativePlayback && isSpeaking ->
                                ttsManager.pause()
                            useNativePlayback && isPaused ->
                                ttsManager.resume()
                            !useNativePlayback &&
                                (isEdgePlaying || isEdgePaused) ->
                                edgeTtsPlayer.togglePlayback()
                            activeText.isNotBlank() -> onSpeakText(activeText)
                            else -> onSpeakPage()
                        }
                    },
                    enabled = !isEdgeLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(speechButtonLabel)
                }
                TextButton(
                    onClick = {
                        edgeTtsPlayer.stop()
                        ttsManager.stop()
                    },
                    enabled = isSpeaking || isPaused || isEdgePlaying || isEdgePaused || isEdgeLoading,
                ) {
                    Text("Stop")
                }
            }
            if (useNativePlayback) {
                Text("Speed: ${"%.1f".format(speed)}×", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = speed,
                    onValueChange = ttsManager::setSpeed,
                    valueRange = 0.5f..2f,
                )
                Text("Pitch: ${"%.1f".format(pitch)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = pitch,
                    onValueChange = ttsManager::setPitch,
                    valueRange = 0.5f..2f,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
            }
        }
    }
}

private const val MAX_PREVIEW_CHARS = 600
private const val CONTEXT_PREVIEW_CHARS = 80
private const val PAGE_CONTEXT_CHARS = 50
