package com.example.vibereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vibereader.model.ReaderTtsVoice
import com.example.vibereader.util.FreeAiTtsPlayer
import com.example.vibereader.util.TtsManager

private enum class AssistantScope(val label: String) {
    SELECTION("Selection"),
    CURRENT_PAGE("Current page"),
    CURRENT_CHAPTER("Chapter"),
}

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
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    onTranslateSelection: (String) -> Unit,
    onTranslatePage: (String) -> Unit,
    onTranslateChapter: (String) -> Unit,
    onExtractQuote: () -> Unit,
    onSummarizePage: () -> Unit,
    onSummarizeChapter: () -> Unit,
    onSpeakPage: () -> Unit,
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
    val chapterAvailable = !chapterText.isNullOrBlank()
    val scopes = buildList {
        if (selectionAvailable) add(AssistantScope.SELECTION)
        if (isPdf) {
            add(AssistantScope.CURRENT_PAGE)
        } else if (chapterAvailable) {
            add(AssistantScope.CURRENT_CHAPTER)
        }
    }
    var selectedScope by remember(selectionText, pageLabel, chapterTitle) {
        mutableStateOf(
            if (selectionAvailable) AssistantScope.SELECTION
            else if (isPdf) AssistantScope.CURRENT_PAGE
            else AssistantScope.CURRENT_CHAPTER,
        )
    }
    val activeScope = selectedScope.takeIf(scopes::contains) ?: scopes.firstOrNull()
    val activeText = when (activeScope) {
        AssistantScope.SELECTION -> selectionText.orEmpty()
        AssistantScope.CURRENT_PAGE -> pageText.orEmpty()
        AssistantScope.CURRENT_CHAPTER -> chapterText.orEmpty()
        null -> ""
    }
    val activeLabel = when (activeScope) {
        AssistantScope.SELECTION -> "Selected passage"
        AssistantScope.CURRENT_PAGE -> pageLabel
        AssistantScope.CURRENT_CHAPTER -> chapterTitle ?: pageLabel
        null -> pageLabel
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
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.58f)
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
            if (scopes.isNotEmpty()) {
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
            }

            if (activeScope == AssistantScope.SELECTION && selectionText != null) {
                Text(
                    selectionText.take(MAX_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("AI actions", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        when (activeScope) {
                            AssistantScope.SELECTION -> onSummarizeSelection()
                            AssistantScope.CURRENT_PAGE -> onSummarizePage()
                            AssistantScope.CURRENT_CHAPTER -> onSummarizeChapter()
                            null -> Unit
                        }
                    },
                    enabled = !isWorking && activeScope != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isWorking) "Working…" else "Summarize")
                }
                TextButton(
                    onClick = {
                        when (activeScope) {
                            AssistantScope.SELECTION -> onTranslateSelection(targetLanguage)
                            AssistantScope.CURRENT_PAGE -> onTranslatePage(targetLanguage)
                            AssistantScope.CURRENT_CHAPTER -> onTranslateChapter(targetLanguage)
                            null -> Unit
                        }
                    },
                    enabled = !isWorking && activeScope != null && targetLanguage.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Translate")
                }
            }
            FastLanguageSelector(
                selectedLanguage = targetLanguage,
                onLanguageSelected = onTargetLanguageChange,
                modifier = Modifier.fillMaxWidth(),
            )
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
                    enabled = activeScope != null && !isEdgeLoading,
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
