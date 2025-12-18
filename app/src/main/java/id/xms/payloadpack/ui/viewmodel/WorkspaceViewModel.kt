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

                // Execute extraction based on filesystem type
                val success = when (fsType) {
                    FilesystemType.EXT4 -> {
                        Log.d(TAG, "Extracting EXT4 filesystem...")
                        extractExt4Image(imgFile, extractedFolder)
                    }
                    FilesystemType.EROFS -> {
                        Log.d(TAG, "Extracting EROFS filesystem...")
                        extractErofsImage(imgFile, extractedFolder)
                    }
                    FilesystemType.BOOT -> {
                        Log.d(TAG, "Unpacking boot image...")
                        extractBootImage(imgFile, extractedFolder)
                    }
                    FilesystemType.F2FS -> {
                        Log.d(TAG, "Extracting F2FS filesystem...")
                        extractF2fsImage(imgFile, extractedFolder)
                    }
                    FilesystemType.UNKNOWN -> {
                        // Modern Android partitions are usually EROFS, try it as fallback
                        Log.w(TAG, "Unknown filesystem type, trying EROFS extraction as fallback...")
                        logFileMagicBytes(imgFile)
                        
                        // Try EROFS first (most common for modern ROMs)
                        val erofsSuccess = extractErofsImage(imgFile, extractedFolder)
                        if (erofsSuccess) {
                            Log.d(TAG, "EROFS fallback extraction succeeded!")
                            true
                        } else {
                            // Try EXT4 as second fallback
                            Log.d(TAG, "EROFS failed, trying EXT4...")
                            val ext4Success = extractExt4Image(imgFile, extractedFolder)
                            if (ext4Success) {
                                Log.d(TAG, "EXT4 fallback extraction succeeded!")
                                true
                            } else {
                                setExtractionError("Could not extract ${partition.name}. Tried EROFS and EXT4.")
                                false
                            }
                        }
                    }
                }

                // Update extraction state
                updatePartitionState(partition.name, isExtracting = false)

                // Refresh partition list
                loadExtractedPartitions()

                Log.d(TAG, "Extraction complete: ${partition.name} (success: $success, fsType: $fsType)")

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
     */
    private suspend fun extractErofsImage(imgFile: File, outputFolder: File): Boolean {
        val binPath = id.xms.payloadpack.core.BinaryManager.getBinPath()
        if (binPath == null) {
            Log.e(TAG, "BinaryManager not initialized")
            return false
        }
        
        // Method 1: Try extract.erofs (preferred for extraction)
        if (id.xms.payloadpack.core.BinaryManager.isBinaryAvailable("extract.erofs")) {
            Log.d(TAG, "Trying extract.erofs...")
            
            // Use root shell for files in /data
            val result = Shell.cmd(
                "'$binPath/extract.erofs' -i '${imgFile.absolutePath}' -x -T8 '${outputFolder.absolutePath}'"
            ).exec()
            
            if (result.isSuccess) {
                Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                Log.d(TAG, "extract.erofs successful")
                result.out.forEach { Log.d(TAG, "  $it") }
                return true
            } else {
                Log.w(TAG, "extract.erofs failed: ${result.err}")
            }
        }
        
        // Method 2: Try fsck.erofs --extract
        if (id.xms.payloadpack.core.BinaryManager.isBinaryAvailable("fsck.erofs")) {
            Log.d(TAG, "Trying fsck.erofs --extract...")
            
            // Use root shell for files in /data
            val result = Shell.cmd(
                "'$binPath/fsck.erofs' --extract='${outputFolder.absolutePath}' '${imgFile.absolutePath}'"
            ).exec()
            
            if (result.isSuccess) {
                Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                Log.d(TAG, "fsck.erofs extraction successful")
                result.out.forEach { Log.d(TAG, "  $it") }
                return true
            } else {
                Log.w(TAG, "fsck.erofs failed:")
                result.err.forEach { Log.e(TAG, "  $it") }
                result.out.forEach { Log.e(TAG, "  $it") }
            }
        }
        
        // Method 3: Try dump.erofs to at least verify it's a valid EROFS image
        if (id.xms.payloadpack.core.BinaryManager.isBinaryAvailable("dump.erofs")) {
            Log.d(TAG, "Checking with dump.erofs...")
            val dumpResult = Shell.cmd(
                "'$binPath/dump.erofs' '${imgFile.absolutePath}'"
            ).exec()
            
            Log.d(TAG, "dump.erofs output:")
            dumpResult.out.forEach { Log.d(TAG, "  $it") }
            dumpResult.err.forEach { Log.e(TAG, "  $it") }
        }
        
        Log.e(TAG, "All EROFS extraction methods failed")
        return false
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
     */
    private fun extractExt4Image(imgFile: File, outputFolder: File): Boolean {
        return try {
            val success = id.xms.payloadpack.core.BinaryManager.extractExt4(
                imgFile.absolutePath,
                outputFolder.absolutePath
            )

            if (success) {
                Shell.cmd("chmod -R 777 '${outputFolder.absolutePath}'").exec()
                id.xms.payloadpack.core.BinaryManager.cleanup(outputFolder.absolutePath)
                Log.d(TAG, "EXT4 extraction successful")
            } else {
                Log.e(TAG, "EXT4 extraction failed")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting EXT4: ${e.message}", e)
            false
        }
    }

    /**
     * Extract boot image using unpack_bootimg binary.
     */
    private fun extractBootImage(imgFile: File, outputFolder: File): Boolean {
        return try {
            val success = id.xms.payloadpack.core.BinaryManager.unpackBootImage(
                imgFile.absolutePath,
                outputFolder.absolutePath
            )

            if (success) {
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
     */
    private fun extractF2fsImage(imgFile: File, outputFolder: File): Boolean {
        return try {
            val mountPoint = "/mnt/payload_f2fs_tmp"

            val mountResult = Shell.cmd(
                "mkdir -p '$mountPoint'",
                "mount -t f2fs -o loop,ro '${imgFile.absolutePath}' '$mountPoint'"
            ).exec()

            if (!mountResult.isSuccess) {
                Log.e(TAG, "Failed to mount F2FS image")
                return false
            }

            val copyResult = Shell.cmd(
                "cp -r '$mountPoint'/* '${outputFolder.absolutePath}'/",
                "chmod -R 777 '${outputFolder.absolutePath}'"
            ).exec()

            Shell.cmd("umount '$mountPoint'").exec()

            if (copyResult.isSuccess) {
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

