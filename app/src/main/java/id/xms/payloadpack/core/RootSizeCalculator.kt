package id.xms.payloadpack.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RootSizeCalculator calculates folder sizes using root shell commands.
 *
 * This is necessary because File.length() often returns 0 for folders in /data
 * due to ownership/permission quirks, even with chmod 777.
 *
 * Solution: Use `du -sb /path` (disk usage) running as Root, which always
 * reports the correct byte size.
 */
object RootSizeCalculator {

    private const val TAG = "RootSizeCalculator"

    /**
     * Calculate the size of a directory in bytes using root shell.
     *
     * @param path Absolute path to the directory
     * @return Size in bytes, or 0 if calculation fails
     */
    suspend fun calculateSize(path: String): Long = withContext(Dispatchers.IO) {
        try {
            // Use du -sb (disk usage, summarize, bytes)
            // Output format: "123456\t/path/to/folder"
            val result = Shell.cmd("du -sb '$path' 2>/dev/null").exec()

            if (!result.isSuccess || result.out.isEmpty()) {
                Log.w(TAG, "du command failed for $path: ${result.err}")
                return@withContext 0L
            }

            // Parse the output: "123456\t/path"
            val output = result.out.firstOrNull() ?: return@withContext 0L
            val parts = output.split(Regex("\\s+"), limit = 2)

            if (parts.isEmpty()) {
                Log.w(TAG, "Invalid du output format: $output")
                return@withContext 0L
            }

            val sizeStr = parts[0]
            val size = sizeStr.toLongOrNull() ?: 0L

            Log.d(TAG, "Size of $path: $size bytes (${formatSize(size)})")
            return@withContext size

        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate size for $path: ${e.message}", e)
            return@withContext 0L
        }
    }

    /**
     * Format size in bytes to human-readable string.
     *
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "123.45 MB")
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024.0 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }

        return if (unitIndex == 0) {
            "${size.toInt()} ${units[unitIndex]}"
        } else {
            "%.2f %s".format(size, units[unitIndex])
        }
    }

    /**
     * Batch calculate sizes for multiple paths.
     *
     * @param paths List of absolute paths
     * @return Map of path to size in bytes
     */
    suspend fun calculateSizes(paths: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Long>()

        for (path in paths) {
            results[path] = calculateSize(path)
        }

        results
    }
}

