import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Read build-time AI credentials from the git-ignored local.properties file.
// Missing values or the placeholder text become empty BuildConfig constants, so
// user-saved keys in Settings continue to work without any hardcoded fallback.
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

fun localBuildConfigString(name: String): String {
    val value = localProperties.getProperty(name)
        ?.trim()
        ?.takeUnless { it.isEmpty() || it == "your_key_here" }
        .orEmpty()
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.example.vibereader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vibereader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "OPENROUTER_API_KEY",
            localBuildConfigString("OPENROUTER_API_KEY"),
        )
        buildConfigField(
            "String",
            "OPENCODE_API_KEY",
            localBuildConfigString("OPENCODE_API_KEY"),
        )
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            localBuildConfigString("GEMINI_API_KEY"),
        )
        buildConfigField(
            "String",
            "CLAUDE_API_KEY",
            localBuildConfigString("CLAUDE_API_KEY"),
        )
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            localBuildConfigString("OPENAI_API_KEY"),
        )
        buildConfigField(
            "String",
            "GROQ_API_KEY",
            localBuildConfigString("GROQ_API_KEY"),
        )
        buildConfigField(
            "String",
            "DEEPSEEK_API_KEY",
            localBuildConfigString("DEEPSEEK_API_KEY"),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.epublib.core) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "org.khronos", module = "opengl-api")
    }
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
