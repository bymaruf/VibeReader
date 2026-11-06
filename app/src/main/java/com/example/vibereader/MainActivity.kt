package com.example.vibereader

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.vibereader.ui.library.LibraryScreen
import com.example.vibereader.ui.library.LibraryScreen
import com.example.vibereader.ui.quotes.QuotesScreen
import com.example.vibereader.ui.reader.ReaderScreen
import com.example.vibereader.ui.settings.SettingsScreen
import com.example.vibereader.core.theme.VibeReaderTheme
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeReaderRoot()
        }
    }
}

@Composable
private fun VibeReaderRoot() {
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context) { (context as? Activity)?.window }
    var storageAccessReady by remember { mutableStateOf(false) }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        storageAccessReady = true
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        storageAccessReady = true
    }
    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager() -> {
                try {
                    allFilesAccessLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } catch (_: ActivityNotFoundException) {
                    storageAccessReady = true
                }
            }
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED -> {
                legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> storageAccessReady = true
        }
    }
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(context, database) {
            SettingsViewModel.Factory(database, context.applicationContext)
        },
    )
    val currentTheme by settingsViewModel.appTheme.collectAsStateWithLifecycle()
    val systemDarkTheme = isSystemInDarkTheme()

    VibeReaderTheme(themeOption = currentTheme) {
        SideEffect {
            window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                val lightSystemBars = when (currentTheme) {
                    ThemeOption.OLED_BLACK -> false
                    ThemeOption.SEPIA, ThemeOption.LIGHT -> true
                    ThemeOption.SYSTEM -> !systemDarkTheme
                }
                controller.isAppearanceLightStatusBars = lightSystemBars
                controller.isAppearanceLightNavigationBars = lightSystemBars
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = when (currentTheme) {
                ThemeOption.OLED_BLACK -> Color.Black
                ThemeOption.SEPIA -> Color(0xFFF8F1E5)
                ThemeOption.LIGHT -> Color.White
                ThemeOption.SYSTEM -> MaterialTheme.colorScheme.background
            },
        ) {
            VibeReaderApp(settingsViewModel, currentTheme, storageAccessReady)
        }
    }
}

private data class AppDestination(
    val route: String,
    val label: String,
    val marker: String,
)

private val destinations = listOf(
    AppDestination("library", "Library", "B"),
    AppDestination("quotes", "Quotes", "Q"),
    AppDestination("settings", "Settings", "S"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibeReaderApp(
    settingsViewModel: SettingsViewModel,
    appTheme: ThemeOption,
    storageAccessReady: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isReader = currentDestination?.route?.startsWith("reader/") == true

    Scaffold(
        bottomBar = {
            if (!isReader) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    destinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(destination.marker) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("library") {
                LibraryScreen(
                    scanEnabled = storageAccessReady,
                    onBookSelected = { bookId ->
                        navController.navigate("reader/$bookId")
                    },
                )
            }
            composable("quotes") { QuotesScreen() }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel) }
            composable("reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                ReaderScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    appTheme = appTheme,
                )
            }
        }
    }
}
