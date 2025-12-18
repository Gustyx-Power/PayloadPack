package id.xms.payloadpack.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.xms.payloadpack.core.DirectoryManager
import kotlinx.coroutines.launch

/**
 * State of the initialization process.
 */
sealed class InitState {
    data object Idle : InitState()
    data object CheckingRoot : InitState()
    data object RequestingRoot : InitState()
    data object RootDenied : InitState()
    data object Initializing : InitState()
    data object Success : InitState()
    data class Error(val message: String) : InitState()
}

/**
 * Root Initialization Screen.
 *
 * This screen handles the root check and workspace initialization flow.
 * It should be shown on app launch before accessing any other features.
 *
 * @param onInitialized Callback when initialization is complete and app can proceed
 */
@Composable
fun RootInitScreen(
    onInitialized: () -> Unit,
    modifier: Modifier = Modifier
) {
    var initState by remember { mutableStateOf<InitState>(InitState.Idle) }
    val scope = rememberCoroutineScope()

    // Start the initialization flow on first composition
    LaunchedEffect(Unit) {
        initState = InitState.CheckingRoot

        // Request root access
        initState = InitState.RequestingRoot
        val hasRoot = DirectoryManager.requestRootAccess()

        if (!hasRoot) {
            initState = InitState.RootDenied
            return@LaunchedEffect
        }

        // Initialize workspace
        initState = InitState.Initializing
        val result = DirectoryManager.initializeWorkspace()

        initState = when (result) {
            is DirectoryManager.InitResult.Success -> InitState.Success
            is DirectoryManager.InitResult.Error -> InitState.Error(result.message)
        }
    }

    // Auto-proceed when successful
    LaunchedEffect(initState) {
        if (initState == InitState.Success) {
            // Small delay to show success state
            kotlinx.coroutines.delay(1000)
            onInitialized()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // App Title
            Text(
                text = "PayloadPack",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "ROM Unpacker / Repacker",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Status Card
            InitStatusCard(
                state = initState,
                onRetry = {
                    scope.launch {
                        initState = InitState.RequestingRoot
                        val hasRoot = DirectoryManager.requestRootAccess()

                        if (!hasRoot) {
                            initState = InitState.RootDenied
                            return@launch
                        }

                        initState = InitState.Initializing
                        val result = DirectoryManager.initializeWorkspace()

                        initState = when (result) {
                            is DirectoryManager.InitResult.Success -> InitState.Success
                            is DirectoryManager.InitResult.Error -> InitState.Error(result.message)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun InitStatusCard(
    state: InitState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                InitState.Idle,
                InitState.CheckingRoot -> {
                    StatusRow(
                        icon = null,
                        isLoading = true,
                        title = "Checking environment...",
                        subtitle = "Please wait"
                    )
                }

                InitState.RequestingRoot -> {
                    StatusRow(
                        icon = null,
                        isLoading = true,
                        title = "Requesting Root Access",
                        subtitle = "Please grant root permission in the popup"
                    )
                }

                InitState.RootDenied -> {
                    StatusRow(
                        icon = Icons.Default.Close,
                        iconColor = MaterialTheme.colorScheme.error,
                        isLoading = false,
                        title = "Root Access Denied",
                        subtitle = "This app requires root access to function properly.\n" +
                                "It needs to access /data for preserving Linux file attributes."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }

                InitState.Initializing -> {
                    StatusRow(
                        icon = null,
                        isLoading = true,
                        title = "Initializing Workspace",
                        subtitle = "Creating directories..."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Show directory info
                    DirectoryInfo(
                        userDir = DirectoryManager.USER_DIR,
                        workDir = DirectoryManager.WORK_DIR
                    )
                }

                InitState.Success -> {
                    StatusRow(
                        icon = Icons.Default.Check,
                        iconColor = Color(0xFF4CAF50),
                        isLoading = false,
                        title = "Ready!",
                        subtitle = "Workspace initialized successfully"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    DirectoryInfo(
                        userDir = DirectoryManager.USER_DIR,
                        workDir = DirectoryManager.WORK_DIR
                    )
                }

                is InitState.Error -> {
                    StatusRow(
                        icon = Icons.Default.Warning,
                        iconColor = MaterialTheme.colorScheme.error,
                        isLoading = false,
                        title = "Initialization Failed",
                        subtitle = state.message
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector?,
    isLoading: Boolean,
    title: String,
    subtitle: String,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = isLoading,
                label = "StatusIconCrossfade"
            ) { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                } else {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = iconColor
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DirectoryInfo(
    userDir: String,
    workDir: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp)
    ) {
        DirectoryRow(
            label = "User Storage",
            path = userDir,
            description = "Input/Output files"
        )

        Spacer(modifier = Modifier.height(12.dp))

        DirectoryRow(
            label = "Work Storage",
            path = workDir,
            description = "Full Linux attributes (symlinks, permissions)"
        )
    }
}

@Composable
private fun DirectoryRow(
    label: String,
    path: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
