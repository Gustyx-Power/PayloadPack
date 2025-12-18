package id.xms.payloadpack.data

import android.util.Log
import com.topjohnwu.superuser.Shell
import id.xms.payloadpack.core.DirectoryManager
import id.xms.payloadpack.core.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Source file found in /sdcard/PayloadPack
 * Can be a .zip ROM or a standalone payload.bin
 */
data class SourceFile(
    val name: String,
    val path: String,
    val size: Long,
    val sizeHuman: String,
    val extension: String,
    val lastModified: Long,
    val isZip: Boolean
) {
    val displayName: String
        get() = name.substringBeforeLast(".")
}

/**
 * Project folder in /data/PayloadPack
 * Contains extracted ROM data
 */
data class Project(
    val name: String,
    val path: String,
    val hasPayload: Boolean,
    val partitionCount: Int,
    val totalSize: Long,
    val sizeHuman: String,
    val lastModified: Long,
    val lastModifiedFormatted: String
)

/**
 * FileRepository handles all file system operations:
 * - Scanning /sdcard/PayloadPack for source ROMs
 * - Scanning /data/PayloadPack for extracted projects
 * - CRUD operations on projects
 * 
 * NOTE: After extraction, chmod -R 777 is applied to /data/PayloadPack,
 * so standard File API should work. Root is used as fallback only.
 */
class FileRepository {

    companion object {
        private const val TAG = "FileRepository"
        
        // Supported file extensions
        private val SUPPORTED_EXTENSIONS = listOf("zip", "bin")
        
        // Date formatter for display
        private val dateFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    }

    /**
     * Scan /sdcard/PayloadPack for source files (.zip and .bin)
     */
    suspend fun scanSources(): List<SourceFile> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning sources in ${DirectoryManager.USER_DIR}")
        
        val userDir = File(StorageHelper.resolvePath(DirectoryManager.USER_DIR) 
            ?: DirectoryManager.USER_DIR.replace("/sdcard", "/storage/emulated/0"))
        
        if (!userDir.exists()) {
            Log.w(TAG, "User directory does not exist: ${userDir.absolutePath}")
            // Try to create it
            Shell.cmd("mkdir -p '${userDir.absolutePath}'").exec()
            return@withContext emptyList()
        }

        val files = try {
            userDir.listFiles()?.filter { file ->
                file.isFile && SUPPORTED_EXTENSIONS.any { ext ->
                    file.name.lowercase().endsWith(".$ext")
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files: ${e.message}")
            emptyList()
        }

        files.map { file ->
            SourceFile(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                sizeHuman = StorageHelper.formatSize(file.length()),
                extension = file.extension.lowercase(),
                lastModified = file.lastModified(),
                isZip = file.extension.lowercase() == "zip"
            )
        }.sortedByDescending { it.lastModified }.also {
            Log.d(TAG, "Found ${it.size} source files")
        }
    }

    /**
     * Scan /data/PayloadPack for extracted projects.
     * 
     * After extraction, chmod -R 777 is applied, so standard File API works.
     * Falls back to root shell if File API fails.
     */
    suspend fun scanProjects(): List<Project> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning projects in ${DirectoryManager.WORK_DIR}")
        
        val workDir = File(DirectoryManager.WORK_DIR)
        
        // Try standard File API first (should work after chmod -R 777)
        val folders = try {
            if (workDir.exists() && workDir.canRead()) {
                Log.d(TAG, "Using standard File API to list projects")
                workDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } else {
                Log.d(TAG, "Cannot read work directory, falling back to root")
                listFoldersWithRoot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "File API failed: ${e.message}, falling back to root")
            listFoldersWithRoot()
        }
        
        if (folders.isEmpty()) {
            Log.d(TAG, "No projects found")
            return@withContext emptyList()
        }
        
        Log.d(TAG, "Found ${folders.size} project folders")
        
        val projects = folders.mapNotNull { folder ->
            try {
                getProjectFromFolder(folder)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read project ${folder.name}: ${e.message}")
                null
            }
        }

        projects.sortedByDescending { it.lastModified }.also {
            Log.d(TAG, "Returning ${it.size} projects")
        }
    }

    /**
     * Create project info from a folder.
     */
    private fun getProjectFromFolder(folder: File): Project {
        val name = folder.name
        val path = folder.absolutePath
        
        // Check for payload.bin
        val payloadFile = File(folder, "payload.bin")
        val hasPayload = payloadFile.exists()
        
        // Count .img files (extracted partitions)
        val partitionCount = folder.walk()
            .filter { it.isFile && it.extension.lowercase() == "img" }
            .count()
        
        // Calculate total size
        val totalSize = folder.walk()
            .filter { it.isFile }
            .sumOf { it.length() }
        
        // Get last modified
        val lastModified = folder.lastModified()
        
        return Project(
            name = name,
            path = path,
            hasPayload = hasPayload,
            partitionCount = partitionCount,
            totalSize = totalSize,
            sizeHuman = StorageHelper.formatSize(totalSize),
            lastModified = lastModified,
            lastModifiedFormatted = dateFormatter.format(Date(lastModified))
        )
    }

    /**
     * Fallback: list folders using root shell when File API fails
     */
    private fun listFoldersWithRoot(): List<File> {
        Log.d(TAG, "Using root shell to list folders")
        
        val result = Shell.cmd("ls -1 '${DirectoryManager.WORK_DIR}' 2>/dev/null").exec()
        if (!result.isSuccess || result.out.isEmpty()) {
            Log.w(TAG, "Root ls failed or empty: ${result.err}")
            return emptyList()
        }
        
        return result.out
            .filter { it.isNotBlank() && !it.startsWith(".") }
            .map { File("${DirectoryManager.WORK_DIR}/$it") }
    }

    /**
     * Delete a project directory
     */
    suspend fun deleteProject(path: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Deleting project: $path")
        
        // Safety check: only allow deleting from WORK_DIR
        if (!path.startsWith(DirectoryManager.WORK_DIR)) {
            Log.e(TAG, "Invalid path - not in work directory!")
            return@withContext false
        }
        
        // Try standard File API first
        val folder = File(path)
        val deleted = try {
            if (folder.exists() && folder.canWrite()) {
                folder.deleteRecursively()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "File API delete failed: ${e.message}")
            false
        }
        
        if (deleted) {
            Log.d(TAG, "Project deleted successfully (File API)")
            return@withContext true
        }
        
        // Fallback to root
        Log.d(TAG, "Falling back to root for deletion")
        val result = Shell.cmd("rm -rf '$path'").exec()
        
        if (result.isSuccess) {
            Log.d(TAG, "Project deleted successfully (root)")
            true
        } else {
            Log.e(TAG, "Failed to delete project: ${result.err}")
            false
        }
    }

    /**
     * Get the payload.bin path for a project
     */
    fun getPayloadPath(projectPath: String): String? {
        val payloadFile = File(projectPath, "payload.bin")
        return if (payloadFile.exists()) payloadFile.absolutePath else null
    }

    /**
     * Ensure proper permissions on work directory.
     * Call this after app startup to fix any permission issues.
     */
    suspend fun ensurePermissions(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Ensuring permissions on ${DirectoryManager.WORK_DIR}")
        
        val result = Shell.cmd(
            "chmod 755 '${DirectoryManager.WORK_DIR}'",
            "chmod -R 777 '${DirectoryManager.WORK_DIR}'"
        ).exec()
        
        if (result.isSuccess) {
            Log.d(TAG, "Permissions applied successfully")
        } else {
            Log.w(TAG, "Permission fix failed: ${result.err}")
        }
        
        result.isSuccess
    }
}
