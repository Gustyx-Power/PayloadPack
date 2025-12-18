package id.xms.payloadpack.native

/**
 * Callback interface for extraction progress updates.
 */
interface ProgressListener {
    /**
     * Called when extraction progress updates.
     *
     * @param currentFile Name of the file currently being processed
     * @param progress Progress percentage (0-100)
     * @param bytesProcessed Number of bytes processed so far
     * @param totalBytes Total bytes to process
     */
    fun onProgress(currentFile: String, progress: Int, bytesProcessed: Long, totalBytes: Long)
}

/**
 * Native Library interface for PayloadPack.
 *
 * This object provides Kotlin bindings to the Rust native library.
 * All native functions are implemented in Rust via JNI.
 *
 * @see src/main/rust/src/lib.rs for the Rust implementation
 */
object NativeLib {

    /**
     * Flag to track if the native library has been loaded.
     */
    private var isLoaded = false

    /**
     * Load the native library.
     * This must be called before any native functions are used.
     *
     * @return true if the library was loaded successfully, false otherwise
     */
    @JvmStatic
    fun loadLibrary(): Boolean {
        if (isLoaded) return true

        return try {
            System.loadLibrary("payloadpack")
            isLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Check if the native library is loaded.
     */
    @JvmStatic
    fun isLibraryLoaded(): Boolean = isLoaded

    /**
     * Returns a greeting message from Rust.
     *
     * This is a proof-of-concept function demonstrating JNI integration.
     *
     * @return A greeting string from Rust, or null if the call fails
     */
    @JvmStatic
    external fun helloFromRust(): String?

    /**
     * Process a message using Rust.
     *
     * This demonstrates passing data between Kotlin and Rust.
     *
     * @param input The input string to process
     * @return The processed string, or null if the call fails
     */
    @JvmStatic
    external fun processMessage(input: String): String?

    /**
     * Inspect a payload.bin file and extract partition information.
     *
     * This function parses the payload header and manifest to extract:
     * - Payload version
     * - Block size
     * - List of partitions with names and sizes
     * - Total size of all partitions
     *
     * Memory-efficient: Only reads header and manifest, not the entire file.
     *
     * @param path Path to the payload.bin file (e.g., "/sdcard/PayloadPack/payload.bin")
     * @return JSON string with payload information, or JSON with "error" field on failure
     *
     * Example success response:
     * ```json
     * {
     *   "header": { "version": 2, "manifest_size": 1234567, "metadata_signature_size": 256 },
     *   "block_size": 4096,
     *   "partitions": [
     *     { "name": "system", "size": 2147483648, "size_human": "2.00 GB", "operations_count": 4521 },
     *     { "name": "vendor", "size": 536870912, "size_human": "512.00 MB", "operations_count": 1234 }
     *   ],
     *   "total_size": 3221225472,
     *   "total_size_human": "3.00 GB"
     * }
     * ```
     *
     * Example error response:
     * ```json
     * { "error": "Payload inspection error: Invalid magic bytes" }
     * ```
     */
    @JvmStatic
    external fun inspectPayload(path: String): String?

    /**
     * Extract partition images from a payload.bin file.
     *
     * This function extracts all partitions from the payload and writes them as .img files
     * to the specified output directory. Uses streaming I/O to handle large files efficiently.
     *
     * @param payloadPath Path to the payload.bin file
     * @param outputDir Directory where .img files will be written (created if doesn't exist)
     * @param progressListener Callback for progress updates (can be null for no progress)
     * @return JSON string with extraction result
     *
     * Example success response:
     * ```json
     * {
     *   "status": "success",
     *   "extracted": [
     *     {"name": "system", "size": 2147483648, "path": "/data/PayloadPack/project/system.img"},
     *     {"name": "vendor", "size": 536870912, "path": "/data/PayloadPack/project/vendor.img"}
     *   ]
     * }
     * ```
     *
     * Example error response:
     * ```json
     * {
     *   "status": "error",
     *   "message": "Failed to write partition: Permission denied"
     * }
     * ```
     */
    @JvmStatic
    external fun extractPayload(
        payloadPath: String,
        outputDir: String,
        progressListener: ProgressListener?
    ): String?
}
