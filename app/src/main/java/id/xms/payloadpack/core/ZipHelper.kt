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
 * Extracts to /data/PayloadPack/work_dir/ using root permissions.
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
            val payloadPath: String?,
            val propertiesPath: String?,
            val filesExtracted: Int,
            val totalBytes: Long
        ) : ExtractResult()
        
        data class Error(val message: String) : ExtractResult()
    }

    /**
     * Extract a ROM ZIP file to the work directory.
     *
     * This function:
     * 1. Creates a unique work directory under /data/PayloadPack/
     * 2. Extracts only essential files (payload.bin, payload_properties.txt, META-INF)
     * 3. Returns paths to the extracted payload files
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
        
        // Create unique work directory
        val timestamp = System.currentTimeMillis()
        val workDirName = "rom_${timestamp}"
        val workDir = "${DirectoryManager.WORK_DIR}/$workDirName"
        
        // Create directory with root (since /data requires root)
        val mkdirResult = Shell.cmd("mkdir -p '$workDir'", "chmod 777 '$workDir'").exec()
        if (!mkdirResult.isSuccess) {
            Log.e(TAG, "Failed to create work directory: ${mkdirResult.err}")
            return@withContext ExtractResult.Error("Failed to create work directory")
        }
        
        Log.i(TAG, "Work directory created: $workDir")
        
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
                    val outputPath = "$workDir/$entryName"
                    val outputFile = File(outputPath)
                    
                    // Create parent directories
                    outputFile.parentFile?.let { parent ->
                        if (!parent.exists()) {
                            // Use root to create directories in /data
                            Shell.cmd("mkdir -p '${parent.absolutePath}'").exec()
                        }
                    }
                    
                    Log.d(TAG, "Extracting: $entryName (${StorageHelper.formatSize(entry.size)})")
                    onProgress?.invoke(bytesExtracted, zipSize, entryName)
                    
                    // Extract file using buffered write
                    // For /data, we need to extract via a temp file first, then move with root
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
            
            Log.i(TAG, "Extraction complete: $filesExtracted files, ${StorageHelper.formatSize(bytesExtracted)}")
            
            ExtractResult.Success(
                extractedDir = workDir,
                payloadPath = payloadPath,
                propertiesPath = propertiesPath,
                filesExtracted = filesExtracted,
                totalBytes = bytesExtracted
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            // Cleanup on failure
            Shell.cmd("rm -rf '$workDir'").exec()
            ExtractResult.Error("Extraction failed: ${e.message}")
        }
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
     *
     * Format:
     * FILE_HASH=abc123
     * FILE_SIZE=123456789
     * METADATA_HASH=def456
     * METADATA_SIZE=12345
     */
    suspend fun parsePayloadProperties(propertiesPath: String): Map<String, String>? = 
        withContext(Dispatchers.IO) {
            try {
                // Read with root since it's in /data
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
                
                Log.d(TAG, "Parsed properties: ${properties.keys}")
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

    /**
     * List all extracted ROMs in the work directory.
     */
    suspend fun listExtractedRoms(): List<ExtractedRom> = withContext(Dispatchers.IO) {
        val result = Shell.cmd("ls -1 '${DirectoryManager.WORK_DIR}' 2>/dev/null").exec()
        if (!result.isSuccess) {
            return@withContext emptyList()
        }
        
        result.out.mapNotNull { dirName ->
            if (dirName.startsWith("rom_")) {
                val path = "${DirectoryManager.WORK_DIR}/$dirName"
                val payloadExists = Shell.cmd("test -f '$path/payload.bin'").exec().isSuccess
                
                ExtractedRom(
                    name = dirName,
                    path = path,
                    hasPayload = payloadExists,
                    timestamp = dirName.removePrefix("rom_").toLongOrNull() ?: 0
                )
            } else null
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Represents an extracted ROM in the work directory.
     */
    data class ExtractedRom(
        val name: String,
        val path: String,
        val hasPayload: Boolean,
        val timestamp: Long
    )
}
