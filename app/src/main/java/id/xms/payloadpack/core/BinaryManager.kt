package id.xms.payloadpack.core

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * BinaryManager - A robust singleton for managing native binary executables.
 *
 * This object handles the complete lifecycle of native binaries in Android:
 * - Copies binaries from assets/bin/ to internal storage (filesDir/bin/)
 * - Makes them executable (chmod 755)
 * - Executes commands using ProcessBuilder on IO dispatcher
 *
 * ## Usage Example:
 * ```kotlin
 * // In a ViewModel or coroutine scope:
 * viewModelScope.launch {
 *     // Step 1: Prepare binaries (do this once, e.g., on app startup)
 *     val prepareResult = BinaryManager.prepareBinaries(context)
 *     if (prepareResult is BinaryManager.PrepareResult.Success) {
 *         // Step 2: Execute a command
 *         val execResult = BinaryManager.executeCommand(listOf("mkfs.erofs", "--version"))
 *         when (execResult) {
 *             is BinaryManager.ExecutionResult.Success -> {
 *                 Log.d("TAG", "Output: ${execResult.output}")
 *             }
 *             is BinaryManager.ExecutionResult.Error -> {
 *                 Log.e("TAG", "Error: ${execResult.message}")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @see PrepareResult for binary preparation outcomes
 * @see ExecutionResult for command execution outcomes
 */
object BinaryManager {

    private const val TAG = "BinaryManager"
    private const val ASSETS_BIN_PATH = "bin"
    private const val VERSION_FILE = ".version"

    /**
     * Internal state tracking.
     */
    @Volatile
    private var binDirPath: String? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var installedBinaries: List<String> = emptyList()

    // =========================================================================
    // Result Types
    // =========================================================================

    /**
     * Sealed class representing the result of binary preparation.
     */
    sealed class PrepareResult {
        /**
         * Binaries were prepared successfully.
         *
         * @param binDirectory Absolute path to the bin directory
         * @param installedBinaries List of successfully installed binary names
         * @param wasUpdated True if binaries were copied (fresh install or update), false if already present
         */
        data class Success(
            val binDirectory: String,
            val installedBinaries: List<String>,
            val wasUpdated: Boolean
        ) : PrepareResult()

        /**
         * Binary preparation failed.
         *
         * @param message Human-readable error message
         * @param exception Optional underlying exception
         */
        data class Error(
            val message: String,
            val exception: Throwable? = null
        ) : PrepareResult()
    }

    /**
     * Sealed class representing the result of command execution.
     */
    sealed class ExecutionResult {
        /**
         * Command executed successfully.
         *
         * @param output Combined stdout and stderr output
         * @param exitCode The process exit code (0 typically means success)
         */
        data class Success(
            val output: String,
            val exitCode: Int
        ) : ExecutionResult()

        /**
         * Command execution failed.
         *
         * @param message Human-readable error message
         * @param output Any output captured before failure (may be partial)
         * @param exitCode The process exit code, if available (-1 if process didn't start)
         * @param exception Optional underlying exception
         */
        data class Error(
            val message: String,
            val output: String = "",
            val exitCode: Int = -1,
            val exception: Throwable? = null
        ) : ExecutionResult()
    }

    // =========================================================================
    // Public API - Binary Preparation
    // =========================================================================

    /**
     * Prepare binaries for execution by copying them from assets to internal storage.
     *
     * This function:
     * 1. Checks if binaries exist in filesDir/bin/
     * 2. If not (or if forceUpdate is true), copies them from assets/bin/
     * 3. Makes all binaries executable (chmod 755)
     *
     * This operation runs on [Dispatchers.IO] to avoid blocking the main thread.
     *
     * @param context Application context (use applicationContext for long-lived operations)
     * @param forceUpdate If true, re-copy binaries even if they already exist
     * @return [PrepareResult.Success] with bin directory path, or [PrepareResult.Error] on failure
     */
    suspend fun prepareBinaries(
        context: Context,
        forceUpdate: Boolean = false
    ): PrepareResult = withContext(Dispatchers.IO) {
        try {
            // Check if already initialized and no force update
            if (isInitialized && binDirPath != null && !forceUpdate) {
                Log.d(TAG, "Binaries already prepared at: $binDirPath")
                return@withContext PrepareResult.Success(
                    binDirectory = binDirPath!!,
                    installedBinaries = installedBinaries,
                    wasUpdated = false
                )
            }

            Log.d(TAG, "Preparing binaries (forceUpdate=$forceUpdate)...")

            // Create bin directory in app's internal storage
            val binDir = File(context.filesDir, "bin")
            if (!binDir.exists() && !binDir.mkdirs()) {
                return@withContext PrepareResult.Error(
                    message = "Failed to create bin directory: ${binDir.absolutePath}"
                )
            }

            val assetManager = context.assets

            // Discover available binaries in assets/bin/
            val availableBinaries = try {
                assetManager.list(ASSETS_BIN_PATH)?.toList() ?: emptyList()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to list assets: ${e.message}")
                return@withContext PrepareResult.Error(
                    message = "Failed to list binaries in assets",
                    exception = e
                )
            }

            if (availableBinaries.isEmpty()) {
                return@withContext PrepareResult.Error(
                    message = "No binaries found in assets/$ASSETS_BIN_PATH"
                )
            }

            Log.d(TAG, "Found ${availableBinaries.size} binaries in assets: $availableBinaries")

            // Check version to determine if update is needed
            val versionFile = File(binDir, VERSION_FILE)
            val currentVersion = getAppVersionCode(context)
            val installedVersion = if (versionFile.exists()) {
                versionFile.readText().trim().toLongOrNull() ?: 0
            } else {
                0
            }

            val needsUpdate = forceUpdate || installedVersion < currentVersion ||
                    !allBinariesExist(binDir, availableBinaries)

            if (!needsUpdate) {
                Log.d(TAG, "Binaries are up-to-date (version $installedVersion)")
                binDirPath = binDir.absolutePath
                installedBinaries = availableBinaries
                isInitialized = true
                return@withContext PrepareResult.Success(
                    binDirectory = binDir.absolutePath,
                    installedBinaries = availableBinaries,
                    wasUpdated = false
                )
            }

            // Copy binaries from assets
            val installed = mutableListOf<String>()
            for (binaryName in availableBinaries) {
                val result = copyBinaryFromAssets(context, binaryName, binDir)
                if (result) {
                    installed.add(binaryName)
                } else {
                    Log.w(TAG, "Failed to copy binary: $binaryName")
                }
            }

            if (installed.isEmpty()) {
                return@withContext PrepareResult.Error(
                    message = "Failed to install any binaries"
                )
            }

            // Make all binaries executable
            val chmodSuccess = makeBinariesExecutable(binDir, installed)
            if (!chmodSuccess) {
                return@withContext PrepareResult.Error(
                    message = "Failed to make binaries executable"
                )
            }

            // Write version file
            try {
                versionFile.writeText(currentVersion.toString())
            } catch (e: IOException) {
                Log.w(TAG, "Failed to write version file: ${e.message}")
                // Non-fatal, continue
            }

            binDirPath = binDir.absolutePath
            installedBinaries = installed
            isInitialized = true

            Log.d(TAG, "Successfully prepared ${installed.size} binaries at: ${binDir.absolutePath}")

            PrepareResult.Success(
                binDirectory = binDir.absolutePath,
                installedBinaries = installed,
                wasUpdated = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare binaries: ${e.message}", e)
            PrepareResult.Error(
                message = "Binary preparation failed: ${e.message}",
                exception = e
            )
        }
    }

    // =========================================================================
    // Public API - Command Execution
    // =========================================================================

    /**
     * Execute a command using the installed binaries.
     *
     * This function uses [ProcessBuilder] for reliable process management with:
     * - Proper argument handling (no shell injection vulnerabilities)
     * - Error stream redirected to stdout for complete log capture
     * - Execution on [Dispatchers.IO]
     *
     * @param args List of command arguments. The first element can be:
     *             - A binary name (e.g., "mkfs.erofs") - will be resolved to full path
     *             - A full path to any executable
     * @param workingDirectory Optional working directory for the command
     * @param timeoutMillis Maximum execution time in milliseconds (default: 5 minutes)
     * @return [ExecutionResult.Success] with output, or [ExecutionResult.Error] on failure
     *
     * @throws IllegalStateException if [prepareBinaries] has not been called
     *
     * ## Example:
     * ```kotlin
     * // Execute mkfs.erofs --version
     * val result = BinaryManager.executeCommand(listOf("mkfs.erofs", "--version"))
     *
     * // Execute with full path
     * val result = BinaryManager.executeCommand(listOf("/data/data/.../bin/mkfs.erofs", "-h"))
     * ```
     */
    suspend fun executeCommand(
        args: List<String>,
        workingDirectory: File? = null,
        timeoutMillis: Long = 5 * 60 * 1000L
    ): ExecutionResult = withContext(Dispatchers.IO) {
        if (args.isEmpty()) {
            return@withContext ExecutionResult.Error(
                message = "Command cannot be empty"
            )
        }

        val binPath = binDirPath
        if (binPath == null) {
            return@withContext ExecutionResult.Error(
                message = "BinaryManager not initialized. Call prepareBinaries() first."
            )
        }

        try {
            // Resolve binary name to full path if needed
            val resolvedArgs = args.toMutableList()
            val firstArg = resolvedArgs[0]

            // If it's just a binary name (no path separators), resolve it
            if (!firstArg.contains(File.separator)) {
                val binaryFile = File(binPath, firstArg)
                if (binaryFile.exists()) {
                    resolvedArgs[0] = binaryFile.absolutePath
                } else {
                    return@withContext ExecutionResult.Error(
                        message = "Binary not found: $firstArg (looked in $binPath)"
                    )
                }
            }

            Log.d(TAG, "Executing command: ${resolvedArgs.joinToString(" ")}")

            // Build and configure the process
            val processBuilder = ProcessBuilder(resolvedArgs).apply {
                // Redirect stderr to stdout for unified output
                redirectErrorStream(true)

                // Set working directory if specified
                workingDirectory?.let { directory(it) }

                // Add bin directory to PATH
                environment()["PATH"] = "$binPath:${environment()["PATH"]}"
            }

            val process = processBuilder.start()

            // Read output in a separate thread-safe manner
            val output = StringBuilder()
            val reader: BufferedReader = process.inputStream.bufferedReader()

            try {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        Log.v(TAG, "Output: $line")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Error reading process output: ${e.message}")
            }

            // Wait for process completion with timeout
            val completed = process.waitFor(
                timeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )

            if (!completed) {
                process.destroyForcibly()
                return@withContext ExecutionResult.Error(
                    message = "Command timed out after ${timeoutMillis}ms",
                    output = output.toString().trim()
                )
            }

            val exitCode = process.exitValue()
            val outputStr = output.toString().trim()

            Log.d(TAG, "Command completed with exit code: $exitCode")

            if (exitCode == 0) {
                ExecutionResult.Success(
                    output = outputStr,
                    exitCode = exitCode
                )
            } else {
                // Non-zero exit code - still return output for debugging
                ExecutionResult.Error(
                    message = "Command failed with exit code $exitCode",
                    output = outputStr,
                    exitCode = exitCode
                )
            }

        } catch (e: IOException) {
            Log.e(TAG, "IO error executing command: ${e.message}", e)
            ExecutionResult.Error(
                message = "Failed to execute command: ${e.message}",
                exception = e
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error: ${e.message}", e)
            ExecutionResult.Error(
                message = "Permission denied: ${e.message}",
                exception = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}", e)
            ExecutionResult.Error(
                message = "Unexpected error: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Convenience overload accepting a command string.
     *
     * The command string will be split on whitespace. For commands with
     * arguments containing spaces, use the List<String> overload instead.
     *
     * @param command Space-separated command string (e.g., "mkfs.erofs --version")
     * @return [ExecutionResult]
     */
    suspend fun executeCommand(command: String): ExecutionResult {
        return executeCommand(command.split("\\s+".toRegex()))
    }

    // =========================================================================
    // Public API - Utility Functions
    // =========================================================================

    /**
     * Get the path to the bin directory.
     *
     * @return Absolute path to bin directory, or null if [prepareBinaries] hasn't been called
     */
    fun getBinPath(): String? = binDirPath

    /**
     * Get the full path to a specific binary.
     *
     * @param binaryName Name of the binary (e.g., "mkfs.erofs")
     * @return Full path to the binary, or null if not found
     */
    fun getBinaryPath(binaryName: String): String? {
        val binPath = binDirPath ?: return null
        val file = File(binPath, binaryName)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Check if a specific binary is available and executable.
     *
     * @param binaryName Name of the binary to check
     * @return true if the binary exists and is executable
     */
    fun isBinaryAvailable(binaryName: String): Boolean {
        val binPath = binDirPath ?: return false
        val binaryFile = File(binPath, binaryName)
        return binaryFile.exists() && binaryFile.canExecute()
    }

    /**
     * Get list of all installed binaries.
     *
     * @return List of binary names, or empty list if not initialized
     */
    fun getInstalledBinaries(): List<String> = installedBinaries.toList()

    /**
     * Check if BinaryManager is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized && binDirPath != null

    // =========================================================================
    // Legacy Shell-based API (for operations requiring root)
    // =========================================================================

    /**
     * Execute a command using libsu Shell (for root operations).
     *
     * Use this when the command requires root permissions.
     *
     * @param command The command to execute
     * @return Shell.Result containing output and error streams
     */
    fun executeShellCommand(command: String): Shell.Result {
        Log.d(TAG, "Executing shell command: $command")
        val result = Shell.cmd(command).exec()

        if (result.isSuccess) {
            Log.d(TAG, "Shell command successful")
            result.out.forEach { Log.v(TAG, "  $it") }
        } else {
            Log.e(TAG, "Shell command failed")
            result.err.forEach { Log.e(TAG, "  $it") }
        }

        return result
    }

    // =========================================================================
    // Legacy API - Backward Compatibility
    // =========================================================================

    /**
     * Install extraction tools from assets to internal storage.
     * 
     * **Legacy API** - Consider using [prepareBinaries] for new code.
     *
     * @param context Application context
     * @return Absolute path to the bin directory, or null if failed
     */
    fun installTools(context: Context): String? {
        if (isInitialized && binDirPath != null) {
            Log.d(TAG, "Tools already installed at: $binDirPath")
            return binDirPath
        }

        try {
            Log.d(TAG, "Installing extraction tools...")

            // Create bin directory in app's files directory
            val binDir = File(context.filesDir, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
                Log.d(TAG, "Created bin directory: ${binDir.absolutePath}")
            }

            val assetManager = context.assets
            val installed = mutableListOf<String>()

            // Discover available binaries in assets/bin/
            val availableBinaries = try {
                assetManager.list(ASSETS_BIN_PATH)?.toList() ?: emptyList()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to list assets: ${e.message}")
                return null
            }

            // Copy each binary from assets to filesDir/bin/
            for (binaryName in availableBinaries) {
                try {
                    val outputFile = File(binDir, binaryName)

                    // Copy binary from assets
                    assetManager.open("$ASSETS_BIN_PATH/$binaryName").use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    Log.d(TAG, "Copied $binaryName to ${outputFile.absolutePath}")
                    installed.add(binaryName)

                } catch (e: Exception) {
                    Log.w(TAG, "Binary $binaryName not found in assets, skipping: ${e.message}")
                }
            }

            if (installed.isEmpty()) {
                Log.e(TAG, "No binaries were installed")
                return null
            }

            // Make all binaries executable using root
            val chmodResult = Shell.cmd(
                "chmod 755 ${binDir.absolutePath}/*",
                "ls -la ${binDir.absolutePath}"
            ).exec()

            if (chmodResult.isSuccess) {
                Log.d(TAG, "Successfully made binaries executable:")
                chmodResult.out.forEach { Log.d(TAG, "  $it") }
            } else {
                Log.e(TAG, "Failed to chmod binaries:")
                chmodResult.err.forEach { Log.e(TAG, "  $it") }
                return null
            }

            binDirPath = binDir.absolutePath
            installedBinaries = installed
            isInitialized = true

            Log.d(TAG, "Tools installation complete: $installed")
            Log.d(TAG, "Binary directory: $binDirPath")

            return binDirPath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install tools: ${e.message}", e)
            return null
        }
    }

    /**
     * Extract EROFS filesystem.
     * 
     * **Legacy API** - Uses Shell for execution.
     *
     * @param imagePath Path to the EROFS image file
     * @param outputDir Directory where files will be extracted
     * @return true if extraction succeeded
     */
    fun extractErofs(imagePath: String, outputDir: String): Boolean {
        val binPath = binDirPath ?: return false

        if (!isBinaryAvailable("fsck.erofs")) {
            Log.e(TAG, "fsck.erofs binary not available")
            return false
        }

        // Create output directory
        Shell.cmd("mkdir -p '$outputDir'").exec()

        // Execute: fsck.erofs --extract={outputDir} {imagePath}
        val command = "$binPath/fsck.erofs --extract='$outputDir' '$imagePath'"
        val result = executeShellCommand(command)

        return result.isSuccess
    }

    /**
     * Convert sparse image to raw image, then try to mount/extract.
     * 
     * **Legacy API** - Uses Shell for execution.
     *
     * @param imagePath Path to the sparse/ext4 image file
     * @param outputDir Directory where files will be extracted
     * @return true if extraction succeeded
     */
    fun extractExt4(imagePath: String, outputDir: String): Boolean {
        val binPath = binDirPath ?: return false

        // Create output directory
        Shell.cmd("mkdir -p '$outputDir'").exec()

        // Check if image is sparse
        val isSparse = isSparseImage(imagePath)
        Log.d(TAG, "EXT4 image sparse: $isSparse")

        val rawImagePath = if (isSparse && isBinaryAvailable("simg2img")) {
            // Convert sparse to raw
            val rawPath = "$outputDir/raw.img"
            val convertCmd = "$binPath/simg2img '$imagePath' '$rawPath'"
            Log.d(TAG, "Converting sparse image: $convertCmd")
            val convertResult = executeShellCommand(convertCmd)

            if (!convertResult.isSuccess) {
                Log.e(TAG, "Failed to convert sparse image")
                return false
            }

            rawPath
        } else {
            imagePath
        }

        // Android kernel restricts loop mounting from /data
        // Workaround: Copy image to /sdcard which allows loop mounts
        val tmpDir = "/sdcard/PayloadPack_tmp"
        val imageName = File(rawImagePath).name
        val tmpImagePath = "$tmpDir/$imageName"
        var usedTmpCopy = false
        
        Shell.cmd("mkdir -p '$tmpDir'").exec()
        
        // Try to copy to external storage for loop mount
        Log.d(TAG, "Copying image to external storage for loop mount...")
        val copyToTmpResult = Shell.cmd(
            "cp '$rawImagePath' '$tmpImagePath'"
        ).exec()
        
        val mountImagePath = if (copyToTmpResult.isSuccess) {
            usedTmpCopy = true
            Log.d(TAG, "Image copied to: $tmpImagePath")
            tmpImagePath
        } else {
            Log.w(TAG, "Failed to copy to external storage, trying direct mount...")
            rawImagePath
        }

        // Try to mount the image and copy files
        val mountPoint = "/mnt/payload_ext4_tmp"
        Log.d(TAG, "Attempting to mount EXT4 image at $mountPoint")

        // Clean up any existing mount
        Shell.cmd("umount '$mountPoint' 2>/dev/null").exec()
        Shell.cmd("rm -rf '$mountPoint'").exec()
        Shell.cmd("mkdir -p '$mountPoint'").exec()

        // Method 1: Try direct loop mount
        var mountResult = Shell.cmd(
            "mount -t ext4 -o loop,ro '$mountImagePath' '$mountPoint'"
        ).exec()
        
        if (!mountResult.isSuccess) {
            Log.w(TAG, "Direct loop mount failed: ${mountResult.err}")
            
            // Method 2: Try with explicit losetup
            // Find a free loop device
            val findLoopResult = Shell.cmd("losetup -f 2>/dev/null || echo /dev/block/loop0").exec()
            val loopDevice = if (findLoopResult.isSuccess && findLoopResult.out.isNotEmpty()) {
                findLoopResult.out.first().trim()
            } else {
                "/dev/block/loop0"
            }
            
            Log.d(TAG, "Trying losetup with: $loopDevice")
            
            // Detach any existing loop
            Shell.cmd("losetup -d '$loopDevice' 2>/dev/null").exec()
            
            val losetupResult = Shell.cmd(
                "losetup '$loopDevice' '$mountImagePath'"
            ).exec()
            
            if (losetupResult.isSuccess) {
                mountResult = Shell.cmd(
                    "mount -t ext4 -o ro '$loopDevice' '$mountPoint'"
                ).exec()
                
                if (!mountResult.isSuccess) {
                    Log.e(TAG, "Mount failed after losetup: ${mountResult.err}")
                    Shell.cmd("losetup -d '$loopDevice'").exec()
                }
            } else {
                Log.e(TAG, "losetup failed: ${losetupResult.err}")
            }
        }

        if (!mountResult.isSuccess) {
            Log.e(TAG, "All mount methods failed for EXT4 image")
            Log.e(TAG, "This kernel may not support loop mounts from this location")
            
            // Cleanup temp file
            if (usedTmpCopy) {
                Shell.cmd("rm -f '$tmpImagePath'").exec()
            }
            
            return false
        }
        
        Log.d(TAG, "Mount successful, copying files...")

        // Copy all files from mount point to output directory
        val copyResult = Shell.cmd(
            "cp -rpf '$mountPoint'/. '$outputDir'/"
        ).exec()

        // Unmount and cleanup
        Shell.cmd("umount '$mountPoint'").exec()
        
        // Cleanup temp file
        if (usedTmpCopy) {
            Shell.cmd("rm -f '$tmpImagePath'").exec()
        }

        if (copyResult.isSuccess) {
            Log.d(TAG, "EXT4 extraction successful")
        } else {
            Log.e(TAG, "Copy failed: ${copyResult.err}")
        }

        return copyResult.isSuccess
    }

    /**
     * Unpack boot image.
     * 
     * **Legacy API** - Uses Shell for execution.
     *
     * @param imagePath Path to the boot image file
     * @param outputDir Directory where boot components will be unpacked
     * @return true if unpacking succeeded
     */
    fun unpackBootImage(imagePath: String, outputDir: String): Boolean {
        val binPath = binDirPath ?: return false

        if (!isBinaryAvailable("unpack_bootimg")) {
            Log.e(TAG, "unpack_bootimg binary not available")
            return false
        }

        // Create output directory
        Shell.cmd("mkdir -p '$outputDir'").exec()

        // Execute: unpack_bootimg -i {imagePath} -o {outputDir}
        val command = "$binPath/unpack_bootimg -i '$imagePath' -o '$outputDir'"
        val result = executeShellCommand(command)

        return result.isSuccess
    }

    /**
     * Clean up temporary files.
     * 
     * **Legacy API**
     */
    fun cleanup(outputDir: String) {
        Shell.cmd("rm -f '$outputDir/raw.img'").exec()
    }

    /**
     * Check if an image is a sparse image.
     */
    private fun isSparseImage(imagePath: String): Boolean {
        try {
            val file = File(imagePath)
            val buffer = ByteArray(4)
            file.inputStream().use { it.read(buffer) }

            // Sparse image magic: 0xED 0x26 0xFF 0x3A
            return buffer[0] == 0xED.toByte() &&
                   buffer[1] == 0x26.toByte() &&
                   buffer[2] == 0xFF.toByte() &&
                   buffer[3] == 0x3A.toByte()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking sparse image: ${e.message}")
            return false
        }
    }

    // =========================================================================
    // Private Helper Functions
    // =========================================================================

    /**
     * Copy a single binary from assets to the bin directory.
     */
    private fun copyBinaryFromAssets(context: Context, binaryName: String, binDir: File): Boolean {
        return try {
            val outputFile = File(binDir, binaryName)
            context.assets.open("$ASSETS_BIN_PATH/$binaryName").use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 8192)
                }
            }
            Log.d(TAG, "Copied $binaryName (${outputFile.length()} bytes)")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy $binaryName: ${e.message}")
            false
        }
    }

    /**
     * Make all binaries executable using multiple fallback methods.
     */
    private fun makeBinariesExecutable(binDir: File, binaries: List<String>): Boolean {
        var success = true

        for (binaryName in binaries) {
            val file = File(binDir, binaryName)
            if (!file.exists()) continue

            // Method 1: Try Java's setExecutable()
            val javaSuccess = file.setExecutable(true, false) &&
                    file.setReadable(true, false)

            if (javaSuccess && file.canExecute()) {
                Log.d(TAG, "Made $binaryName executable via Java API")
                continue
            }

            // Method 2: Fallback to libsu Shell (for rooted devices or if Java fails)
            val shellResult = Shell.cmd("chmod 755 '${file.absolutePath}'").exec()
            if (shellResult.isSuccess) {
                Log.d(TAG, "Made $binaryName executable via Shell")
                continue
            }

            // Method 3: Try ProcessBuilder chmod as last resort
            try {
                val chmodProcess = ProcessBuilder("chmod", "755", file.absolutePath).start()
                val exitCode = chmodProcess.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "Made $binaryName executable via ProcessBuilder chmod")
                    continue
                }
            } catch (e: Exception) {
                Log.w(TAG, "ProcessBuilder chmod failed for $binaryName: ${e.message}")
            }

            Log.e(TAG, "Failed to make $binaryName executable")
            success = false
        }

        return success
    }

    /**
     * Check if all expected binaries exist in the bin directory.
     */
    private fun allBinariesExist(binDir: File, binaries: List<String>): Boolean {
        return binaries.all { File(binDir, it).exists() }
    }

    /**
     * Get app version code for tracking binary updates.
     */
    private fun getAppVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code: ${e.message}")
            1L
        }
    }
}
