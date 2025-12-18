package id.xms.payloadpack.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * ZipHelper - Efficient extraction of large ROM ZIP files.
 *
 * Uses buffered streams for memory efficiency with 3GB+ files.
 * Extracts to /data/PayloadPack/ using root permissions.
 * 
 * IMPORTANT: After extraction, chmod -R 777 is applied so the UI layer
 * can read files without needing root for every operation.
 */
object ZipHelper {

    private const val TAG = "ZipHelper"
    
    // Buffer size for reading (8MB for faster I/O on large files)
    private const val BUFFER_SIZE = 8 * 1024 * 1024
    
    /**
     * Result of a ZIP extraction operation.
     */
    sealed class ExtractResult {
        data class Success(
            val extractedDir: String,
            val projectName: String,
            val payloadPath: String?,
            val propertiesPath: String?,
            val filesExtracted: Int,
            val totalBytes: Long
        ) : ExtractResult()
        
        data class Error(val message: String) : ExtractResult()
    }

    /**
     * Extract a ROM ZIP file to a project directory.
     * 
     * The project folder is named after the ZIP file (without extension).
     * Example: "PixelOS_Marble.zip" -> "/data/PayloadPack/PixelOS_Marble/"
     *
     * This function:
     * 1. Creates a project directory named after the ZIP file
     * 2. Extracts only essential files (payload.bin, payload_properties.txt, META-INF)
     * 3. Applies chmod -R 777 so UI can read files without root
     * 4. Returns paths to the extracted payload files
     *
     * @param zipPath Path to the input ZIP file
     * @param extractAll If true, extract all files. If false, only extract payload-related files.
     * @param onProgress Progress callback (current bytes, total bytes, current file name)
     * @return ExtractResult with paths to extracted files or error
     */
    suspend fun extractRomZip(
        zipPath: String,
        extractAll: Boolean = false,
        onProgress: ((Long, Long, String) -> Unit)? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting extraction of: $zipPath")
        
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            Log.e(TAG, "ZIP file not found: $zipPath")
            return@withContext ExtractResult.Error("ZIP file not found: $zipPath")
        }
        
        val zipSize = zipFile.length()
        Log.i(TAG, "ZIP size: ${StorageHelper.formatSize(zipSize)}")
        
        // Generate project name from ZIP filename
        val projectName = generateProjectName(zipFile.nameWithoutExtension)
        val projectDir = "${DirectoryManager.WORK_DIR}/$projectName"
        
        Log.i(TAG, "Project name: $projectName")
        Log.i(TAG, "Project directory: $projectDir")
        
        // Create directory with root (since /data requires root)
        val mkdirResult = Shell.cmd(
            "mkdir -p '$projectDir'",
            "chmod 777 '$projectDir'"
        ).exec()
        
        if (!mkdirResult.isSuccess) {
            Log.e(TAG, "Failed to create project directory: ${mkdirResult.err}")
            return@withContext ExtractResult.Error("Failed to create project directory")
        }
        
        Log.i(TAG, "Project directory created: $projectDir")
        
        var payloadPath: String? = null
        var propertiesPath: String? = null
        var filesExtracted = 0
        var bytesExtracted = 0L
        
        try {
            // Use ZipInputStream for memory-efficient streaming
            val fis = FileInputStream(zipFile)
            val bis = BufferedInputStream(fis, BUFFER_SIZE)
            val zis = ZipInputStream(bis)
            
            var entry = zis.nextEntry
            
            while (entry != null) {
                val entryName = entry.name
                
                // Filter files if not extracting all
                val shouldExtract = extractAll || isEssentialFile(entryName)
                
                if (shouldExtract && !entry.isDirectory) {
                    val outputPath = "$projectDir/$entryName"
                    val outputFile = File(outputPath)
                    
                    // Create parent directories
                    outputFile.parentFile?.let { parent ->
                        if (!parent.exists()) {
                            Shell.cmd("mkdir -p '${parent.absolutePath}'").exec()
                        }
                    }
                    
                    Log.d(TAG, "Extracting: $entryName (${StorageHelper.formatSize(entry.size)})")
                    onProgress?.invoke(bytesExtracted, zipSize, entryName)
                    
                    // Extract file using buffered write via temp file
                    val tempFile = File.createTempFile("extract_", ".tmp")
                    
                    try {
                        BufferedOutputStream(FileOutputStream(tempFile), BUFFER_SIZE).use { bos ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                bos.write(buffer, 0, len)
                                bytesExtracted += len
                            }
                        }
                        
                        // Move to final destination with root
                        val moveResult = Shell.cmd(
                            "cp '${tempFile.absolutePath}' '$outputPath'",
                            "chmod 666 '$outputPath'"
                        ).exec()
                        
                        if (moveResult.isSuccess) {
                            filesExtracted++
                            
                            // Track payload paths
                            if (entryName.endsWith("payload.bin")) {
                                payloadPath = outputPath
                                Log.i(TAG, "Found payload.bin: $outputPath")
                            } else if (entryName.endsWith("payload_properties.txt")) {
                                propertiesPath = outputPath
                                Log.i(TAG, "Found payload_properties.txt: $outputPath")
                            }
                        } else {
                            Log.w(TAG, "Failed to move file: ${moveResult.err}")
                        }
                    } finally {
                        tempFile.delete()
                    }
                }
                
                zis.closeEntry()
                entry = zis.nextEntry
            }
            
            zis.close()
            bis.close()
            fis.close()
            
            // =================================================================
            // CRITICAL: Apply chmod -R 777 so UI can read without root
            // =================================================================
            Log.i(TAG, "Applying chmod -R 777 to project directory...")
            val chmodResult = Shell.cmd("chmod -R 777 '$projectDir'").exec()
            if (!chmodResult.isSuccess) {
                Log.w(TAG, "chmod failed (non-fatal): ${chmodResult.err}")
            } else {
                Log.i(TAG, "chmod -R 777 applied successfully")
            }
            
            // Also ensure the parent directory is readable
            Shell.cmd("chmod 755 '${DirectoryManager.WORK_DIR}'").exec()
            
            Log.i(TAG, "Extraction complete: $filesExtracted files, ${StorageHelper.formatSize(bytesExtracted)}")
            
            ExtractResult.Success(
                extractedDir = projectDir,
                projectName = projectName,
                payloadPath = payloadPath,
                propertiesPath = propertiesPath,
                filesExtracted = filesExtracted,
                totalBytes = bytesExtracted
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            // Cleanup on failure
            Shell.cmd("rm -rf '$projectDir'").exec()
            ExtractResult.Error("Extraction failed: ${e.message}")
        }
    }

    /**
     * Generate a valid project name from the input string.
     * 
     * - Sanitizes special characters
     * - Handles duplicates by appending _1, _2, etc.
     * - Limits length to 50 characters
     */
    private fun generateProjectName(baseName: String): String {
        // Sanitize: replace invalid characters with underscore
        val sanitized = baseName
            .replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
            .replace(Regex("_+"), "_")  // Collapse multiple underscores
            .trim('_')
            .take(50)
        
        val projectName = sanitized.ifEmpty { "project" }
        
        // Check if folder already exists
        val baseDir = DirectoryManager.WORK_DIR
        val checkResult = Shell.cmd("test -d '$baseDir/$projectName'").exec()
        
        if (!checkResult.isSuccess) {
            // Folder doesn't exist, use as-is
            return projectName
        }
        
        // Folder exists, find a unique suffix
        var suffix = 1
        while (suffix < 100) {
            val candidateName = "${projectName}_$suffix"
            val checkCandidate = Shell.cmd("test -d '$baseDir/$candidateName'").exec()
            if (!checkCandidate.isSuccess) {
                Log.d(TAG, "Using unique name: $candidateName")
                return candidateName
            }
            suffix++
        }
        
        // Fallback with timestamp
        return "${projectName}_${System.currentTimeMillis()}"
    }

    /**
     * Check if a file is essential for ROM processing.
     */
    private fun isEssentialFile(name: String): Boolean {
        return name.endsWith("payload.bin") ||
               name.endsWith("payload_properties.txt") ||
               name.contains("META-INF/") ||
               name.endsWith("care_map.pb") ||
               name.endsWith("apex_info.pb")
    }

    /**
     * Parse payload_properties.txt and return as a map.
     */
    suspend fun parsePayloadProperties(propertiesPath: String): Map<String, String>? = 
        withContext(Dispatchers.IO) {
            try {
                // Try standard file read first (after chmod)
                val file = File(propertiesPath)
                if (file.canRead()) {
                    val properties = mutableMapOf<String, String>()
                    file.readLines().forEach { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            properties[parts[0].trim()] = parts[1].trim()
                        }
                    }
                    Log.d(TAG, "Parsed properties (file): ${properties.keys}")
                    return@withContext properties
                }
                
                // Fallback to root shell
                val result = Shell.cmd("cat '$propertiesPath'").exec()
                if (!result.isSuccess) {
                    Log.e(TAG, "Failed to read properties: ${result.err}")
                    return@withContext null
                }
                
                val properties = mutableMapOf<String, String>()
                result.out.forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        properties[parts[0].trim()] = parts[1].trim()
                    }
                }
                
                Log.d(TAG, "Parsed properties (root): ${properties.keys}")
                properties
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse properties: ${e.message}")
                null
            }
        }

    /**
     * Clean up an extracted ROM directory.
     */
    suspend fun cleanupExtraction(extractedDir: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Cleaning up: $extractedDir")
        val result = Shell.cmd("rm -rf '$extractedDir'").exec()
        result.isSuccess
    }
}
