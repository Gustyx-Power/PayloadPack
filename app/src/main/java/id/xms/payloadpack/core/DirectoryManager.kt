package id.xms.payloadpack.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DirectoryManager - Singleton for managing PayloadPack directories.
 *
 * This app requires special directory handling due to the nature of ROM files:
 * - Standard Storage (/sdcard): FAT/FUSE emulation - Cannot store symlinks/permissions
 * - Working Storage (/data): EXT4/F2FS - Supports full Linux attributes
 *
 * The working directory in /data is essential for preserving file permissions,
 * symlinks, and other Linux-specific attributes during ROM unpacking/repacking.
 */
object DirectoryManager {

    private const val TAG = "DirectoryManager"

    /**
     * User-accessible folder for input/output files.
     * Located on sdcard for easy file transfer via USB/MTP.
     */
    const val USER_DIR = "/sdcard/PayloadPack"

    /**
     * Internal working directory for processing.
     * Located on /data for full Linux attribute support (permissions, symlinks, etc.)
     */
    const val WORK_DIR = "/data/PayloadPack"

    /**
     * SELinux context for the working directory.
     * Using system_data_file context to prevent SELinux denials.
     */
    private const val SELINUX_CONTEXT = "u:object_r:system_data_file:s0"

    /**
     * Result of workspace initialization.
     */
    sealed class InitResult {
        data object Success : InitResult()
        data class Error(val message: String) : InitResult()
    }

    /**
     * Check if root access is available.
     *
     * @return true if root shell is available, false otherwise
     */
    fun isRootAvailable(): Boolean {
        return Shell.isAppGrantedRoot() == true
    }

    /**
     * Request root access from the user.
     * This will trigger the Superuser permission dialog if not already granted.
     *
     * @return true if root access was granted, false otherwise
     */
    suspend fun requestRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            // This triggers the root permission dialog
            Shell.getShell()
            val granted = Shell.isAppGrantedRoot() == true
            Log.d(TAG, "Root access granted: $granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request root access", e)
            false
        }
    }

    /**
     * Initialize the workspace directories.
     *
     * This function requires root access and will:
     * 1. Create the user directory at /sdcard/PayloadPack
     * 2. Create the working directory at /data/PayloadPack
     * 3. Set appropriate SELinux context to prevent denials
     * 4. Set permissions for the working directory
     *
     * @return InitResult indicating success or failure with message
     */
    suspend fun initializeWorkspace(): InitResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing workspace...")

        if (!isRootAvailable()) {
            return@withContext InitResult.Error("Root access is required but not available")
        }

        try {
            // Step 1: Create user directory on /sdcard
            // This can use standard mkdir as /sdcard is accessible
            val userDirResult = Shell.cmd("mkdir -p $USER_DIR").exec()
            if (!userDirResult.isSuccess) {
                val error = userDirResult.err.joinToString("\n")
                Log.e(TAG, "Failed to create user directory: $error")
                return@withContext InitResult.Error("Failed to create user directory: $error")
            }
            Log.d(TAG, "User directory created: $USER_DIR")

            // Step 2: Create working directory on /data
            // Must use root shell as /data is not accessible to apps
            val workDirResult = Shell.cmd("mkdir -p $WORK_DIR").exec()
            if (!workDirResult.isSuccess) {
                val error = workDirResult.err.joinToString("\n")
                Log.e(TAG, "Failed to create working directory: $error")
                return@withContext InitResult.Error("Failed to create working directory: $error")
            }
            Log.d(TAG, "Working directory created: $WORK_DIR")

            // Step 3: Set SELinux context to prevent denials
            // This is crucial for accessing the directory without SELinux blocking operations
            val selinuxResult = Shell.cmd("chcon $SELINUX_CONTEXT $WORK_DIR").exec()
            if (!selinuxResult.isSuccess) {
                val error = selinuxResult.err.joinToString("\n")
                Log.w(TAG, "Failed to set SELinux context (may not be critical): $error")
                // Don't return error here as some devices may have SELinux disabled
            } else {
                Log.d(TAG, "SELinux context set: $SELINUX_CONTEXT")
            }

            // Step 4: Set permissions (777 for full access during development)
            // In production, you might want more restrictive permissions
            val chmodResult = Shell.cmd("chmod 777 $WORK_DIR").exec()
            if (!chmodResult.isSuccess) {
                val error = chmodResult.err.joinToString("\n")
                Log.w(TAG, "Failed to set permissions: $error")
            } else {
                Log.d(TAG, "Permissions set on working directory")
            }

            // Step 5: Create subdirectories for organization
            val subdirs = listOf(
                "$WORK_DIR/extract",    // For extracted ROM contents
                "$WORK_DIR/build",      // For building repacked ROMs
                "$WORK_DIR/temp"        // For temporary files
            )

            for (subdir in subdirs) {
                val subdirResult = Shell.cmd("mkdir -p $subdir").exec()
                if (!subdirResult.isSuccess) {
                    Log.w(TAG, "Failed to create subdirectory: $subdir")
                }
            }

            Log.i(TAG, "Workspace initialized successfully")
            InitResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Exception during workspace initialization", e)
            InitResult.Error("Exception: ${e.message}")
        }
    }

    /**
     * Verify that the workspace directories exist and are accessible.
     *
     * @return true if all directories exist and are accessible
     */
    suspend fun verifyWorkspace(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false

        try {
            // Check user directory
            val userDirCheck = Shell.cmd("test -d $USER_DIR && echo 'exists'").exec()
            val userDirExists = userDirCheck.out.any { it.contains("exists") }

            // Check working directory
            val workDirCheck = Shell.cmd("test -d $WORK_DIR && echo 'exists'").exec()
            val workDirExists = workDirCheck.out.any { it.contains("exists") }

            Log.d(TAG, "Workspace verification - User: $userDirExists, Work: $workDirExists")
            userDirExists && workDirExists

        } catch (e: Exception) {
            Log.e(TAG, "Exception during workspace verification", e)
            false
        }
    }

    /**
     * Clean the working directory.
     * This removes all files in the working directory but preserves the directory structure.
     *
     * @return true if cleanup was successful
     */
    suspend fun cleanWorkspace(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false

        try {
            val result = Shell.cmd(
                "rm -rf $WORK_DIR/extract/*",
                "rm -rf $WORK_DIR/build/*",
                "rm -rf $WORK_DIR/temp/*"
            ).exec()

            val success = result.isSuccess
            Log.d(TAG, "Workspace cleanup: $success")
            success

        } catch (e: Exception) {
            Log.e(TAG, "Exception during workspace cleanup", e)
            false
        }
    }

    /**
     * Get workspace disk usage information.
     *
     * @return Pair of (used bytes, available bytes) or null if unavailable
     */
    suspend fun getWorkspaceUsage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext null

        try {
            // Get disk usage using df command
            val result = Shell.cmd("df $WORK_DIR | tail -1 | awk '{print \$3, \$4}'").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                val parts = result.out.first().split(" ")
                if (parts.size >= 2) {
                    val used = parts[0].toLongOrNull()?.times(1024) ?: 0L
                    val available = parts[1].toLongOrNull()?.times(1024) ?: 0L
                    return@withContext Pair(used, available)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get workspace usage", e)
            null
        }
    }
}
