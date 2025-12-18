package id.xms.payloadpack

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.topjohnwu.superuser.Shell
import id.xms.payloadpack.core.StorageHelper
import id.xms.payloadpack.native.NativeLib
import id.xms.payloadpack.ui.screens.HomeScreen
import id.xms.payloadpack.ui.screens.RootInitScreen
import id.xms.payloadpack.ui.screens.StoragePermissionScreen
import id.xms.payloadpack.ui.screens.WorkspaceScreen
import id.xms.payloadpack.ui.theme.PayloadPackTheme
import kotlinx.coroutines.launch

/**
 * App initialization state.
 */
enum class AppState {
    CHECKING,
    NEED_STORAGE_PERMISSION,
    NEED_ROOT,
    READY
}

/**
 * Navigation state
 */
sealed class NavigationState {
    data object Home : NavigationState()
    data class Workspace(val projectPath: String) : NavigationState()
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PayloadPack"

        init {
            // Configure libsu Shell
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(30)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load native library early
        val nativeLoaded = NativeLib.loadLibrary()
        Log.d(TAG, "Native library loaded: $nativeLoaded")

        enableEdgeToEdge()
        setContent {
            PayloadPackTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var appState by remember { mutableStateOf(AppState.CHECKING) }

                    // Check permissions on resume (user might come back from settings)
                    LaunchedEffect(Unit) {
                        lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                // Re-check storage permission when app resumes
                                appState = when {
                                    !StorageHelper.hasStoragePermission() -> AppState.NEED_STORAGE_PERMISSION
                                    appState == AppState.NEED_STORAGE_PERMISSION -> AppState.NEED_ROOT
                                    appState == AppState.CHECKING -> AppState.NEED_STORAGE_PERMISSION.takeIf { 
                                        !StorageHelper.hasStoragePermission() 
                                    } ?: AppState.NEED_ROOT
                                    else -> appState
                                }
                                
                                Log.d(TAG, "App state: $appState, Storage permission: ${StorageHelper.hasStoragePermission()}")
                            }
                        }
                    }

                    // Initial check
                    LaunchedEffect(Unit) {
                        appState = if (StorageHelper.hasStoragePermission()) {
                            AppState.NEED_ROOT
                        } else {
                            AppState.NEED_STORAGE_PERMISSION
                        }
                    }

                    Crossfade(
                        targetState = appState,
                        label = "MainCrossfade"
                    ) { state ->
                        when (state) {
                            AppState.CHECKING -> {
                                // Show nothing or loading while checking
                            }
                            
                            AppState.NEED_STORAGE_PERMISSION -> {
                                StoragePermissionScreen(
                                    onPermissionGranted = {
                                        if (StorageHelper.hasStoragePermission()) {
                                            appState = AppState.NEED_ROOT
                                        }
                                    },
                                    onSkip = {
                                        // Allow skipping for development (may cause issues)
                                        Log.w(TAG, "User skipped storage permission!")
                                        appState = AppState.NEED_ROOT
                                    }
                                )
                            }
                            
                            AppState.NEED_ROOT -> {
                                RootInitScreen(
                                    onInitialized = {
                                        appState = AppState.READY
                                    }
                                )
                            }
                            
                            AppState.READY -> {
                                // Navigation state management
                                var navigationState by remember {
                                    mutableStateOf<NavigationState>(NavigationState.Home)
                                }

                                when (val navState = navigationState) {
                                    is NavigationState.Home -> {
                                        HomeScreen(
                                            onNavigateToWorkspace = { projectPath ->
                                                navigationState = NavigationState.Workspace(projectPath)
                                            }
                                        )
                                    }
                                    is NavigationState.Workspace -> {
                                        WorkspaceScreen(
                                            projectPath = navState.projectPath,
                                            onBack = {
                                                navigationState = NavigationState.Home
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}