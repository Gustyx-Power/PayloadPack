package id.xms.payloadpack

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.topjohnwu.superuser.Shell
import id.xms.payloadpack.native.NativeLib
import id.xms.payloadpack.ui.screens.HomeScreen
import id.xms.payloadpack.ui.screens.RootInitScreen
import id.xms.payloadpack.ui.theme.PayloadPackTheme

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
                    var isInitialized by remember { mutableStateOf(false) }

                    Crossfade(
                        targetState = isInitialized,
                        label = "MainCrossfade"
                    ) { initialized ->
                        if (initialized) {
                            HomeScreen()
                        } else {
                            RootInitScreen(
                                onInitialized = {
                                    isInitialized = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}