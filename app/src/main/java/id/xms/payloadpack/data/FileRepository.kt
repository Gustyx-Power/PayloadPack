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
            // Fallback to root ls if permission denied
            listFilesWithRoot(userDir.absolutePath)
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
     * Scan /data/PayloadPack for extracted projects
     * Requires root access
     */
    suspend fun scanProjects(): List<Project> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning projects in ${DirectoryManager.WORK_DIR}")

        // /data requires root, so use Shell commands
        val result = Shell.cmd("ls -la '${DirectoryManager.WORK_DIR}' 2>/dev/null").exec()
        
        if (!result.isSuccess || result.out.isEmpty()) {
            Log.w(TAG, "No projects found or cannot access work directory")
            return@withContext emptyList()
        }

        // Parse ls output
        // Format: drwxrwxrwx  2 root root 4096 2024-01-15 10:30 foldername
        val projects = mutableListOf<Project>()
        
        for (line in result.out) {
            // Skip . and .. and non-directory entries
            if (line.startsWith("total") || line.isEmpty()) continue
            if (!line.startsWith("d")) continue  // Only directories
            
            val parts = line.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            
            val name = parts[8]
            if (name == "." || name == "..") continue
            
            val path = "${DirectoryManager.WORK_DIR}/$name"
            
            // Get more details about this project
            val projectInfo = getProjectInfo(path)
            
            projects.add(
                Project(
                    name = name,
                    path = path,
                    hasPayload = projectInfo.hasPayload,
                    partitionCount = projectInfo.partitionCount,
                    totalSize = projectInfo.totalSize,
                    sizeHuman = StorageHelper.formatSize(projectInfo.totalSize),
                    lastModified = projectInfo.lastModified,
                    lastModifiedFormatted = dateFormatter.format(Date(projectInfo.lastModified))
                )
            )
        }

        projects.sortedByDescending { it.lastModified }.also {
            Log.d(TAG, "Found ${it.size} projects")
        }
    }

    /**
     * Create a new project directory for extraction
     */
    suspend fun createProject(sourceName: String): String? = withContext(Dispatchers.IO) {
        val projectName = sourceName
            .substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(50)
        
        val projectPath = "${DirectoryManager.WORK_DIR}/$projectName"
        
        Log.d(TAG, "Creating project: $projectPath")
        
        val result = Shell.cmd(
            "mkdir -p '$projectPath'",
            "chmod 777 '$projectPath'"
        ).exec()
        
        if (result.isSuccess) {
            Log.d(TAG, "Project created successfully")
            projectPath
        } else {
            Log.e(TAG, "Failed to create project: ${result.err}")
            null
        }
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
        
        val result = Shell.cmd("rm -rf '$path'").exec()
        
        if (result.isSuccess) {
            Log.d(TAG, "Project deleted successfully")
            true
        } else {
            Log.e(TAG, "Failed to delete project: ${result.err}")
            false
        }
    }

    /**
     * Get the payload.bin path for a source file
     * If it's a ZIP, we need to extract first
     * If it's a .bin, return its path directly
     */
    fun getPayloadPath(source: SourceFile): String {
        return if (source.isZip) {
            // Will need extraction first
            ""
        } else {
            source.path
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Fallback: list files using root shell when Java File API fails
     */
    private fun listFilesWithRoot(dirPath: String): List<File> {
        Log.d(TAG, "Using root to list files in: $dirPath")
        
        val result = Shell.cmd("ls -la '$dirPath' 2>/dev/null").exec()
        if (!result.isSuccess) return emptyList()
        
        val files = mutableListOf<File>()
        
        for (line in result.out) {
            if (line.startsWith("total") || line.isEmpty()) continue
            if (line.startsWith("d")) continue  // Skip directories
            
            val parts = line.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            
            val name = parts[8]
            val extension = name.substringAfterLast(".", "").lowercase()
            
            if (SUPPORTED_EXTENSIONS.contains(extension)) {
                files.add(File("$dirPath/$name"))
            }
        }
        
        return files
    }

    /**
     * Get detailed info about a project directory
     */
    private data class ProjectInfo(
        val hasPayload: Boolean,
        val partitionCount: Int,
        val totalSize: Long,
        val lastModified: Long
    )

    private fun getProjectInfo(projectPath: String): ProjectInfo {
        // Check for payload.bin
        val hasPayload = Shell.cmd("test -f '$projectPath/payload.bin'").exec().isSuccess
        
        // Count .img files (partitions)
        val countResult = Shell.cmd(
            "find '$projectPath' -name '*.img' 2>/dev/null | wc -l"
        ).exec()
        val partitionCount = countResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        
        // Get total size
        val sizeResult = Shell.cmd(
            "du -sb '$projectPath' 2>/dev/null | cut -f1"
        ).exec()
        val totalSize = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        
        // Get last modified time
        val statResult = Shell.cmd(
            "stat -c %Y '$projectPath' 2>/dev/null"
        ).exec()
        val lastModified = (statResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L) * 1000
        
        return ProjectInfo(
            hasPayload = hasPayload,
            partitionCount = partitionCount,
            totalSize = totalSize,
            lastModified = if (lastModified > 0) lastModified else System.currentTimeMillis()
        )
    }
}
