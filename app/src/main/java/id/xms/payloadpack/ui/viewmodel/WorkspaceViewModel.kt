package id.xms.payloadpack.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import id.xms.payloadpack.core.PermissionManager
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
    val totalBytes: Long = 0L,
    // Live extraction logs (newest first, max 100 lines)
    val extractionLogs: List<String> = emptyList(),
    // Current extracting partition name (for partition-level extraction)
    val currentPartitionExtraction: String? = null
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
    val isExtracting: Boolean = false,
    val hasPermissionConfig: Boolean = false  // True if fs_config exists for this partition
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
        private const val MAX_LOG_LINES = 100
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
     * Add a log entry to the extraction logs.
     * Thread-safe, can be called from any coroutine.
     */
    private suspend fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        
        Log.d(TAG, logEntry)
        
        withContext(Dispatchers.Main) {
            val currentLogs = _uiState.value.extractionLogs.toMutableList()
            currentLogs.add(0, logEntry)  // Add to beginning (newest first)
            if (currentLogs.size > MAX_LOG_LINES) {
                currentLogs.removeAt(currentLogs.lastIndex)  // Remove oldest
            }
            _uiState.value = _uiState.value.copy(extractionLogs = currentLogs)
        }
    }

    /**
     * Clear extraction logs.
     */
    fun clearLogs() {
        _uiState.value = _uiState.value.copy(extractionLogs = emptyList())
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
     * 
     * IMPORTANT: If .img files are found, this also updates unpackState to Success
     * so that returning to a project shows the extracted partitions instead of
     * the "Start Unpack" button.
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
                    
                    // Check if fs_config exists (permissions preserved)
                    val hasPermConfig = if (isExtracted) {
                        PermissionManager.hasPermissionConfig(extractedFolder)
                    } else {
                        false
                    }

                    Log.d(TAG, "  ${file.name}: $size bytes (extracted: $isExtracted, permConfig: $hasPermConfig)")

                    PartitionInfo(
                        name = partitionName,
                        path = file.absolutePath,
                        size = size,
                        sizeFormatted = RootSizeCalculator.formatSize(size),
                        isExtracted = isExtracted,
                        extractedPath = if (isExtracted) extractedFolder.absolutePath else null,
                        hasPermissionConfig = hasPermConfig
                    )
                }.sortedBy { it.name }

                withContext(Dispatchers.Main) {
                    // Update partitions list
                    _uiState.value = _uiState.value.copy(partitions = partitions)
                    
                    // CRITICAL FIX: If we have .img files, set unpackState to Success
                    // This ensures re-opening a project shows partitions instead of "Start Unpack"
                    if (partitions.isNotEmpty() && _uiState.value.unpackState is UnpackState.Idle) {
                        Log.d(TAG, "Found ${partitions.size} existing partitions, updating state to Success")
                        _uiState.value = _uiState.value.copy(
                            unpackState = UnpackState.Success(extractedCount = partitions.size)
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading partitions: ${e.message}", e)
            }
        }
    }

    /**
     * Extract partition image to a folder.
     * Supports EXT4, EROFS, and other Android filesystem formats.
     * Includes live logging for real-time progress display.
     */
    fun extractPartitionImage(partition: PartitionInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Starting extraction: ${partition.name}.img")
                addLog("Image size: ${partition.sizeFormatted}")
                
                // Update state to show extraction in progress
                updatePartitionState(partition.name, isExtracting = true)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        currentPartitionExtraction = partition.name
                    )
                }

                val projectDir = File(projectPath)
                val extractedFolder = File(projectDir, "${partition.name}_extracted")

                // Create extraction folder
                if (!extractedFolder.exists()) {
                    extractedFolder.mkdirs()
                    addLog("Created output folder: ${partition.name}_extracted/")
                } else {
                    addLog("Output folder exists, will overwrite: ${partition.name}_extracted/")
                }

                // Apply permissions using root
                Shell.cmd(
                    "mkdir -p '${extractedFolder.absolutePath}'",
                    "chmod 777 '${extractedFolder.absolutePath}'"
                ).exec()

                // Detect filesystem type by reading magic bytes
                val imgFile = File(partition.path)
                addLog("Detecting filesystem type...")
                val fsType = detectFilesystemType(imgFile)
                addLog("Filesystem detected: $fsType")

                // Execute extraction based on filesystem type
                val success = when (fsType) {
                    FilesystemType.EXT4 -> {
                        addLog("Using EXT4 extraction method...")
                        extractExt4Image(imgFile, extractedFolder)
                    }
                    FilesystemType.EROFS -> {
                        addLog("Using EROFS extraction method...")
                        extractErofsImage(imgFile, extractedFolder)
                    }
                    FilesystemType.BOOT -> {
                        addLog("Using boot image unpack method...")
                        extractBootImage(imgFile, extractedFolder)
                    }
                    FilesystemType.F2FS -> {
                        addLog("Using F2FS extraction method...")
                        extractF2fsImage(imgFile, extractedFolder)
                    }
                    FilesystemType.UNKNOWN -> {
                        addLog("Unknown filesystem, trying fallbacks...")
                        logFileMagicBytes(imgFile)
                        
                        // Try EROFS first (most common for modern ROMs)
                        addLog("Attempting EROFS extraction...")
                        val erofsSuccess = extractErofsImage(imgFile, extractedFolder)
                        if (erofsSuccess) {
                            addLog("EROFS extraction succeeded!")
                            true
                        } else {
                            // Try EXT4 as second fallback
                            addLog("EROFS failed, trying EXT4...")
                            val ext4Success = extractExt4Image(imgFile, extractedFolder)
                            if (ext4Success) {
                                addLog("EXT4 extraction succeeded!")
                                true
                            } else {
                                addLog("ERROR: All extraction methods failed!")
                                setExtractionError("Could not extract ${partition.name}. Tried EROFS and EXT4.")
                                false
                            }
                        }
                    }
                }

                // Update extraction state
                updatePartitionState(partition.name, isExtracting = false)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        currentPartitionExtraction = null
                    )
                }

                // Refresh partition list
                loadExtractedPartitions()

                if (success) {
                    addLog("✓ Extraction complete: ${partition.name}")
                    addLog("Files extracted to: ${partition.name}_extracted/")
                } else {
                    addLog("✗ Extraction failed: ${partition.name}")
                }

            } catch (e: Exception) {
                addLog("ERROR: ${e.message}")
                Log.e(TAG, "Extraction failed: ${e.message}", e)
                updatePartitionState(partition.name, isExtracting = false)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Extraction failed: ${e.message}",
                        currentPartitionExtraction = null
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
            // First try direct read (works for files in external storage)
            var buffer: ByteArray? = null
            
            try {
                buffer = ByteArray(8192)
                imgFile.inputStream().use { input ->
                    val bytesRead = input.read(buffer)
                    Log.d(TAG, "Direct read: $bytesRead bytes from ${imgFile.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct read failed, trying root: ${e.message}")
                buffer = null
            }
            
            // If direct read failed, use root shell
            if (buffer == null || buffer.all { it == 0.toByte() }) {
                Log.d(TAG, "Using root shell to read file header...")
                buffer = readFileHeaderWithRoot(imgFile.absolutePath, 8192)
            }
            
            if (buffer == null || buffer.isEmpty()) {
                Log.e(TAG, "Failed to read file header")
                return FilesystemType.UNKNOWN
            }
            
            // Log first 16 bytes for debugging
            val hexFirst16 = buffer.take(16).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Magic bytes (first 16): $hexFirst16")

            // Check for Android boot image magic ("ANDROID!" at offset 0)
            if (buffer.size > 8) {
                val bootSignature = String(buffer, 0, 8, Charsets.US_ASCII)
                if (bootSignature == "ANDROID!") {
                    Log.d(TAG, "Detected: BOOT image")
                    return FilesystemType.BOOT
                }
            }
            
            // Check for Sparse image magic (0x3AFF26ED at offset 0) - need to handle this!
            if (buffer.size > 4) {
                val sparseMagic = ((buffer[0].toInt() and 0xFF)) or
                                 ((buffer[1].toInt() and 0xFF) shl 8) or
                                 ((buffer[2].toInt() and 0xFF) shl 16) or
                                 ((buffer[3].toInt() and 0xFF) shl 24)
                if (sparseMagic == 0x3AFF26ED.toInt()) {
                    Log.d(TAG, "Detected: Sparse image (treating as EXT4)")
                    return FilesystemType.EXT4  // Sparse images are usually EXT4
                }
            }

            // Check for EXT4 magic (0xEF53 at offset 0x438)
            // Magic is stored as little-endian: [0x53, 0xEF] in file
            if (buffer.size > 0x438 + 2) {
                val magic = ((buffer[0x438 + 1].toInt() and 0xFF) shl 8) or
                           (buffer[0x438].toInt() and 0xFF)
                Log.d(TAG, "EXT4 magic check at 0x438: 0x${magic.toString(16).uppercase()}")
                if (magic == 0xEF53) {  // Fixed: was incorrectly 0x53EF
                    Log.d(TAG, "Detected: EXT4")
                    return FilesystemType.EXT4
                }
            }

            // Check for EROFS magic (0xE0F5E1E2 at offset 0x400)
            if (buffer.size > 0x400 + 4) {
                val erofsMagic = ((buffer[0x400].toInt() and 0xFF)) or
                               ((buffer[0x401].toInt() and 0xFF) shl 8) or
                               ((buffer[0x402].toInt() and 0xFF) shl 16) or
                               ((buffer[0x403].toInt() and 0xFF) shl 24)
                
                val hex0x400 = buffer.slice(0x400 until minOf(0x408, buffer.size))
                    .joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Bytes at 0x400: $hex0x400 (parsed: 0x${erofsMagic.toUInt().toString(16).uppercase()})")
                
                // EROFS magic: 0xE0F5E1E2 (little-endian)
                if (erofsMagic == 0xE0F5E1E2.toInt()) {
                    Log.d(TAG, "Detected: EROFS")
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
                    Log.d(TAG, "Detected: F2FS")
                    return FilesystemType.F2FS
                }
            }

            Log.w(TAG, "Could not detect filesystem type")
            return FilesystemType.UNKNOWN
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting filesystem: ${e.message}", e)
            return FilesystemType.UNKNOWN
        }
    }
    
    /**
     * Read file header using root shell (for files in /data).
     */
    private fun readFileHeaderWithRoot(filePath: String, bytes: Int): ByteArray? {
        return try {
            // Use dd to read first N bytes and base64 encode for safe transfer
            val result = Shell.cmd(
                "dd if='$filePath' bs=1 count=$bytes 2>/dev/null | base64"
            ).exec()
            
            if (result.isSuccess && result.out.isNotEmpty()) {
                val base64Data = result.out.joinToString("")
                val decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                Log.d(TAG, "Root read successful: ${decoded.size} bytes")
                decoded
            } else {
                Log.e(TAG, "Root read failed: ${result.err}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading with root: ${e.message}")
            null
        }
    }
    
    /**
     * Log raw magic bytes for debugging unknown filesystem types.
     */
    private fun logFileMagicBytes(imgFile: File) {
        try {
            val buffer = ByteArray(16)
            imgFile.inputStream().use { it.read(buffer) }
            
            val hexString = buffer.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "File magic bytes (first 16): $hexString")
            
            // Also check offset 0x400 where EROFS/F2FS magic is
            val buffer0x400 = ByteArray(8)
            imgFile.inputStream().use { input ->
                input.skip(0x400)
                input.read(buffer0x400)
            }
            val hex0x400 = buffer0x400.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "File magic bytes at 0x400: $hex0x400")
            
            // Check offset 0x438 for EXT4
            val buffer0x438 = ByteArray(4)
            imgFile.inputStream().use { input ->
                input.skip(0x438)
                input.read(buffer0x438)
            }
            val hex0x438 = buffer0x438.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "File magic bytes at 0x438 (EXT4): $hex0x438")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading magic bytes: ${e.message}")
        }
    }

    /**
     * Extract EROFS image using erofs-utils binaries.
     * Tries extract.erofs first, then fsck.erofs --extract.
     * Preserves original permissions to fs_config before applying chmod 777.
     */
    private suspend fun extractErofsImage(imgFile: File, outputFolder: File): Boolean {
        return withContext(Dispatchers.IO) {
            val binPath = id.xms.payloadpack.core.BinaryManager.getBinPath()
            if (binPath == null) {
                addLog("Error: BinaryManager not initialized")
                return@withContext false
            }
            
            val partitionName = imgFile.nameWithoutExtension
            
            // Method 1: Try extract.erofs (preferred for extraction)
            if (id.xms.payloadpack.core.BinaryManager.isBinaryAvailable("extract.erofs")) {
                addLog("Running extract.erofs...")
                
                val result = Shell.cmd(
                    "'$binPath/extract.erofs' -i '${imgFile.absolutePath}' -x -T8 '${outputFolder.absolutePath}'"
                ).exec()
                
                if (result.isSuccess) {
                    addLog("Saving permissions...")
                    val saveResult = PermissionManager.savePermissions(outputFolder, partitionName)
                    when (saveResult) {
                        is PermissionManager.SaveResult.Success -> {
                            addLog("Permissions saved: ${saveResult.fileCount} files")
                        }
                        is PermissionManager.SaveResult.Error -> {
                            addLog("Warning: ${saveResult.message}")
                        }
                    }
                    
                    addLog("Applying UI permissions...")
                    Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                    addLog("EROFS extraction successful")
                    return@withContext true
                } else {
                    addLog("extract.erofs failed, trying fsck.erofs...")
                }
            }
            
            // Method 2: Try fsck.erofs --extract
            if (id.xms.payloadpack.core.BinaryManager.isBinaryAvailable("fsck.erofs")) {
                addLog("Running fsck.erofs --extract...")
                
                val result = Shell.cmd(
                    "'$binPath/fsck.erofs' --extract='${outputFolder.absolutePath}' '${imgFile.absolutePath}'"
                ).exec()
                
                if (result.isSuccess) {
                    addLog("Saving permissions...")
                    val saveResult = PermissionManager.savePermissions(outputFolder, partitionName)
                    when (saveResult) {
                        is PermissionManager.SaveResult.Success -> {
                            addLog("Permissions saved: ${saveResult.fileCount} files")
                        }
                        is PermissionManager.SaveResult.Error -> {
                            addLog("Warning: ${saveResult.message}")
                        }
                    }
                    
                    addLog("Applying UI permissions...")
                    Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                    addLog("EROFS extraction successful")
                    return@withContext true
                } else {
                    addLog("fsck.erofs failed")
                    result.err.firstOrNull()?.let { addLog("  $it") }
                }
            }
            
            addLog("All EROFS extraction methods failed")
            false
        }
    }
    
    /**
     * Helper to set extraction error message
     */
    private suspend fun setExtractionError(message: String) {
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                errorMessage = message
            )
        }
    }

    /**
     * Extract EXT4 image using mount or simg2img.
     * Preserves original permissions to fs_config before applying chmod 777.
     */
    private suspend fun extractExt4Image(imgFile: File, outputFolder: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                addLog("Running EXT4 extraction...")
                
                val success = id.xms.payloadpack.core.BinaryManager.extractExt4(
                    imgFile.absolutePath,
                    outputFolder.absolutePath
                )

                if (success) {
                    val partitionName = imgFile.nameWithoutExtension
                    
                    // Save original permissions BEFORE chmod 777
                    addLog("Saving permissions...")
                    val saveResult = PermissionManager.savePermissions(outputFolder, partitionName)
                    when (saveResult) {
                        is PermissionManager.SaveResult.Success -> {
                            addLog("Permissions saved: ${saveResult.fileCount} files")
                        }
                        is PermissionManager.SaveResult.Error -> {
                            addLog("Warning: ${saveResult.message}")
                        }
                    }
                    
                    // Now apply chmod 777 for UI access
                    addLog("Applying UI permissions...")
                    Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                    id.xms.payloadpack.core.BinaryManager.cleanup(outputFolder.absolutePath)
                    addLog("EXT4 extraction successful")
                } else {
                    addLog("EXT4 extraction failed")
                }

                success
            } catch (e: Exception) {
                addLog("EXT4 error: ${e.message}")
                Log.e(TAG, "Error extracting EXT4: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Extract boot image using unpack_bootimg binary.
     * Preserves original permissions to fs_config before applying chmod 777.
     */
    private suspend fun extractBootImage(imgFile: File, outputFolder: File): Boolean {
        return try {
            val success = id.xms.payloadpack.core.BinaryManager.unpackBootImage(
                imgFile.absolutePath,
                outputFolder.absolutePath
            )

            if (success) {
                val partitionName = imgFile.nameWithoutExtension
                
                // Save original permissions BEFORE chmod 777
                Log.d(TAG, "Saving original permissions for $partitionName...")
                val saveResult = PermissionManager.savePermissions(outputFolder, partitionName)
                when (saveResult) {
                    is PermissionManager.SaveResult.Success -> {
                        Log.d(TAG, "Permissions saved: ${saveResult.fileCount} files")
                    }
                    is PermissionManager.SaveResult.Error -> {
                        Log.w(TAG, "Failed to save permissions: ${saveResult.message}")
                    }
                }
                
                // Now apply chmod 777 for UI access
                Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                Log.d(TAG, "Boot image unpacking successful")
            } else {
                Log.e(TAG, "Boot image unpacking failed - binary may be missing")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error unpacking boot image: ${e.message}", e)
            false
        }
    }

    /**
     * Extract F2FS image using mount.
     * Preserves original permissions to fs_config before applying chmod 777.
     */
    private suspend fun extractF2fsImage(imgFile: File, outputFolder: File): Boolean {
        return try {
            val mountPoint = "/mnt/payload_f2fs_tmp"
            val partitionName = imgFile.nameWithoutExtension

            val mountResult = Shell.cmd(
                "mkdir -p '$mountPoint'",
                "mount -t f2fs -o loop,ro '${imgFile.absolutePath}' '$mountPoint'"
            ).exec()

            if (!mountResult.isSuccess) {
                Log.e(TAG, "Failed to mount F2FS image")
                return false
            }

            // Copy files first (preserving permissions with -p flag)
            val copyResult = Shell.cmd(
                "cp -rpf '$mountPoint'/. '${outputFolder.absolutePath}/'"
            ).exec()

            Shell.cmd("umount '$mountPoint'").exec()

            if (copyResult.isSuccess) {
                // Save original permissions BEFORE chmod 777
                Log.d(TAG, "Saving original permissions for $partitionName...")
                val saveResult = PermissionManager.savePermissions(outputFolder, partitionName)
                when (saveResult) {
                    is PermissionManager.SaveResult.Success -> {
                        Log.d(TAG, "Permissions saved: ${saveResult.fileCount} files")
                    }
                    is PermissionManager.SaveResult.Error -> {
                        Log.w(TAG, "Failed to save permissions: ${saveResult.message}")
                    }
                }
                
                // Now apply chmod 777 for UI access
                Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                Log.d(TAG, "F2FS extraction successful")
                true
            } else {
                Log.e(TAG, "Failed to copy F2FS files")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting F2FS: ${e.message}", e)
            false
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

    /**
     * Restore original file permissions for a partition.
     * This should be called before repacking to restore original permissions from fs_config.
     *
     * @param partition The partition to restore permissions for
     */
    fun restorePartitionPermissions(partition: PartitionInfo) {
        if (!partition.isExtracted || partition.extractedPath == null) {
            Log.w(TAG, "Cannot restore permissions: partition not extracted")
            return
        }

        if (!partition.hasPermissionConfig) {
            Log.w(TAG, "Cannot restore permissions: no fs_config found")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Restoring permissions for: ${partition.name}")
                
                val extractedFolder = File(partition.extractedPath)
                val result = PermissionManager.restorePermissions(extractedFolder)
                
                when (result) {
                    is PermissionManager.RestoreResult.Success -> {
                        Log.d(TAG, "Permissions restored: ${result.restoredCount} files")
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                errorMessage = null
                            )
                        }
                    }
                    is PermissionManager.RestoreResult.Error -> {
                        Log.e(TAG, "Failed to restore permissions: ${result.message}")
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                errorMessage = "Failed to restore permissions: ${result.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring permissions: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Restore permissions for all extracted partitions.
     * This should be called before assembling the ROM.
     */
    fun restoreAllPermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Restoring permissions for all extracted partitions...")
                
                var totalRestored = 0
                var failures = 0
                
                for (partition in _uiState.value.partitions) {
                    if (partition.isExtracted && partition.hasPermissionConfig && partition.extractedPath != null) {
                        val extractedFolder = File(partition.extractedPath)
                        val result = PermissionManager.restorePermissions(extractedFolder)
                        
                        when (result) {
                            is PermissionManager.RestoreResult.Success -> {
                                totalRestored += result.restoredCount
                                Log.d(TAG, "  ${partition.name}: ${result.restoredCount} files restored")
                            }
                            is PermissionManager.RestoreResult.Error -> {
                                failures++
                                Log.e(TAG, "  ${partition.name}: Failed - ${result.message}")
                            }
                        }
                    }
                }
                
                Log.d(TAG, "Restore complete: $totalRestored files, $failures failures")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring all permissions: ${e.message}", e)
            }
        }
    }

    /**
     * Repack a partition from extracted folder back to .img file.
     * This will restore original permissions and use appropriate tool based on filesystem.
     */
    fun repackPartitionImage(partition: PartitionInfo) {
        if (!partition.isExtracted || partition.extractedPath == null) {
            Log.w(TAG, "Cannot repack: partition not extracted")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Starting repack: ${partition.name}")
                
                // Update state
                updatePartitionState(partition.name, isExtracting = true)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        currentPartitionExtraction = partition.name
                    )
                }

                val extractedFolder = File(partition.extractedPath)
                val projectDir = File(projectPath)
                val outputImg = File(projectDir, "${partition.name}.img")

                // Step 1: Restore original permissions if config exists
                if (partition.hasPermissionConfig) {
                    addLog("Restoring original permissions...")
                    val restoreResult = PermissionManager.restorePermissions(extractedFolder)
                    when (restoreResult) {
                        is PermissionManager.RestoreResult.Success -> {
                            addLog("Restored ${restoreResult.restoredCount} permissions")
                        }
                        is PermissionManager.RestoreResult.Error -> {
                            addLog("Warning: Failed to restore permissions: ${restoreResult.message}")
                        }
                    }
                } else {
                    addLog("Warning: No permission config found, repacking with current permissions")
                }

                // Step 2: Detect original filesystem type from the source image
                val originalImg = File(partition.path)
                val fsType = detectFilesystemType(originalImg)
                addLog("Original filesystem type: $fsType")

                // Step 3: Repack based on filesystem type
                val success = when (fsType) {
                    FilesystemType.EROFS -> {
                        addLog("Repacking as EROFS...")
                        repackAsErofs(extractedFolder, outputImg)
                    }
                    FilesystemType.EXT4 -> {
                        addLog("Repacking as EXT4...")
                        repackAsExt4(extractedFolder, outputImg, originalImg.length())
                    }
                    FilesystemType.BOOT -> {
                        addLog("Repacking boot image...")
                        repackBootImage(extractedFolder, outputImg)
                    }
                    else -> {
                        addLog("Using EXT4 as default repack format...")
                        repackAsExt4(extractedFolder, outputImg, originalImg.length())
                    }
                }

                // Update state
                updatePartitionState(partition.name, isExtracting = false)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        currentPartitionExtraction = null
                    )
                }

                if (success) {
                    addLog("✓ Repack complete: ${partition.name}")
                    addLog("Output: ${outputImg.name} (${RootSizeCalculator.formatSize(outputImg.length())})")
                } else {
                    addLog("✗ Repack failed: ${partition.name}")
                }

                // Refresh partition list
                loadExtractedPartitions()

            } catch (e: Exception) {
                addLog("ERROR: ${e.message}")
                Log.e(TAG, "Repack failed: ${e.message}", e)
                updatePartitionState(partition.name, isExtracting = false)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Repack failed: ${e.message}",
                        currentPartitionExtraction = null
                    )
                }
            }
        }
    }

    /**
     * Repack folder as EROFS image using mkfs.erofs
     */
    private suspend fun repackAsErofs(sourceFolder: File, outputImg: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val toolsDir = File("/data/local/PayloadPack")
                val mkfsErofs = File(toolsDir, "mkfs.erofs")

                if (!mkfsErofs.exists()) {
                    addLog("mkfs.erofs not found, trying to use system tool...")
                    // Try system mkfs.erofs
                    val result = Shell.cmd(
                        "mkfs.erofs -zlz4hc '${outputImg.absolutePath}' '${sourceFolder.absolutePath}'"
                    ).exec()
                    return@withContext result.isSuccess
                }

                addLog("Using PayloadPack mkfs.erofs...")
                val result = Shell.cmd(
                    "'${mkfsErofs.absolutePath}' -zlz4hc '${outputImg.absolutePath}' '${sourceFolder.absolutePath}'"
                ).exec()

                if (!result.isSuccess) {
                    result.err.forEach { addLog("  $it") }
                }

                result.isSuccess
            } catch (e: Exception) {
                addLog("EROFS repack error: ${e.message}")
                false
            }
        }
    }

    /**
     * Repack folder as EXT4 image using make_ext4fs or tar
     */
    private suspend fun repackAsExt4(sourceFolder: File, outputImg: File, targetSize: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val binPath = id.xms.payloadpack.core.BinaryManager.getBinPath()
                
                // Calculate size with 20% overhead to be safe
                val imgSize = (targetSize * 1.2).toLong()
                val imgSizeMB = imgSize / (1024 * 1024)
                val partitionName = sourceFolder.name.removeSuffix("_extracted")
                
                addLog("Target image size: ${imgSizeMB}MB")

                // Method 1: Try make_ext4fs from BinaryManager (best method)
                if (binPath != null) {
                    val makeExt4fs = File(binPath, "make_ext4fs")
                    if (makeExt4fs.exists()) {
                        addLog("Using make_ext4fs...")
                        val result = Shell.cmd(
                            "'${makeExt4fs.absolutePath}' -l ${imgSize} -a /$partitionName '${outputImg.absolutePath}' '${sourceFolder.absolutePath}'"
                        ).exec()

                        if (result.isSuccess) {
                            addLog("make_ext4fs successful")
                            return@withContext true
                        } else {
                            result.err.forEach { addLog("  $it") }
                            addLog("make_ext4fs failed, trying alternatives...")
                        }
                    }
                }

                // Method 2: Use mke2fs + mount approach
                // Android kernel restricts loop mounts from /data, so we need to work in /sdcard
                val tmpDir = "/sdcard/PayloadPack_repack"
                val tmpImgPath = "$tmpDir/${partitionName}_repack.img"
                
                addLog("Creating image in external storage (loop mount workaround)...")
                Shell.cmd("mkdir -p '$tmpDir'").exec()
                
                // Create sparse image in /sdcard
                val ddResult = Shell.cmd(
                    "dd if=/dev/zero of='$tmpImgPath' bs=1M count=0 seek=$imgSizeMB 2>/dev/null"
                ).exec()
                
                if (!ddResult.isSuccess) {
                    // Fallback: try truncate
                    val truncateResult = Shell.cmd(
                        "truncate -s ${imgSize} '$tmpImgPath'"
                    ).exec()
                    
                    if (!truncateResult.isSuccess) {
                        addLog("Failed to create image file")
                        return@withContext false
                    }
                }
                
                // Format with mke2fs
                val mke2fsPaths = listOf(
                    "/system/bin/mke2fs",
                    "/vendor/bin/mke2fs", 
                    "mke2fs"
                )
                
                var formatSuccess = false
                for (mke2fsPath in mke2fsPaths) {
                    addLog("Trying: $mke2fsPath")
                    val mkfsResult = Shell.cmd(
                        "$mke2fsPath -t ext4 -b 4096 -O ^metadata_csum '$tmpImgPath' 2>&1"
                    ).exec()
                    
                    if (mkfsResult.isSuccess) {
                        formatSuccess = true
                        addLog("Format successful")
                        break
                    }
                }
                
                if (!formatSuccess) {
                    addLog("All mke2fs attempts failed")
                    Shell.cmd("rm -f '$tmpImgPath'").exec()
                    
                    // Fallback to tar archive
                    addLog("Creating tar archive as fallback...")
                    val tarOutput = File(outputImg.parent, "${partitionName}.tar.gz")
                    val tarResult = Shell.cmd(
                        "cd '${sourceFolder.absolutePath}' && tar -czf '${tarOutput.absolutePath}' ."
                    ).exec()
                    
                    if (tarResult.isSuccess) {
                        addLog("Created tar archive: ${tarOutput.name}")
                        return@withContext true
                    }
                    
                    return@withContext false
                }
                
                // Mount and copy files - now from /sdcard which allows loop mount
                val mountPoint = "/mnt/repack_ext4_tmp"
                addLog("Mounting image...")
                
                Shell.cmd("mkdir -p '$mountPoint'").exec()
                Shell.cmd("umount '$mountPoint' 2>/dev/null").exec()
                
                // Try multiple mount methods
                var mountSuccess = false
                
                // Method A: Direct loop mount
                val mountResult = Shell.cmd(
                    "mount -t ext4 -o loop '$tmpImgPath' '$mountPoint'"
                ).exec()
                
                if (mountResult.isSuccess) {
                    mountSuccess = true
                } else {
                    addLog("Direct mount failed, trying losetup...")
                    
                    // Method B: Manual losetup
                    val findLoopResult = Shell.cmd("losetup -f 2>/dev/null || echo /dev/block/loop7").exec()
                    val loopDevice = if (findLoopResult.isSuccess && findLoopResult.out.isNotEmpty()) {
                        findLoopResult.out.first().trim()
                    } else {
                        "/dev/block/loop7"
                    }
                    
                    Shell.cmd("losetup -d '$loopDevice' 2>/dev/null").exec()
                    val losetupResult = Shell.cmd("losetup '$loopDevice' '$tmpImgPath'").exec()
                    
                    if (losetupResult.isSuccess) {
                        val mountResult2 = Shell.cmd(
                            "mount -t ext4 '$loopDevice' '$mountPoint'"
                        ).exec()
                        
                        if (mountResult2.isSuccess) {
                            mountSuccess = true
                        } else {
                            Shell.cmd("losetup -d '$loopDevice'").exec()
                        }
                    }
                }
                
                if (!mountSuccess) {
                    addLog("Mount failed - kernel may not support loop mounts")
                    Shell.cmd("rm -f '$tmpImgPath'").exec()
                    
                    // Fallback to tar
                    addLog("Creating tar archive as fallback...")
                    val tarOutput = File(outputImg.parent, "${partitionName}.tar.gz")
                    val tarResult = Shell.cmd(
                        "cd '${sourceFolder.absolutePath}' && tar -czf '${tarOutput.absolutePath}' ."
                    ).exec()
                    
                    if (tarResult.isSuccess) {
                        addLog("Created tar archive: ${tarOutput.name}")
                        return@withContext true
                    }
                    
                    return@withContext false
                }
                
                addLog("Mount successful, copying files...")
                
                // Copy files preserving permissions
                val copyResult = Shell.cmd(
                    "cp -rpf '${sourceFolder.absolutePath}'/. '$mountPoint/' 2>&1"
                ).exec()
                
                // Sync and unmount
                Shell.cmd("sync").exec()
                Shell.cmd("umount '$mountPoint'").exec()
                
                if (!copyResult.isSuccess) {
                    addLog("Copy failed: ${copyResult.err.firstOrNull()}")
                    Shell.cmd("rm -f '$tmpImgPath'").exec()
                    return@withContext false
                }
                
                addLog("Files copied successfully")
                
                // Move image back to project folder
                addLog("Moving image to project folder...")
                val moveResult = Shell.cmd(
                    "mv '$tmpImgPath' '${outputImg.absolutePath}'"
                ).exec()
                
                if (!moveResult.isSuccess) {
                    // Try copy instead
                    val cpResult = Shell.cmd(
                        "cp '$tmpImgPath' '${outputImg.absolutePath}' && rm -f '$tmpImgPath'"
                    ).exec()
                    
                    if (!cpResult.isSuccess) {
                        addLog("Failed to move image to project folder")
                        // Image is still in /sdcard
                        addLog("Image available at: $tmpImgPath")
                        return@withContext true
                    }
                }
                
                // Cleanup temp dir
                Shell.cmd("rmdir '$tmpDir' 2>/dev/null").exec()
                
                addLog("EXT4 repack complete")
                true
                
            } catch (e: Exception) {
                addLog("EXT4 repack error: ${e.message}")
                false
            }
        }
    }

    /**
     * Repack boot image using magiskboot
     */
    private suspend fun repackBootImage(sourceFolder: File, outputImg: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val toolsDir = File("/data/local/PayloadPack")
                val magiskboot = File(toolsDir, "magiskboot")

                if (!magiskboot.exists()) {
                    addLog("magiskboot not found")
                    return@withContext false
                }

                // Change to source folder and repack
                val result = Shell.cmd(
                    "cd '${sourceFolder.absolutePath}' && '${magiskboot.absolutePath}' repack '${File(sourceFolder.parent, "${sourceFolder.name.removeSuffix("_extracted")}.img").absolutePath}' '${outputImg.absolutePath}'"
                ).exec()

                if (!result.isSuccess) {
                    result.err.forEach { addLog("  $it") }
                }

                result.isSuccess
            } catch (e: Exception) {
                addLog("Boot repack error: ${e.message}")
                false
            }
        }
    }
}

/**
 * Filesystem types for partition images.
 */
enum class FilesystemType {
    EXT4,
    EROFS,
    F2FS,
    BOOT,
    UNKNOWN
}

