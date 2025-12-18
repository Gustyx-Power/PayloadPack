package id.xms.payloadpack

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.xms.payloadpack.native.NativeLib
import id.xms.payloadpack.ui.theme.PayloadPackTheme

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "PayloadPack"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load the native library
        val libraryLoaded = NativeLib.loadLibrary()
        Log.d(TAG, "Native library loaded: $libraryLoaded")
        
        enableEdgeToEdge()
        setContent {
            PayloadPackTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RustDemoScreen(
                        libraryLoaded = libraryLoaded,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun RustDemoScreen(
    libraryLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    var rustMessage by remember { mutableStateOf<String?>(null) }
    var processedMessage by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PayloadPack",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Rust + JNI Demo",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = if (libraryLoaded) "✅ Native library loaded" else "❌ Failed to load library",
            style = MaterialTheme.typography.bodyLarge,
            color = if (libraryLoaded) MaterialTheme.colorScheme.primary 
                    else MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                rustMessage = NativeLib.helloFromRust()
            },
            enabled = libraryLoaded
        ) {
            Text("Call helloFromRust()")
        }
        
        rustMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                processedMessage = NativeLib.processMessage("PayloadPack")
            },
            enabled = libraryLoaded
        ) {
            Text("Call processMessage()")
        }
        
        processedMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RustDemoScreenPreview() {
    PayloadPackTheme {
        RustDemoScreen(libraryLoaded = true)
    }
}