package id.xms.payloadpack.native

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
     *   "header": { "version_major": 2, "version_minor": 0, ... },
     *   "partitions": [
     *     { "name": "system", "size": 2147483648, "size_human": "2.00 GB", ... },
     *     { "name": "vendor", "size": 536870912, "size_human": "512.00 MB", ... }
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
}
