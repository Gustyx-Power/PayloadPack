//! Payload.bin Parser Module
//!
//! This module provides functionality to parse Android OTA payload.bin files.
//! It reads only the header and manifest to extract partition information
//! without loading the entire file into memory.

use byteorder::{BigEndian, ReadBytesExt};
use prost::Message;
use serde::Serialize;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use thiserror::Error;

// Use the proto module with generated protobuf code
use crate::proto::DeltaArchiveManifest;

/// Magic bytes for payload.bin files
const PAYLOAD_MAGIC: &[u8; 4] = b"CrAU";

/// Payload header size (version 2)
/// - Magic: 4 bytes
/// - Version: 8 bytes
/// - Manifest size: 8 bytes
/// - Metadata signature size: 4 bytes
const HEADER_SIZE_V2: u64 = 24;

/// Error types for payload parsing
#[derive(Error, Debug)]
pub enum PayloadError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Invalid magic bytes: expected 'CrAU', got '{0}'")]
    InvalidMagic(String),

    #[error("Unsupported payload version: {major}.{minor}")]
    UnsupportedVersion { major: u32, minor: u32 },

    #[error("Protobuf decode error: {0}")]
    ProtobufDecode(#[from] prost::DecodeError),

    #[error("Manifest too large: {0} bytes (max 100MB)")]
    ManifestTooLarge(u64),

    #[error("File too small to be a valid payload")]
    FileTooSmall,
}

/// Payload header information
#[derive(Debug, Clone, Serialize)]
pub struct PayloadHeader {
    /// Major version of the payload format
    pub version_major: u32,
    /// Minor version of the payload format
    pub version_minor: u32,
    /// Size of the manifest in bytes
    pub manifest_size: u64,
    /// Size of the metadata signature (v2+ only)
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
pub fn inspect_payload(path: &str) -> Result<PayloadInspection, PayloadError> {
    log::info!("Opening payload file: {}", path);

    let file = File::open(path)?;
    let file_size = file.metadata()?.len();

    if file_size < HEADER_SIZE_V2 {
        return Err(PayloadError::FileTooSmall);
    }

    let mut reader = BufReader::new(file);

    // Read and verify magic bytes
    let mut magic = [0u8; 4];
    reader.read_exact(&mut magic)?;

    if &magic != PAYLOAD_MAGIC {
        return Err(PayloadError::InvalidMagic(
            String::from_utf8_lossy(&magic).to_string(),
        ));
    }

    log::debug!("Magic bytes verified: CrAU");

    // Header format (version 2):
    // - 4 bytes: magic ("CrAU")
    // - 8 bytes: file format version (uint64, big-endian)
    // - 8 bytes: manifest size (uint64, big-endian)
    // - 4 bytes: metadata signature size (uint32, big-endian)

    // Read version (already at offset 4 after magic)
    let version = reader.read_u64::<BigEndian>()?;
    let version_major = (version >> 32) as u32;
    let version_minor = (version & 0xFFFFFFFF) as u32;

    log::debug!("Payload version: {}.{}", version_major, version_minor);

    if version_major != 2 {
        return Err(PayloadError::UnsupportedVersion {
            major: version_major,
            minor: version_minor,
        });
    }

    // Read manifest size
    let manifest_size = reader.read_u64::<BigEndian>()?;
    log::debug!("Manifest size: {} bytes", manifest_size);

    // Sanity check: manifest shouldn't be larger than 100MB
    if manifest_size > 100 * 1024 * 1024 {
        return Err(PayloadError::ManifestTooLarge(manifest_size));
    }

    // Read metadata signature size (v2+)
    let metadata_signature_size = reader.read_u32::<BigEndian>()?;
    log::debug!("Metadata signature size: {} bytes", metadata_signature_size);

    // Now read the manifest
    // Current position should be at HEADER_SIZE_V2 (24 bytes)
    let manifest_offset = HEADER_SIZE_V2;
    reader.seek(SeekFrom::Start(manifest_offset))?;

    let mut manifest_data = vec![0u8; manifest_size as usize];
    reader.read_exact(&mut manifest_data)?;

    log::debug!("Read {} bytes of manifest data", manifest_data.len());

    // Parse the protobuf manifest
    let manifest = DeltaArchiveManifest::decode(&manifest_data[..])?;

    log::info!("Parsed manifest with {} partitions", manifest.partitions.len());

    // Extract partition information
    let mut partitions = Vec::new();
    let mut total_size: u64 = 0;

    for partition in &manifest.partitions {
        let size = partition
            .new_partition_info
            .as_ref()
            .map(|info| info.size.unwrap_or(0))
            .unwrap_or(0);

        total_size += size;

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
        version_major,
        version_minor,
        manifest_size,
        metadata_signature_size,
    };

    Ok(PayloadInspection {
        header,
        block_size: manifest.block_size.unwrap_or(4096),
        partial_update: manifest.partial_update.unwrap_or(false),
        security_patch_level: manifest.security_patch_level,
        partitions,
        total_size,
        total_size_human: format_size(total_size),
    })
}

/// Inspect a payload and return the result as a JSON string.
///
/// This is the main entry point for JNI calls.
///
/// # Arguments
/// * `path` - Path to the payload.bin file
///
/// # Returns
/// * `Ok(String)` - JSON string with payload information
/// * `Err(String)` - Error message if parsing fails
pub fn inspect_payload_json(path: &str) -> Result<String, String> {
    match inspect_payload(path) {
        Ok(inspection) => {
            serde_json::to_string_pretty(&inspection)
                .map_err(|e| format!("JSON serialization error: {}", e))
        }
        Err(e) => Err(format!("Payload inspection error: {}", e)),
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
}
