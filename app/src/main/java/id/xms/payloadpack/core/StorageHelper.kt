package id.xms.payloadpack.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * StorageHelper - Utility for handling Android storage permissions and path resolution.
 *
 * Android 11+ (API 30+) requires MANAGE_EXTERNAL_STORAGE for full file access.
 * This helper handles:
 * - Permission checking
 * - Permission request intent creation
 * - Path resolution (symlinks, content URIs, etc.)
 */
object StorageHelper {

    private const val TAG = "StorageHelper"

    /**
     * Check if the app has full storage access permission.
     *
     * On Android 11+, this checks MANAGE_EXTERNAL_STORAGE.
     * On older versions, this always returns true (handled by normal permissions).
     *
     * @return true if full storage access is granted
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For Android 10 and below, assume granted if app is running
            // (would have crashed at runtime permissions otherwise)
            true
        }
    }

    /**
     * Create an Intent to request MANAGE_EXTERNAL_STORAGE permission.
     *
     * Opens the system settings page for "All Files Access" for this app.
     *
     * @param context Application context
     * @return Intent to launch settings, or null if not needed (pre-Android 11)
     */
    fun createStoragePermissionIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            null
        }
    }

    /**
     * Resolve a path to its canonical absolute form.
     *
     * This handles:
     * - /sdcard/ → /storage/emulated/0/
     * - Symlink resolution
     * - Trailing slashes
     * - Content URIs (returns null for these - not supported)
     *
     * @param path Input path (may be /sdcard/..., /storage/..., or content://...)
     * @return Resolved absolute path, or null if path cannot be resolved
     */
    fun resolvePath(path: String): String? {
        Log.d(TAG, "Resolving path: $path")

        // Content URIs are not supported - need SAF for those
        if (path.startsWith("content://")) {
            Log.w(TAG, "Content URI not supported: $path")
            return null
        }

        // Handle /sdcard/ symlink - replace with actual path
        var resolvedPath = path
        if (resolvedPath.startsWith("/sdcard/")) {
            resolvedPath = resolvedPath.replaceFirst("/sdcard/", "/storage/emulated/0/")
            Log.d(TAG, "Replaced /sdcard/ prefix: $resolvedPath")
        } else if (resolvedPath == "/sdcard") {
            resolvedPath = "/storage/emulated/0"
        }

        // Also handle /sdcard without trailing content
        if (resolvedPath.startsWith("/sdcard")) {
            resolvedPath = resolvedPath.replaceFirst("/sdcard", "/storage/emulated/0")
        }

        // Handle ~ (home directory) - unlikely on Android but handle it
        if (resolvedPath.startsWith("~/")) {
            resolvedPath = resolvedPath.replaceFirst("~/", "/storage/emulated/0/")
        }

        // Try to get canonical path (resolves all symlinks)
        return try {
            val file = File(resolvedPath)
            val canonicalPath = file.canonicalPath
            Log.d(TAG, "Canonical path: $canonicalPath")
            canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get canonical path: ${e.message}")
            // Fallback to the resolved path without canonicalization
            resolvedPath
        }
    }

    /**
     * Check if a file exists and is readable.
     *
     * @param path Absolute file path
     * @return true if file exists and is readable
     */
    fun isFileReadable(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.isFile && file.canRead()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception checking file: ${e.message}")
            false
        }
    }

    /**
     * Get the file size in bytes.
     *
     * @param path Absolute file path
     * @return File size in bytes, or -1 if file doesn't exist or can't be read
     */
    fun getFileSize(path: String): Long {
        return try {
            val file = File(path)
            if (file.exists() && file.isFile) file.length() else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size: ${e.message}")
            -1L
        }
    }

    /**
     * Format file size to human-readable string.
     *
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "2.5 GB")
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Get the default payload path.
     *
     * @return Path to /sdcard/PayloadPack/payload.bin (resolved)
     */
    fun getDefaultPayloadPath(): String {
        return resolvePath("/sdcard/PayloadPack/payload.bin") 
            ?: "/storage/emulated/0/PayloadPack/payload.bin"
    }

    /**
     * Check if the default payload.bin exists.
     *
     * @return true if /sdcard/PayloadPack/payload.bin exists and is readable
     */
    fun hasDefaultPayload(): Boolean {
        return isFileReadable(getDefaultPayloadPath())
    }
}
