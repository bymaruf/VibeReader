package com.example.vibereader

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.core.theme.VibeReaderTheme
import com.example.vibereader.ui.library.LibraryScreen
import com.example.vibereader.ui.quotes.QuotesScreen
import com.example.vibereader.ui.reader.ReaderScreen
import com.example.vibereader.ui.settings.SettingsScreen
import com.example.vibereader.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Draw the Compose hierarchy behind the status/navigation bars. The
        // bottom navigation bar consumes its own navigation-bar inset, so the
        // Scaffold must not add a second one.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Let the app's NavigationBar color show through on 3-button
            // navigation instead of the platform's translucent white scrim.
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }
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

    // One activity-scoped SettingsViewModel is shared by the root theme wrapper
    // and the Settings tab. This is what makes an appearance change recompose
    // the whole app immediately instead of only updating the Settings screen.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(context, database) {
            SettingsViewModel.Factory(database, context.applicationContext)
        },
    )
    val currentTheme by settingsViewModel.appTheme.collectAsStateWithLifecycle(
        initialValue = ThemeOption.SYSTEM,
    )
    val systemDarkTheme = isSystemInDarkTheme()
    // The exact status/navigation icon polarity used on first launch and after
    // every theme change. Restoring this value (instead of a hardcoded one)
    // keeps the reader exit identical to the app's normal main-screen chrome.
    val lightSystemBars = when (currentTheme) {
        ThemeOption.OLED_BLACK -> false
        ThemeOption.SEPIA, ThemeOption.LIGHT -> true
        ThemeOption.SYSTEM -> !systemDarkTheme
    }

    VibeReaderTheme(themeOption = currentTheme) {
        SideEffect {
            window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = lightSystemBars
                controller.isAppearanceLightNavigationBars = lightSystemBars
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            // Follow the active color scheme directly. Hardcoded theme colors
            // here used to mask the real theme during a switch.
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            VibeReaderApp(
                settingsViewModel = settingsViewModel,
                storageAccessReady = storageAccessReady,
                lightSystemBars = lightSystemBars,
            )
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
    storageAccessReady: Boolean,
    lightSystemBars: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isReader = currentDestination?.route?.startsWith("reader/") == true
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context) { (context as? Activity)?.window }

    // ReaderScreen switches the window to decor-fitting and can leave the
    // navigation-bar behavior in transient-swipe mode. SideEffect runs after
    // composition (including the reader's DisposableEffect cleanup), so the
    // main tabs always return to the exact launch chrome: edge-to-edge, bars
    // visible, default behavior, and the theme's icon polarity. With
    // decor-fitting restored, CompactNavigationBar's single
    // navigationBarsPadding() is the only bottom inset and cannot stack an
    // extra ~48dp strip above the system navigation area.
    SideEffect {
        val activityWindow = window ?: return@SideEffect
        if (!isReader) {
            WindowCompat.setDecorFitsSystemWindows(activityWindow, false)
            WindowCompat.getInsetsController(activityWindow, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Bottom inset is owned only by CompactNavigationBar's
        // navigationBarsPadding(). Excluding it here prevents the Scaffold
        // from adding a second one when the reader restores edge-to-edge.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        bottomBar = {
            if (!isReader) {
                CompactNavigationBar(
                    destinations = destinations,
                    isSelected = { route ->
                        currentDestination?.hierarchy?.any { it.route == route } == true
                    },
                    onNavigate = { route ->
                        runCatching {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(innerPadding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("library") {
                LibraryScreen(
                    scanEnabled = storageAccessReady,
                    onBookSelected = { bookId ->
                        runCatching { navController.navigate("reader/$bookId") }
                    },
                )
            }
            composable("quotes") { QuotesScreen() }
            composable("settings") {
                // Reuse the activity-scoped instance so setTheme() updates the
                // same StateFlow that MainActivity collects for VibeReaderTheme.
                SettingsScreen(viewModel = settingsViewModel)
            }
            composable(
                route = "reader/{bookId}",
                enterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        initialOffsetX = { it / 6 },
                    ) + fadeIn(tween(180, easing = FastOutSlowInEasing))
                },
                exitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        targetOffsetX = { it / 6 },
                    ) + fadeOut(tween(180, easing = FastOutSlowInEasing))
                },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        targetOffsetX = { it / 6 },
                    ) + fadeOut(tween(180, easing = FastOutSlowInEasing))
                },
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    ReaderScreen(
                        bookId = bookId,
                        onBack = { runCatching { navController.popBackStack() } },
                    )
                }
            }
        }
    }
}

/**
 * Ultra-slim navigation bar (50.dp of content + the single system navigation
 * inset). Replaces Material 3's 80.dp NavigationBar and its selection pill.
 */
@Composable
private fun CompactNavigationBar(
    destinations: List<AppDestination>,
    isSelected: (String) -> Boolean,
    onNavigate: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(50.dp),
        ) {
            destinations.forEach { destination ->
                val selected = isSelected(destination.route)
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            onClick = { onNavigate(destination.route) },
                            role = Role.Tab,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = destination.marker,
                        color = contentColor,
                        fontSize = 17.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(
                        text = destination.label,
                        modifier = Modifier.padding(top = 2.dp),
                        color = contentColor,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
