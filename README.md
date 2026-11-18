# VibeReader

VibeReader is an Android reading app for **PDF** and **EPUB** books. It pairs a focused,
customizable reader with an **AI Book Assistant** for grounded questions, summaries and quote
extraction, and a **Wallpaper Studio** that turns the quotes you approve into typography
wallpapers that automatically rotate on your home screen, lock screen, or both.

Everything is written in Kotlin with Jetpack Compose. Books and quotes stay on your device;
AI features are optional and use API keys that you provide (stored encrypted on-device).

## Features

### Library
- Scans the device for PDF and EPUB files (Android 11+ "All files access", legacy
  `READ_EXTERNAL_STORAGE` on older versions).
- Book grid or list views with cover art, format badges, reading progress and share actions.
- Search your library and switch between grid and list views; books are ordered by recent activity
  and opening one resumes where you left off.

### PDF & EPUB reading
- PDF rendering with pinch-to-zoom, pan, horizontal or vertical page scrolling, and a page slider.
- EPUB support with chapter parsing (Jsoup), custom pagination and a dedicated renderer.
- Text selection with actions to **highlight/unhighlight**, **save as quote**, **copy**, **translate**,
  **ask AI**, and **look up in a dictionary app** (single words). Highlight positions persist
  across sessions.
- PDF-specific night rendering via a color matrix, plus a page bitmap cache for smooth paging.
- Per-book reading themes and global reader preferences saved in Room.

### AI Book Assistant
- Ask free-form questions about the book you are reading ("Ask this book"), with local keyword
  retrieval that supplies relevant excerpts as context and sanitizers that keep prompts and
  responses scoped and safe.
- One-tap **Summarize**, **Explain** and **Extract quote** actions.
- Quotes can be fetched in batches with AI and reviewed before they enter your library — user-saved
  quotes are always listed above AI suggestions.
- Multiple providers: **OpenCode Zen, Google Gemini, OpenAI, OpenRouter, Anthropic Claude, Groq,
  and DeepSeek**, with dynamic model discovery (FREE / PAID / PRO tiers).
- Resilient request handling: rotating provider/key pool, 60-second cooldown after HTTP 429,
  session-wide blacklisting of rejected keys, and a local cache for repeated AI responses.
  If all AI paths fail, local quote selection takes over.

### Wallpaper Studio
- Turns **approved** quotes into 1080 × 2400 typography wallpapers with the book title, author and
  a "progress through book" footer.
- Five layout variants: Classic, Minimal, Emphasis, Overlay and Split, each with light and dark
  palettes and live previews in a swipeable carousel.
- Apply the generated bitmap to the **home screen, lock screen, or both** (with an OEM fallback to
  the system wallpaper when lock-screen wallpapers are unsupported).
- Automatic rotation: **Manual**, **Hourly**, **Daily**, or **On unlock**, powered by WorkManager
  with boot/unlock receivers. Rotation walks through your approved quotes round-robin and advances
  only after the wallpaper was applied successfully.
- Wallpaper settings, the last applied quote, and the prompt context are stored in encrypted
  preferences.

### Text-to-speech
- Listen to book content with the offline **Android System Voice** or free **Edge Neural** voices
  (Jenny / Guy) streamed over a WebSocket. Playback controls live in the reader's audio overlay.

### Themes & settings
- App themes: System, Light, Sepia and OLED Black, applied across the whole app.
- AI provider/model selection with key enable/disable controls and model discovery.
- Choose which installed dictionary apps handle text lookup.
- Keys are never hardcoded — see [API keys](#api-keys-optional).

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin 2.2.10, Coroutines 1.10.2 |
| UI | Jetpack Compose (BOM 2026.02.01), Material 3, Navigation Compose 2.9.7, Lifecycle 2.11.0 |
| Architecture | MVVM with ViewModels and StateFlow; Hilt 2.57.1 for dependency injection |
| Persistence | Room 2.8.4 (9 entities, exported schemas), DataStore Preferences 1.1.7 |
| Background work | WorkManager 2.11.1 (wallpaper rotation), broadcast receivers for boot/unlock |
| Security | AndroidX Security Crypto 1.1.0 (`EncryptedSharedPreferences` / `MasterKey`) for API keys and wallpaper settings |
| Documents | pdfbox-android 2.0.27.0 (PDF), epublib-core 3.1 + Jsoup 1.19.1 (EPUB), AndroidX DocumentFile 1.0.1 |
| Networking | OkHttp 4.12.0 (AI providers, model discovery, Edge TTS WebSockets) |
| Build | Android Gradle Plugin 9.5.1, Gradle 9.6.0 wrapper, KSP 2.2.10-2.0.2, JDK 25 toolchain via the Foojay resolver |
| Tests | JUnit 4, AndroidX JUnit / Espresso 3.7.0, Compose UI Test |

SDK configuration: `compileSdk` **37**, `targetSdk` **34**, `minSdk` **24**, Java 11 source/target
compatibility, application ID `com.example.vibereader`.

## Project Structure

```
.
├── app/
│   ├── schemas/                     # Exported Room database schemas (v1–v15)
│   └── src/
│       ├── main/
│       │   ├── java/com/example/vibereader/
│       │   │   ├── MainActivity.kt  # Edge-to-edge shell, bottom tabs, NavHost
│       │   │   ├── core/            # Room database, theme system, secure storage
│       │   │   ├── data/
│       │   │   │   ├── document/    # PDF/EPUB document loading and parsing engines
│       │   │   │   ├── local/       # Encrypted preferences and model preferences
│       │   │   │   ├── remote/      # AI clients, model discovery, quote curation, Edge TTS
│       │   │   │   ├── repository/  # Books, quotes, AI assistant, failover, response cache
│       │   │   │   └── room/        # Entities and DAOs
│       │   │   ├── domain/          # AI manager, prompts/sanitizers, quote validation, models
│       │   │   ├── model/           # AI provider definitions, themes, TTS voices
│       │   │   ├── service/         # Wallpaper worker, rotation manager, boot/unlock receivers
│       │   │   ├── ui/
│       │   │   │   ├── library/     # Library screen + ViewModel
│       │   │   │   ├── quotes/      # Quote library and Wallpaper Studio
│       │   │   │   ├── reader/      # PDF/EPUB readers, highlights, AI assistant sheet, TTS overlay
│       │   │   │   └── settings/    # Settings, API key and storage cards
│       │   │   └── util/            # Wallpaper generation/application, file and TTS helpers
│       │   └── res/                 # Manifest, launcher icon, themes, wallpaper metadata
│       ├── test/                    # Local unit tests
│       └── androidTest/             # Instrumented tests
├── gradle/
│   ├── libs.versions.toml           # Centralized dependency versions
│   └── wrapper/                     # Gradle 9.6.0 wrapper
├── build.gradle.kts                 # Root build file
└── settings.gradle.kts              # Repositories and module list
```

## Setup & Build Instructions

### Prerequisites
- **Android Studio** with support for Android Gradle Plugin 9.5.x and Gradle 9.6.
- **Android SDK**: API 37 (compile) and API 34 (target) installed via the SDK Manager.
- **JDK 25** — Gradle's Foojay toolchain resolver downloads it automatically if it is not
  installed locally. The app itself targets Java 11 bytecode.
- Internet access for dependency resolution, AI requests and Edge TTS.

### Get the code
```bash
git clone https://github.com/marufhasan-1/VibeReader.git
cd VibeReader
```

### Open in Android Studio
1. Open the project root in Android Studio and let Gradle sync.
2. Android Studio creates `local.properties` with your `sdk.dir`. This file is git-ignored and
   must never be committed.
3. Select a device or emulator and press **Run**, or use the command line:

```bash
./gradlew assembleDebug          # build a debug APK
./gradlew installDebug           # build and install on a connected device
```

### API keys (optional)
AI features work only with a key, and you can provide one in either of two ways:

- **In the app (recommended):** open **Settings** and add keys per provider. They are stored in
  `EncryptedSharedPreferences` backed by the Android Keystore and can be enabled/disabled there.
- **At build time:** add any of the following properties to the git-ignored `local.properties`:

```
OPENCODE_API_KEY=...
GEMINI_API_KEY=...
GEMINI_API_KEYS=key1,key2        # optional multiple Gemini keys
OPENAI_API_KEY=...
OPENROUTER_API_KEY=...
CLAUDE_API_KEY=...
GROQ_API_KEY=...
DEEPSEEK_API_KEY=...
```

The literal value `your_key_here` is treated as unset. Build-time keys become `BuildConfig`
constants; otherwise no key is bundled and the Settings UI is used.

### Build variants & tests
```bash
./gradlew test                   # local unit tests
./gradlew connectedAndroidTest   # instrumented tests (device/emulator required)
./gradlew assembleRelease        # minified release APK
```

> The `release` build currently reuses the debug signing config for convenience. Configure your own
> `signingConfig` before distributing the app.

### Permissions at runtime
On first launch the app requests **All files access** (Android 11+) or `READ_EXTERNAL_STORAGE` on
older devices so it can scan your library. Applying a wallpaper triggers the system wallpaper
permission dialog. `INTERNET`, `RECEIVE_BOOT_COMPLETED` and foreground-service permissions support
AI requests, wallpaper restoration after reboot, and background work.

## Tests

Local unit tests cover AI provider endpoints and model tiers, prompt/context/response sanitizers,
AI quote response parsing, quote quality validation, PDF page rendering/selection/word snapping,
book-title and filename handling, dictionary routing, and OpenCode model policy. An instrumented
test template is included under `app/src/androidTest`.
