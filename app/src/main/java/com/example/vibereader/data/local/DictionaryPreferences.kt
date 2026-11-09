package com.example.vibereader.data.local

import android.content.Context

/**
 * Stores the user's preferred text-processing app for the reader's Dictionary
 * action. A null/blank package means "use Google Search".
 */
class DictionaryPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    fun getCustomDictionaryPackage(): String? =
        preferences.getString(KEY_CUSTOM_DICTIONARY_PACKAGE, null)
            ?.takeIf(String::isNotBlank)

    fun setCustomDictionaryPackage(packageName: String?) {
        preferences.edit().apply {
            if (packageName.isNullOrBlank()) {
                remove(KEY_CUSTOM_DICTIONARY_PACKAGE)
            } else {
                putString(KEY_CUSTOM_DICTIONARY_PACKAGE, packageName)
            }
        }.apply()
    }

    private companion object {
        const val PREFERENCES_FILE = "vibereader_dictionary_preferences"
        const val KEY_CUSTOM_DICTIONARY_PACKAGE = "custom_dictionary_package"
    }
}
