package id.xms.payloadpack.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import id.xms.payloadpack.core.RootSizeCalculator
import id.xms.payloadpack.native.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Unpacking state for the workspace
 */
sealed class UnpackState {
    object Idle : UnpackState()
    object Loading : UnpackState()
    data class Success(val extractedCount: Int) : UnpackState()
    data class Error(val message: String) : UnpackState()
}

/**
 * State for the Workspace screen
 */
data class WorkspaceUiState(
    val isLoading: Boolean = true,
    val projectPath: String = "",
    val projectName: String = "",
    val hasPayload: Boolean = false,
    val payloadPath: String? = null,
    val payloadSize: Long = 0L,
    val payloadSizeFormatted: String = "0 B",
    val unpackState: UnpackState = UnpackState.Idle,
    val partitions: List<PartitionInfo> = emptyList(),
    val errorMessage: String? = null,
    // Progress tracking
    val extractionProgress: Float = 0f,
    val currentFile: String = "",
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L
)

/**
 * Information about an extracted partition
 */
data class PartitionInfo(
    val name: String,
    val path: String,
    val size: Long,
    val sizeFormatted: String,
    val isExtracted: Boolean = false,
    val extractedPath: String? = null,
    val isExtracting: Boolean = false
)

/**
 * WorkspaceViewModel manages the project workspace.
 *
 * Responsibilities:
 * - Check for payload.bin in the project folder
 * - Display payload information (size, status)
 * - Trigger Rust unpacking when user clicks "START UNPACK"
 * - Monitor unpacking progress and update UI
 */
class WorkspaceViewModel(private val projectPath: String) : ViewModel() {

    companion object {
        private const val TAG = "WorkspaceViewModel"
    }

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "WorkspaceViewModel initialized for: $projectPath")
        _uiState.value = _uiState.value.copy(
            projectPath = projectPath,
            projectName = File(projectPath).name
        )
        checkPayload()
    }

    /**
     * Check if payload.bin exists in the project folder
     */
    private fun checkPayload() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

                val payloadFile = File(projectPath, "payload.bin")

                if (!payloadFile.exists()) {
                    Log.w(TAG, "payload.bin not found in $projectPath")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        hasPayload = false,
                        errorMessage = "payload.bin not found in this project"
                    )
                    return@launch
                }

                Log.d(TAG, "payload.bin found at: ${payloadFile.absolutePath}")

                // Calculate payload size (may be large, so use File.length() directly for single file)
                val payloadSize = payloadFile.length()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasPayload = true,
                    payloadPath = payloadFile.absolutePath,
                    payloadSize = payloadSize,
                    payloadSizeFormatted = RootSizeCalculator.formatSize(payloadSize)
                )

                Log.d(TAG, "Payload ready: ${_uiState.value.payloadSizeFormatted}")

                // Check if already unpacked (scan for .img files)
                loadExtractedPartitions()

            } catch (e: Exception) {
                Log.e(TAG, "Error checking payload: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Start unpacking the payload.bin using Rust native code.
     */
    fun startUnpacking() {
        if (!_uiState.value.hasPayload || _uiState.value.payloadPath == null) {
            Log.e(TAG, "Cannot unpack: no payload found")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Update state to Loading
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        unpackState = UnpackState.Loading,
                        errorMessage = null
                    )
                }

                Log.d(TAG, "Starting unpacking: ${_uiState.value.payloadPath}")

                // CRITICAL FIX 1: Apply SELinux context before extraction
                Log.d(TAG, "Applying SELinux context to $projectPath")
                val selinuxResult = Shell.cmd(
                    "chcon -R u:object_r:app_data_file:s0 '$projectPath'",
                    "chmod -R 777 '$projectPath'"
                ).exec()

                if (!selinuxResult.isSuccess) {
                    Log.w(TAG, "SELinux/chmod warning: ${selinuxResult.err}")
                }

                // Call Rust native extractor with progress callback
                val payloadPath = _uiState.value.payloadPath!!
                Log.d(TAG, "Calling Rust extractor: $payloadPath -> $projectPath")

                // Create progress listener
                val progressListener = object : id.xms.payloadpack.native.ProgressListener {
                    override fun onProgress(
                        currentFile: String,
                        progress: Int,
                        bytesProcessed: Long,
                        totalBytes: Long
                    ) {
                        // Update UI on main thread
                        viewModelScope.launch(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                extractionProgress = progress / 100f,
                                currentFile = currentFile,
                                bytesProcessed = bytesProcessed,
                                totalBytes = totalBytes
                            )
                        }
                    }
                }

                val resultJson = NativeLib.extractPayload(payloadPath, projectPath, progressListener)

                if (resultJson == null) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            unpackState = UnpackState.Error("Native extraction returned null")
                        )
                    }
                    return@launch
                }

                Log.d(TAG, "Extraction result: $resultJson")

                // Parse JSON result
                val json = JSONObject(resultJson)
                val status = json.optString("status", "error")

                if (status == "success") {
                    val extractedArray = json.optJSONArray("extracted")
                    val extractedCount = extractedArray?.length() ?: 0

                    Log.d(TAG, "Extraction successful: $extractedCount partitions")

                    // CRITICAL FIX 2: Apply permissions to extracted files
                    Log.d(TAG, "Applying permissions to extracted files")
                    Shell.cmd("chmod 777 '$projectPath'/*.img 2>/dev/null").exec()

                    // Update state to Success
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            unpackState = UnpackState.Success(extractedCount)
                        )
                    }

                    // CRITICAL FIX 3: Auto-refresh partition list
                    loadExtractedPartitions()

                } else {
                    val errorMessage = json.optString("message", "Unknown error")
                    Log.e(TAG, "Extraction failed: $errorMessage")

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            unpackState = UnpackState.Error(errorMessage)
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Unpacking failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        unpackState = UnpackState.Error("Unpacking failed: ${e.message}")
                    )
                }
            }
        }
    }

    /**
     * Load extracted partitions from the project directory.
     * This is called after successful extraction to refresh the UI.
     */
    private suspend fun loadExtractedPartitions() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading extracted partitions from $projectPath")

                val projectDir = File(projectPath)
                val imgFiles = projectDir.listFiles { file ->
                    file.isFile && file.extension.lowercase() == "img"
                }?.toList() ?: emptyList()

                Log.d(TAG, "Found ${imgFiles.size} partition files")

                val partitions = imgFiles.map { file ->
                    val size = file.length()
                    val partitionName = file.nameWithoutExtension

                    // Check if partition is already extracted
                    val extractedFolder = File(projectDir, "${partitionName}_extracted")
                    val isExtracted = extractedFolder.exists() && extractedFolder.isDirectory

                    Log.d(TAG, "  ${file.name}: $size bytes (extracted: $isExtracted)")

                    PartitionInfo(
                        name = partitionName,
                        path = file.absolutePath,
                        size = size,
                        sizeFormatted = RootSizeCalculator.formatSize(size),
                        isExtracted = isExtracted,
                        extractedPath = if (isExtracted) extractedFolder.absolutePath else null
                    )
                }.sortedBy { it.name }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(partitions = partitions)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading partitions: ${e.message}", e)
            }
        }
    }

    /**
     * Extract partition image to a folder.
     * Supports EXT4, EROFS, and other Android filesystem formats.
     */
    fun extractPartitionImage(partition: PartitionInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Extracting partition: ${partition.name}")

                // Update state to show extraction in progress
                updatePartitionState(partition.name, isExtracting = true)

                val projectDir = File(projectPath)
                val extractedFolder = File(projectDir, "${partition.name}_extracted")

                // Create extraction folder
                if (!extractedFolder.exists()) {
                    extractedFolder.mkdirs()
                    Log.d(TAG, "Created folder: ${extractedFolder.absolutePath}")
                }

                // Apply permissions using root
                Shell.cmd(
                    "mkdir -p '${extractedFolder.absolutePath}'",
                    "chmod 777 '${extractedFolder.absolutePath}'"
                ).exec()

                // Detect filesystem type by reading magic bytes
                val imgFile = File(partition.path)
                val fsType = detectFilesystemType(imgFile)
                Log.d(TAG, "Detected filesystem: $fsType")

                // TODO: Implement actual extraction based on filesystem type
                // For now, we'll create a placeholder structure and log
                when (fsType) {
                    FilesystemType.EXT4 -> {
                        Log.d(TAG, "Extracting EXT4 filesystem...")
                        // TODO: Use debugfs or mount to extract
                        extractPlaceholder(extractedFolder, "EXT4")
                    }
                    FilesystemType.EROFS -> {
                        Log.d(TAG, "Extracting EROFS filesystem...")
                        // TODO: Use erofs-utils or mount to extract
                        extractPlaceholder(extractedFolder, "EROFS")
                    }
                    FilesystemType.F2FS -> {
                        Log.d(TAG, "Extracting F2FS filesystem...")
                        // TODO: Use mount to extract
                        extractPlaceholder(extractedFolder, "F2FS")
                    }
                    FilesystemType.UNKNOWN -> {
                        Log.w(TAG, "Unknown filesystem type, creating placeholder")
                        extractPlaceholder(extractedFolder, "UNKNOWN")
                    }
                }

                // Refresh partition list
                loadExtractedPartitions()

                Log.d(TAG, "Extraction complete: ${partition.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed: ${e.message}", e)
                updatePartitionState(partition.name, isExtracting = false)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Extraction failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Update the state of a specific partition.
     */
    private suspend fun updatePartitionState(partitionName: String, isExtracting: Boolean) {
        withContext(Dispatchers.Main) {
            val updatedPartitions = _uiState.value.partitions.map { partition ->
                if (partition.name == partitionName) {
                    partition.copy(isExtracting = isExtracting)
                } else {
                    partition
                }
            }
            _uiState.value = _uiState.value.copy(partitions = updatedPartitions)
        }
    }

    /**
     * Detect filesystem type from partition image.
     */
    private fun detectFilesystemType(imgFile: File): FilesystemType {
        try {
            val buffer = ByteArray(8192)
            imgFile.inputStream().use { input ->
                input.read(buffer)
            }

            // Check for EXT4 magic (0x53EF at offset 0x438)
            if (buffer.size > 0x438 + 2) {
                val magic = ((buffer[0x438 + 1].toInt() and 0xFF) shl 8) or
                           (buffer[0x438].toInt() and 0xFF)
                if (magic == 0x53EF) {
                    return FilesystemType.EXT4
                }
            }

            // Check for EROFS magic ("E2ROFS" at offset 0x400)
            if (buffer.size > 0x400 + 6) {
                val erofsSignature = String(buffer, 0x400, 6, Charsets.US_ASCII)
                if (erofsSignature.startsWith("E2RO")) {
                    return FilesystemType.EROFS
                }
            }

            // Check for F2FS magic (0xF2F52010 at offset 0x400)
            if (buffer.size > 0x400 + 4) {
                val f2fsMagic = ((buffer[0x400].toInt() and 0xFF)) or
                               ((buffer[0x401].toInt() and 0xFF) shl 8) or
                               ((buffer[0x402].toInt() and 0xFF) shl 16) or
                               ((buffer[0x403].toInt() and 0xFF) shl 24)
                if (f2fsMagic.toLong() == 0xF2F52010L) {
                    return FilesystemType.F2FS
                }
            }

            return FilesystemType.UNKNOWN
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting filesystem: ${e.message}")
            return FilesystemType.UNKNOWN
        }
    }

    /**
     * Create placeholder extraction (temporary until real extraction is implemented).
     */
    private fun extractPlaceholder(folder: File, fsType: String) {
        // Create a README file explaining the extraction
        val readmeFile = File(folder, "README.txt")
        readmeFile.writeText("""
            Partition Extraction Placeholder
            ================================
            
            Filesystem Type: $fsType
            Status: Structure created, awaiting full extraction implementation
            
            This folder will contain the extracted files from the partition image.
            
            Implementation TODO:
            - EXT4: Use debugfs or mount with loop device
            - EROFS: Use extract.erofs or mount
            - F2FS: Use mount with loop device
            
            For now, this is a placeholder to demonstrate the UI flow.
        """.trimIndent())

        // Apply permissions
        Shell.cmd("chmod 777 '${readmeFile.absolutePath}'").exec()

        Log.d(TAG, "Created placeholder extraction in ${folder.absolutePath}")
    }

    /**
     * Open extracted partition folder in file manager.
     */
    fun openExtractedFolder(partition: PartitionInfo) {
        if (partition.extractedPath != null) {
            Log.d(TAG, "Opening folder: ${partition.extractedPath}")
            // TODO: Launch file manager intent
            // For now, just log
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Retry payload check
     */
    fun retry() {
        checkPayload()
    }
}

/**
 * Filesystem types for partition images.
 */
enum class FilesystemType {
    EXT4,
    EROFS,
    F2FS,
    UNKNOWN
}

