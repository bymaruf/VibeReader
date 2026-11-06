package com.example.vibereader.model

enum class ReaderTtsVoice(
    val label: String,
    val edgeVoiceName: String? = null,
) {
    ANDROID_SYSTEM("Android System Voice (Offline)"),
    EDGE_JENNY("Edge Neural - Jenny (US Female - Free)", "en-US-JennyNeural"),
    EDGE_GUY("Edge Neural - Guy (US Male - Free)", "en-US-GuyNeural");

    companion object {
        fun fromStoredName(name: String?): ReaderTtsVoice =
            entries.firstOrNull { it.name == name } ?: ANDROID_SYSTEM
    }
}
