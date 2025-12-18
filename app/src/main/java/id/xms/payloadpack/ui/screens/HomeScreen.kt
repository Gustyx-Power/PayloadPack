package id.xms.payloadpack.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.SdCard
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.xms.payloadpack.R
import id.xms.payloadpack.core.DirectoryManager
import id.xms.payloadpack.core.StorageHelper
import id.xms.payloadpack.native.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "HomeScreen"

/**
 * Represents the current state of the home screen.
 */
sealed class HomeState {
    data object Empty : HomeState()
    data object Loading : HomeState()
    data class Loaded(val payload: PayloadData) : HomeState()
    data class Error(val message: String) : HomeState()
}

/**
 * Parsed payload data to display.
 */
data class PayloadData(
    val version: String,
    val blockSize: Int,
    val partialUpdate: Boolean,
    val securityPatch: String?,
    val partitions: List<PartitionData>,
    val totalSize: String,
    val totalSizeBytes: Long,
    val filePath: String
)

/**
 * Individual partition data.
 */
data class PartitionData(
    val name: String,
    val size: String,
    val sizeBytes: Long,
    val operationsCount: Int,
    val isSelected: Boolean = false
)

/**
 * Parse JSON result from Rust into PayloadData.
 */
private fun parsePayloadJson(jsonString: String): Result<PayloadData> {
    return try {
        val json = JSONObject(jsonString)
        
        // Check for error
        if (json.has("error")) {
            return Result.failure(Exception(json.getString("error")))
        }
        
        // Parse header
        val header = json.getJSONObject("header")
        val versionMajor = header.getInt("version_major")
        val versionMinor = header.getInt("version_minor")
        
        // Parse partitions
        val partitionsArray = json.getJSONArray("partitions")
        val partitions = mutableListOf<PartitionData>()
        
        for (i in 0 until partitionsArray.length()) {
            val partition = partitionsArray.getJSONObject(i)
            partitions.add(
                PartitionData(
                    name = partition.getString("name"),
                    size = partition.getString("size_human"),
                    sizeBytes = partition.getLong("size"),
                    operationsCount = partition.getInt("operations_count")
                )
            )
        }
        
        Result.success(
            PayloadData(
                version = "$versionMajor.$versionMinor",
                blockSize = json.getInt("block_size"),
                partialUpdate = json.getBoolean("partial_update"),
                securityPatch = json.optString("security_patch_level").takeIf { it.isNotEmpty() },
                partitions = partitions,
                totalSize = json.getString("total_size_human"),
                totalSizeBytes = json.getLong("total_size"),
                filePath = json.optString("file_path", "")
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
        Result.failure(e)
    }
}

/**
 * Main home screen of PayloadPack.
 * Features a collapsing top app bar, empty state, partition list, and FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    var homeState by remember { mutableStateOf<HomeState>(HomeState.Empty) }
    var rustMessage by remember { mutableStateOf<String?>(null) }
    var diskUsage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var hasDefaultPayload by remember { mutableStateOf(false) }

    // Haptic feedback helper
    fun triggerHaptic(success: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (success) {
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    } else {
                        VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(if (success) 50L else 100L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic feedback failed: ${e.message}")
        }
    }

    /**
     * Load a payload file using Rust native code.
     */
    fun loadPayload(path: String) {
        Log.d(TAG, "loadPayload called with path: $path")
        
        scope.launch {
            homeState = HomeState.Loading
            
            try {
                // Resolve path (handle /sdcard/ symlink)
                val resolvedPath = StorageHelper.resolvePath(path)
                if (resolvedPath == null) {
                    Log.e(TAG, "Failed to resolve path: $path")
                    homeState = HomeState.Error("Invalid path: $path")
                    triggerHaptic(false)
                    return@launch
                }
                
                Log.d(TAG, "Resolved path: $resolvedPath")
                
                // Check if file is readable
                if (!StorageHelper.isFileReadable(resolvedPath)) {
                    Log.e(TAG, "File not readable: $resolvedPath")
                    homeState = HomeState.Error("File not found or permission denied:\n$resolvedPath")
                    triggerHaptic(false)
                    return@launch
                }
                
                Log.d(TAG, "File is readable, size: ${StorageHelper.getFileSize(resolvedPath)}")
                
                // Call Rust code on background thread
                val jsonResult = withContext(Dispatchers.IO) {
                    Log.d(TAG, "Calling NativeLib.inspectPayload...")
                    val result = NativeLib.inspectPayload(resolvedPath)
                    Log.d(TAG, "Rust returned: ${result?.take(200)}...")
                    result
                }
                
                if (jsonResult == null) {
                    Log.e(TAG, "Rust returned null")
                    homeState = HomeState.Error("Native library returned null.\nMake sure the file is a valid payload.bin")
                    triggerHaptic(false)
                    return@launch
                }
                
                // Check if it's an error response
                if (jsonResult.contains("\"error\"")) {
                    val errorJson = JSONObject(jsonResult)
                    val errorMessage = errorJson.getString("error")
                    Log.e(TAG, "Rust returned error: $errorMessage")
                    homeState = HomeState.Error(errorMessage)
                    triggerHaptic(false)
                    return@launch
                }
                
                // Parse the JSON result
                val parseResult = parsePayloadJson(jsonResult)
                parseResult.fold(
                    onSuccess = { payload ->
                        Log.i(TAG, "Successfully parsed payload: ${payload.partitions.size} partitions")
                        homeState = HomeState.Loaded(payload)
                        triggerHaptic(true)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to parse result: ${error.message}")
                        homeState = HomeState.Error("Failed to parse result:\n${error.message}")
                        triggerHaptic(false)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in loadPayload: ${e.message}", e)
                homeState = HomeState.Error("Unexpected error:\n${e.message}")
                triggerHaptic(false)
            }
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        if (NativeLib.loadLibrary()) {
            rustMessage = NativeLib.helloFromRust()
            Log.d(TAG, "Rust message: $rustMessage")
        } else {
            Log.e(TAG, "Failed to load native library")
        }
        diskUsage = DirectoryManager.getWorkspaceUsage()
        hasDefaultPayload = StorageHelper.hasDefaultPayload()
        Log.d(TAG, "Has default payload: $hasDefaultPayload")
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopBar(
                scrollBehavior = scrollBehavior,
                rustMessage = rustMessage,
                diskUsage = diskUsage
            )
        },
        floatingActionButton = {
            HomeFab(
                state = homeState,
                onPickFile = {
                    // For now, load the default payload
                    val defaultPath = StorageHelper.getDefaultPayloadPath()
                    Log.d(TAG, "Loading default payload: $defaultPath")
                    loadPayload(defaultPath)
                },
                onExtract = {
                    // TODO: Extract implementation
                    triggerHaptic(true)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Progress indicator when processing
            AnimatedVisibility(
                visible = homeState is HomeState.Loading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Main content with animated transitions
            AnimatedContent(
                targetState = homeState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(300)
                    ) togetherWith fadeOut(animationSpec = tween(200)) + scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(200)
                    )
                },
                label = "HomeContentTransition"
            ) { state ->
                when (state) {
                    HomeState.Empty -> EmptyState(
                        hasDefaultPayload = hasDefaultPayload,
                        onLoadDefault = {
                            loadPayload(StorageHelper.getDefaultPayloadPath())
                        }
                    )
                    HomeState.Loading -> LoadingState()
                    is HomeState.Loaded -> PayloadContent(
                        payload = state.payload,
                        onPartitionClick = { partition ->
                            triggerHaptic(true)
                            // TODO: Handle partition selection
                        }
                    )
                    is HomeState.Error -> ErrorState(
                        message = state.message,
                        onRetry = {
                            homeState = HomeState.Empty
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    rustMessage: String?,
    diskUsage: Pair<Long, Long>?,
    modifier: Modifier = Modifier
) {
    LargeTopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = { /* TODO: Settings */ }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.action_settings)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
    )
}

@Composable
private fun HomeFab(
    state: HomeState,
    onPickFile: () -> Unit,
    onExtract: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoaded = state is HomeState.Loaded
    val isLoading = state is HomeState.Loading
    
    ExtendedFloatingActionButton(
        onClick = { 
            if (!isLoading) {
                if (isLoaded) onExtract() else onPickFile() 
            }
        },
        icon = {
            Icon(
                imageVector = if (isLoaded) Icons.Outlined.Archive else Icons.Default.Add,
                contentDescription = null
            )
        },
        text = {
            Text(
                text = when {
                    isLoading -> stringResource(R.string.status_analyzing)
                    isLoaded -> stringResource(R.string.action_extract_all)
                    else -> stringResource(R.string.action_pick_file)
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    )
}

/**
 * Empty state shown when no payload is selected.
 */
@Composable
private fun EmptyState(
    hasDefaultPayload: Boolean = false,
    onLoadDefault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Decorative icon background
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.empty_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            AssistChip(
                onClick = { },
                label = { Text(stringResource(R.string.empty_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Show "Load Default" button if default payload exists
            if (hasDefaultPayload) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(onClick = onLoadDefault) {
                    Text("Load Default Payload")
                }
            }
        }
    }
}

/**
 * Loading state with status text.
 */
@Composable
private fun LoadingState(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.status_analyzing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.please_wait),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Error state with retry option.
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.status_error),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Main content showing payload information and partition list.
 */
@Composable
private fun PayloadContent(
    payload: PayloadData,
    onPartitionClick: (PartitionData) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Payload info card
        item {
            PayloadInfoCard(payload = payload)
        }

        // Section header
        item {
            Row(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.payload_partitions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.partition_count, payload.partitions.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Partition cards
        items(
            items = payload.partitions,
            key = { it.name }
        ) { partition ->
            PartitionCard(
                partition = partition,
                onClick = { onPartitionClick(partition) }
            )
        }

        // Bottom spacer for FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Card showing payload header information.
 */
@Composable
private fun PayloadInfoCard(
    payload: PayloadData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.payload_info),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(
                    label = stringResource(R.string.payload_version),
                    value = payload.version
                )
                InfoItem(
                    label = stringResource(R.string.payload_total_size),
                    value = payload.totalSize
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(
                    label = stringResource(R.string.payload_block_size),
                    value = "${payload.blockSize}"
                )
                payload.securityPatch?.let { patch ->
                    InfoItem(
                        label = stringResource(R.string.payload_security_patch),
                        value = patch
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * Individual partition card using OutlinedCard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartitionCard(
    partition: PartitionData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (partition.isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Partition icon with decorative background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(getPartitionIconBackground(partition.name)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getPartitionIcon(partition.name),
                    contentDescription = stringResource(R.string.cd_partition_icon),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Partition info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = partition.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = partition.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.partition_operations, partition.operationsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Attribute chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    getPartitionAttributes(partition.name).forEach { attr ->
                        AssistChip(
                            onClick = { },
                            label = { 
                                Text(
                                    text = attr,
                                    style = MaterialTheme.typography.labelSmall
                                ) 
                            },
                            modifier = Modifier.height(24.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Get appropriate icon for partition based on name.
 */
@Composable
private fun getPartitionIcon(name: String): ImageVector {
    return when {
        name.contains("system", ignoreCase = true) -> Icons.TwoTone.Android
        name.contains("vendor", ignoreCase = true) -> Icons.TwoTone.Settings
        name.contains("boot", ignoreCase = true) -> Icons.TwoTone.PhoneAndroid
        name.contains("product", ignoreCase = true) -> Icons.TwoTone.Folder
        name.contains("odm", ignoreCase = true) -> Icons.TwoTone.SdCard
        name.contains("vbmeta", ignoreCase = true) -> Icons.Outlined.Memory
        name.contains("dtbo", ignoreCase = true) -> Icons.Outlined.Memory
        else -> Icons.TwoTone.Storage
    }
}

/**
 * Get background color for partition icon.
 */
@Composable
private fun getPartitionIconBackground(name: String) = when {
    name.contains("system", ignoreCase = true) -> 
        MaterialTheme.colorScheme.primaryContainer
    name.contains("vendor", ignoreCase = true) -> 
        MaterialTheme.colorScheme.secondaryContainer
    name.contains("boot", ignoreCase = true) -> 
        MaterialTheme.colorScheme.tertiaryContainer
    else -> 
        MaterialTheme.colorScheme.surfaceVariant
}

/**
 * Get attribute labels for partition based on name.
 */
@Composable
private fun getPartitionAttributes(name: String): List<String> {
    val attrs = mutableListOf<String>()
    
    // Infer filesystem type based on common conventions
    when {
        name in listOf("system", "vendor", "product", "system_ext", "odm") -> {
            attrs.add(stringResource(R.string.attr_erofs))
            attrs.add(stringResource(R.string.attr_readonly))
        }
        name.contains("vbmeta") -> {
            attrs.add(stringResource(R.string.attr_readonly))
        }
        name == "super" -> {
            attrs.add(stringResource(R.string.attr_sparse))
        }
    }
    
    return attrs
}
