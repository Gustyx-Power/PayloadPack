package id.xms.payloadpack.core

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream

/**
 * BinaryManager - Manages extraction tool binaries.
 *
 * Handles installation and execution of native binaries:
 * - fsck.erofs: EROFS filesystem extraction
 * - unpack_bootimg: Boot image unpacking
 * - simg2img: Sparse image conversion
 * - make_ext4fs: EXT4 filesystem creation
 */
object BinaryManager {
    private const val TAG = "BinaryManager"
    private const val ASSETS_BIN_PATH = "bin"

    private var binDirPath: String? = null
    private var isInitialized = false

    /**
     * List of binaries to install from assets.
     */
    private val BINARIES = listOf(
        "fsck.erofs",
        "unpack_bootimg",
        "simg2img",
        "make_ext4fs"
    )

    /**
     * Install extraction tools from assets to internal storage.
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
            val installedBinaries = mutableListOf<String>()

            // Copy each binary from assets to filesDir/bin/
            for (binaryName in BINARIES) {
                try {
                    val outputFile = File(binDir, binaryName)

                    // Copy binary from assets
                    assetManager.open("$ASSETS_BIN_PATH/$binaryName").use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    Log.d(TAG, "Copied $binaryName to ${outputFile.absolutePath}")
                    installedBinaries.add(binaryName)

                } catch (e: Exception) {
                    Log.w(TAG, "Binary $binaryName not found in assets, skipping: ${e.message}")
                }
            }

            if (installedBinaries.isEmpty()) {
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
            isInitialized = true

            Log.d(TAG, "Tools installation complete: $installedBinaries")
            Log.d(TAG, "Binary directory: $binDirPath")

            return binDirPath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install tools: ${e.message}", e)
            return null
        }
    }

    /**
     * Get the path to the bin directory.
     * Must call installTools() first.
     */
    fun getBinPath(): String? = binDirPath

    /**
     * Check if a specific binary is available.
     */
    fun isBinaryAvailable(binaryName: String): Boolean {
        val binPath = binDirPath ?: return false
        val binaryFile = File(binPath, binaryName)
        return binaryFile.exists() && binaryFile.canExecute()
    }

    /**
     * Execute an extraction command using Shell.
     *
     * @param command The command to execute
     * @return Shell.Result containing output and error streams
     */
    fun executeCommand(command: String): Shell.Result {
        Log.d(TAG, "Executing: $command")

        val result = Shell.cmd(command).exec()

        if (result.isSuccess) {
            Log.d(TAG, "Command successful:")
            result.out.forEach { Log.d(TAG, "  $it") }
        } else {
            Log.e(TAG, "Command failed:")
            result.err.forEach { Log.e(TAG, "  $it") }
        }

        return result
    }

    /**
     * Extract EROFS filesystem.
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
        val result = executeCommand(command)

        return result.isSuccess
    }

    /**
     * Unpack boot image.
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
        val result = executeCommand(command)

        return result.isSuccess
    }

    /**
     * Convert sparse image to raw image, then try to mount/extract.
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
        val isSparse = isSparsImage(imagePath)

        val rawImagePath = if (isSparse && isBinaryAvailable("simg2img")) {
            // Convert sparse to raw
            val rawPath = "$outputDir/raw.img"
            val convertCmd = "$binPath/simg2img '$imagePath' '$rawPath'"
            val convertResult = executeCommand(convertCmd)

            if (!convertResult.isSuccess) {
                Log.e(TAG, "Failed to convert sparse image")
                return false
            }

            rawPath
        } else {
            imagePath
        }

        // Try to mount the image and copy files
        val mountPoint = "/mnt/payload_ext4_tmp"

        val mountResult = Shell.cmd(
            "mkdir -p '$mountPoint'",
            "mount -o loop,ro '$rawImagePath' '$mountPoint'"
        ).exec()

        if (!mountResult.isSuccess) {
            Log.e(TAG, "Failed to mount EXT4 image")
            return false
        }

        // Copy all files from mount point to output directory
        val copyResult = Shell.cmd("cp -r '$mountPoint'/* '$outputDir'/").exec()

        // Unmount
        Shell.cmd("umount '$mountPoint'").exec()

        return copyResult.isSuccess
    }

    /**
     * Check if an image is a sparse image.
     */
    private fun isSparsImage(imagePath: String): Boolean {
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

    /**
     * Clean up temporary files.
     */
    fun cleanup(outputDir: String) {
        Shell.cmd("rm -f '$outputDir/raw.img'").exec()
    }
}

