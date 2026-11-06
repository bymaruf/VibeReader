package com.example.vibereader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FastLanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val languages = remember {
        listOf(
            "Bangla",
            "English",
            "Spanish",
            "French",
            "German",
            "Hindi",
            "Arabic",
            "Japanese",
            "Bengali",
            "Chinese",
            "Italian",
            "Korean",
            "Portuguese",
            "Russian",
        )
    }
    var searchText by remember(selectedLanguage) { mutableStateOf(selectedLanguage) }
    val filtered = remember(searchText, languages) {
        languages.filter { it.contains(searchText.trim(), ignoreCase = true) }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { value ->
                searchText = value
                languages.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
                    ?.let(onLanguageSelected)
            },
            label = { Text("Search language (e.g. Ba -> Bangla)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyRow(modifier = Modifier.padding(top = 8.dp)) {
            items(filtered, key = { it }) { language ->
                SuggestionChip(
                    onClick = {
                        searchText = language
                        onLanguageSelected(language)
                    },
                    label = { Text(language) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}
