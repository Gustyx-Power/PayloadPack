//! Payload.bin Parser Module
//!
//! This module provides functionality to parse Android OTA payload.bin files.
//! It reads only the header and manifest to extract partition information
//! without loading the entire file into memory.
//!
//! AOSP Update Engine Header Format (Version 2):
//! - Offset 0:  Magic bytes "CrAU" (4 bytes)
//! - Offset 4:  Version (u64, Big Endian) - Value must be 2
//! - Offset 12: Manifest Size (u64, Big Endian)
//! - Offset 20: Metadata Signature Size (u32, Big Endian)
//! - Offset 24: Manifest data begins
//!
//! IMPORTANT: This module is called from JNI and must NEVER panic.
//! All errors must be returned as Result::Err, never via unwrap/expect.

use prost::Message;
use serde::Serialize;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use thiserror::Error;

// Use the proto module with generated protobuf code
use crate::proto::DeltaArchiveManifest;

/// Magic bytes for payload.bin files
const PAYLOAD_MAGIC: &[u8; 4] = b"CrAU";

/// Header size in bytes (for version 2)
const HEADER_SIZE: u64 = 24;

/// Error types for payload parsing
#[derive(Error, Debug)]
pub enum PayloadError {
    #[error("File not found: {0}")]
    FileNotFound(String),

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("IO error reading file: {0}")]
    Io(String),

    #[error("Invalid magic bytes: expected 'CrAU' (0x43724155), got '{0}' (0x{1:08X})")]
    InvalidMagic(String, u32),

    #[error("Unsupported payload version: {0}. Only Version 2 is supported.")]
    UnsupportedVersion(u64),

    #[error("Protobuf decode error: {0}")]
    ProtobufDecode(String),

    #[error("Manifest too large: {0} bytes (max 100MB). File may be corrupted.")]
    ManifestTooLarge(u64),

    #[error("File too small ({0} bytes) to be a valid payload. Minimum size is {1} bytes.")]
    FileTooSmall(u64, u64),

    #[error("Path is empty")]
    EmptyPath,

    #[error("Unexpected end of file while reading {0}")]
    UnexpectedEof(String),
}

// Custom From implementations for better error messages
impl From<std::io::Error> for PayloadError {
    fn from(e: std::io::Error) -> Self {
        match e.kind() {
            std::io::ErrorKind::NotFound => PayloadError::FileNotFound(e.to_string()),
            std::io::ErrorKind::PermissionDenied => PayloadError::PermissionDenied(e.to_string()),
            std::io::ErrorKind::UnexpectedEof => {
                PayloadError::UnexpectedEof("header or manifest".to_string())
            }
            _ => PayloadError::Io(e.to_string()),
        }
    }
}

impl From<prost::DecodeError> for PayloadError {
    fn from(e: prost::DecodeError) -> Self {
        PayloadError::ProtobufDecode(e.to_string())
    }
}

/// Payload header information
#[derive(Debug, Clone, Serialize)]
pub struct PayloadHeader {
    /// Payload format version (should be 2)
    pub version: u64,
    /// Size of the manifest in bytes
    pub manifest_size: u64,
    /// Size of the metadata signature (v2+)
    pub metadata_signature_size: u32,
}

/// Information about a single partition
#[derive(Debug, Clone, Serialize)]
pub struct PartitionInfo {
    /// Name of the partition (e.g., "system", "vendor", "boot")
    pub name: String,
    /// Size of the partition in bytes
    pub size: u64,
    /// Number of operations to apply
    pub operations_count: usize,
    /// Size of the partition in human-readable format
    pub size_human: String,
}

/// Complete payload inspection result
#[derive(Debug, Clone, Serialize)]
pub struct PayloadInspection {
    /// Header information
    pub header: PayloadHeader,
    /// Block size used (usually 4096)
    pub block_size: u32,
    /// Whether this is a partial update
    pub partial_update: bool,
    /// Security patch level (if available)
    pub security_patch_level: Option<String>,
    /// List of partitions in the payload
    pub partitions: Vec<PartitionInfo>,
    /// Total size of all partitions
    pub total_size: u64,
    /// Total size in human-readable format
    pub total_size_human: String,
    /// Path that was inspected
    pub file_path: String,
}

/// Format bytes into human-readable string
fn format_size(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;

    if bytes >= GB {
        format!("{:.2} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.2} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.2} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

/// Inspect a payload.bin file and extract partition information.
///
/// This function reads only the header and manifest, making it memory-efficient
/// even for large payload files (2+ GB).
///
/// # Arguments
/// * `path` - Path to the payload.bin file
///
/// # Returns
/// * `Ok(PayloadInspection)` - Parsed payload information
/// * `Err(PayloadError)` - If parsing fails
///
/// # Safety
/// This function NEVER panics. All errors are returned via Result.
pub fn inspect_payload(path: &str) -> Result<PayloadInspection, PayloadError> {
    // Validate path is not empty
    if path.is_empty() {
        log::error!("Empty path provided");
        return Err(PayloadError::EmptyPath);
    }

    log::info!("=== PAYLOAD INSPECTION START ===");
    log::info!("Path: {}", path);

    // Check if file exists before trying to open
    let path_obj = Path::new(path);
    if !path_obj.exists() {
        log::error!("File does not exist: {}", path);
        return Err(PayloadError::FileNotFound(format!(
            "File does not exist: {}",
            path
        )));
    }

    if !path_obj.is_file() {
        log::error!("Path is not a file: {}", path);
        return Err(PayloadError::FileNotFound(format!(
            "Path is not a file: {}",
            path
        )));
    }

    // Open the file
    let mut file = match File::open(path) {
        Ok(f) => {
            log::debug!("File opened successfully");
            f
        }
        Err(e) => {
            log::error!("Failed to open file: {} - {:?}", path, e);
            return Err(PayloadError::from(e));
        }
    };

    // Get file size
    let file_size = match file.metadata() {
        Ok(m) => m.len(),
        Err(e) => {
            log::error!("Failed to get file metadata: {:?}", e);
            return Err(PayloadError::from(e));
        }
    };

    log::info!("File size: {} bytes ({})", file_size, format_size(file_size));

    if file_size < HEADER_SIZE {
        log::error!(
            "File too small: {} bytes, need at least {} bytes",
            file_size,
            HEADER_SIZE
        );
        return Err(PayloadError::FileTooSmall(file_size, HEADER_SIZE));
    }

    // =========================================================================
    // STEP 1: Read and verify Magic Bytes (Offset 0, 4 bytes)
    // Expected: "CrAU" = 0x43 0x72 0x41 0x55
    // =========================================================================
    let mut magic = [0u8; 4];
    if let Err(e) = file.read_exact(&mut magic) {
        log::error!("Failed to read magic bytes: {:?}", e);
        return Err(PayloadError::from(e));
    }

    log::info!(
        "Magic bytes: {:02X} {:02X} {:02X} {:02X} ('{}')",
        magic[0],
        magic[1],
        magic[2],
        magic[3],
        String::from_utf8_lossy(&magic)
    );

    if &magic != PAYLOAD_MAGIC {
        let magic_str = String::from_utf8_lossy(&magic).to_string();
        let magic_u32 = u32::from_be_bytes(magic);
        log::error!(
            "Invalid magic! Expected 'CrAU' (0x43724155), got '{}' (0x{:08X})",
            magic_str,
            magic_u32
        );
        return Err(PayloadError::InvalidMagic(magic_str, magic_u32));
    }

    log::info!("✓ Magic bytes verified: CrAU");

    // =========================================================================
    // STEP 2: Read Version (Offset 4, 8 bytes, u64 Big Endian)
    // Expected: 2 (Android 10+ uses Version 2)
    // =========================================================================
    let mut version_bytes = [0u8; 8];
    if let Err(e) = file.read_exact(&mut version_bytes) {
        log::error!("Failed to read version bytes: {:?}", e);
        return Err(PayloadError::from(e));
    }

    // CRITICAL: Use Big Endian byte order!
    let version = u64::from_be_bytes(version_bytes);

    log::info!(
        "Version bytes: {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X}",
        version_bytes[0],
        version_bytes[1],
        version_bytes[2],
        version_bytes[3],
        version_bytes[4],
        version_bytes[5],
        version_bytes[6],
        version_bytes[7]
    );
    log::info!("Version (BE): {}", version);

    if version != 2 {
        log::error!(
            "Unsupported version: {}. Only Version 2 is supported.",
            version
        );
        return Err(PayloadError::UnsupportedVersion(version));
    }

    log::info!("✓ Version verified: 2");

    // =========================================================================
    // STEP 3: Read Manifest Size (Offset 12, 8 bytes, u64 Big Endian)
    // =========================================================================
    let mut manifest_size_bytes = [0u8; 8];
    if let Err(e) = file.read_exact(&mut manifest_size_bytes) {
        log::error!("Failed to read manifest size bytes: {:?}", e);
        return Err(PayloadError::from(e));
    }

    let manifest_size = u64::from_be_bytes(manifest_size_bytes);

    log::info!(
        "Manifest size bytes: {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X}",
        manifest_size_bytes[0],
        manifest_size_bytes[1],
        manifest_size_bytes[2],
        manifest_size_bytes[3],
        manifest_size_bytes[4],
        manifest_size_bytes[5],
        manifest_size_bytes[6],
        manifest_size_bytes[7]
    );
    log::info!("Manifest size (BE): {} bytes ({})", manifest_size, format_size(manifest_size));

    // Sanity check: manifest shouldn't be larger than 100MB
    const MAX_MANIFEST_SIZE: u64 = 100 * 1024 * 1024;
    if manifest_size > MAX_MANIFEST_SIZE {
        log::error!(
            "Manifest too large: {} bytes (max {} bytes)",
            manifest_size,
            MAX_MANIFEST_SIZE
        );
        return Err(PayloadError::ManifestTooLarge(manifest_size));
    }

    // =========================================================================
    // STEP 4: Read Metadata Signature Size (Offset 20, 4 bytes, u32 Big Endian)
    // =========================================================================
    let mut metadata_sig_size_bytes = [0u8; 4];
    if let Err(e) = file.read_exact(&mut metadata_sig_size_bytes) {
        log::error!("Failed to read metadata signature size: {:?}", e);
        return Err(PayloadError::from(e));
    }

    let metadata_signature_size = u32::from_be_bytes(metadata_sig_size_bytes);

    log::info!(
        "Metadata signature size bytes: {:02X} {:02X} {:02X} {:02X}",
        metadata_sig_size_bytes[0],
        metadata_sig_size_bytes[1],
        metadata_sig_size_bytes[2],
        metadata_sig_size_bytes[3]
    );
    log::info!("Metadata signature size (BE): {} bytes", metadata_signature_size);

    // =========================================================================
    // STEP 5: Read Manifest Data (Offset 24, manifest_size bytes)
    // =========================================================================
    // Current position should be at offset 24 (HEADER_SIZE)
    let current_pos = match file.stream_position() {
        Ok(pos) => pos,
        Err(e) => {
            log::error!("Failed to get stream position: {:?}", e);
            return Err(PayloadError::from(e));
        }
    };
    log::info!("Current file position: {} (should be {})", current_pos, HEADER_SIZE);

    // Ensure we're at the right position
    if current_pos != HEADER_SIZE {
        log::warn!("Position mismatch, seeking to {}", HEADER_SIZE);
        if let Err(e) = file.seek(SeekFrom::Start(HEADER_SIZE)) {
            log::error!("Failed to seek to manifest: {:?}", e);
            return Err(PayloadError::from(e));
        }
    }

    // Read manifest data
    log::info!("Reading {} bytes of manifest data...", manifest_size);
    let mut manifest_data = vec![0u8; manifest_size as usize];
    if let Err(e) = file.read_exact(&mut manifest_data) {
        log::error!("Failed to read manifest data: {:?}", e);
        return Err(PayloadError::from(e));
    }

    log::info!(
        "✓ Read {} bytes of manifest data",
        manifest_data.len()
    );

    // Log first few bytes of manifest for debugging
    if manifest_data.len() >= 16 {
        log::debug!(
            "Manifest first 16 bytes: {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X} {:02X}",
            manifest_data[0], manifest_data[1], manifest_data[2], manifest_data[3],
            manifest_data[4], manifest_data[5], manifest_data[6], manifest_data[7],
            manifest_data[8], manifest_data[9], manifest_data[10], manifest_data[11],
            manifest_data[12], manifest_data[13], manifest_data[14], manifest_data[15]
        );
    }

    // =========================================================================
    // STEP 6: Parse Protobuf Manifest
    // =========================================================================
    log::info!("Parsing protobuf manifest...");
    let manifest = match DeltaArchiveManifest::decode(&manifest_data[..]) {
        Ok(m) => {
            log::info!("✓ Manifest parsed successfully");
            m
        }
        Err(e) => {
            log::error!("Failed to decode protobuf manifest: {:?}", e);
            return Err(PayloadError::from(e));
        }
    };

    log::info!("Partition count: {}", manifest.partitions.len());
    log::info!("Block size: {:?}", manifest.block_size);
    log::info!("Partial update: {:?}", manifest.partial_update);

    // =========================================================================
    // STEP 7: Extract Partition Information
    // =========================================================================
    let mut partitions = Vec::new();
    let mut total_size: u64 = 0;

    for partition in &manifest.partitions {
        let size = partition
            .new_partition_info
            .as_ref()
            .and_then(|info| info.size)
            .unwrap_or(0);

        total_size += size;

        log::debug!(
            "  Partition: {} - {} ({} ops)",
            partition.partition_name,
            format_size(size),
            partition.operations.len()
        );

        partitions.push(PartitionInfo {
            name: partition.partition_name.clone(),
            size,
            operations_count: partition.operations.len(),
            size_human: format_size(size),
        });
    }

    // Sort partitions by name for consistent output
    partitions.sort_by(|a, b| a.name.cmp(&b.name));

    let header = PayloadHeader {
        version,
        manifest_size,
        metadata_signature_size,
    };

    log::info!("=== PAYLOAD INSPECTION COMPLETE ===");
    log::info!(
        "Result: {} partitions, {}",
        partitions.len(),
        format_size(total_size)
    );

    Ok(PayloadInspection {
        header,
        block_size: manifest.block_size.unwrap_or(4096),
        partial_update: manifest.partial_update.unwrap_or(false),
        security_patch_level: manifest.security_patch_level,
        partitions,
        total_size,
        total_size_human: format_size(total_size),
        file_path: path.to_string(),
    })
}

/// Inspect a payload and return the result as a JSON string.
///
/// This is the main entry point for JNI calls.
/// This function NEVER panics - all errors are encoded in the return value.
///
/// # Arguments
/// * `path` - Path to the payload.bin file
///
/// # Returns
/// * `Ok(String)` - JSON string with payload information
/// * `Err(String)` - Error message if parsing fails
pub fn inspect_payload_json(path: &str) -> Result<String, String> {
    log::info!("inspect_payload_json called with path: {}", path);

    match inspect_payload(path) {
        Ok(inspection) => {
            log::debug!("Inspection successful, serializing to JSON");
            match serde_json::to_string_pretty(&inspection) {
                Ok(json) => {
                    log::debug!(
                        "JSON serialization successful, {} bytes",
                        json.len()
                    );
                    Ok(json)
                }
                Err(e) => {
                    log::error!("JSON serialization failed: {:?}", e);
                    Err(format!("JSON serialization error: {}", e))
                }
            }
        }
        Err(e) => {
            log::error!("Payload inspection failed: {}", e);
            Err(e.to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_size() {
        assert_eq!(format_size(0), "0 B");
        assert_eq!(format_size(512), "512 B");
        assert_eq!(format_size(1024), "1.00 KB");
        assert_eq!(format_size(1536), "1.50 KB");
        assert_eq!(format_size(1048576), "1.00 MB");
        assert_eq!(format_size(1073741824), "1.00 GB");
    }

    #[test]
    fn test_empty_path_error() {
        let result = inspect_payload("");
        assert!(result.is_err());
        if let Err(PayloadError::EmptyPath) = result {
            // Expected
        } else {
            panic!("Expected EmptyPath error");
        }
    }

    #[test]
    fn test_nonexistent_file_error() {
        let result = inspect_payload("/nonexistent/path/to/file.bin");
        assert!(result.is_err());
        if let Err(PayloadError::FileNotFound(_)) = result {
            // Expected
        } else {
            panic!("Expected FileNotFound error");
        }
    }

    #[test]
    fn test_big_endian_version() {
        // Version 2 in big endian: 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x02
        let version_bytes: [u8; 8] = [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02];
        let version = u64::from_be_bytes(version_bytes);
        assert_eq!(version, 2);
    }
}
