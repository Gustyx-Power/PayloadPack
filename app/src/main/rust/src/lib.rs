//! PayloadPack Native Library
//!
//! This module provides JNI bindings for the PayloadPack Android application.
//! It exposes Rust functionality to Kotlin/Java through the Java Native Interface.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use thiserror::Error;

// Payload parsing module
mod proto;
mod payload;

/// Custom error types for PayloadPack native operations
#[derive(Error, Debug)]
pub enum PayloadPackError {
    #[error("JNI error: {0}")]
    JniError(String),

    #[error("Invalid input: {0}")]
    InvalidInput(String),

    #[error("Operation failed: {0}")]
    OperationFailed(String),
}

/// Initialize the Android logger for debugging
/// This should be called once when the library is loaded
fn init_logger() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("PayloadPack"),
    );
}

/// JNI Function: Returns a "Hello from Rust!" greeting
///
/// This is a proof-of-concept function demonstrating JNI integration.
///
/// # JNI Signature
/// ```
/// public static native String helloFromRust();
/// ```
///
/// # Safety
/// This function is called from the JVM and must not panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_id_xms_payloadpack_native_NativeLib_helloFromRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // Initialize logger on first call
    init_logger();
    
    log::debug!("helloFromRust called");

    let greeting = "Hello from Rust! 🦀";

    match env.new_string(greeting) {
        Ok(output) => output.into_raw(),
        Err(e) => {
            log::error!("Failed to create Java string: {:?}", e);
            // Return null on error
            std::ptr::null_mut()
        }
    }
}

/// JNI Function: Process input string and return modified version
///
/// This demonstrates passing data between Kotlin and Rust.
///
/// # JNI Signature
/// ```
/// public static native String processMessage(String input);
/// ```
///
/// # Safety
/// This function is called from the JVM and must not panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_id_xms_payloadpack_native_NativeLib_processMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> jstring {
    log::debug!("processMessage called");

    // Extract the input string from JNI
    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get input string: {:?}", e);
            return std::ptr::null_mut();
        }
    };

    // Process the message (example: reverse and convert to uppercase)
    let processed = format!(
        "Rust processed: {} (length: {}, reversed: {})",
        input_str,
        input_str.len(),
        input_str.chars().rev().collect::<String>()
    );

    match env.new_string(&processed) {
        Ok(output) => output.into_raw(),
        Err(e) => {
            log::error!("Failed to create output string: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// JNI Function: Inspect a payload.bin file
///
/// Parses the payload header and manifest to extract partition information.
/// Memory-efficient: only reads header and manifest, not the entire file.
///
/// # JNI Signature
/// ```
/// public static native String inspectPayload(String path);
/// ```
///
/// # Arguments
/// * `path` - Path to the payload.bin file
///
/// # Returns
/// * JSON string with payload information on success
/// * JSON object with "error" field on failure
///
/// # Safety
/// This function is called from the JVM and must not panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_id_xms_payloadpack_native_NativeLib_inspectPayload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    init_logger();
    log::info!("inspectPayload called");

    // Extract the path string from JNI
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get path string: {:?}", e);
            let error_json = r#"{"error": "Failed to get path string"}"#;
            return match env.new_string(error_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    log::info!("Inspecting payload: {}", path_str);

    // Call the payload inspection function
    let result = match payload::inspect_payload_json(&path_str) {
        Ok(json) => json,
        Err(e) => {
            log::error!("Payload inspection failed: {}", e);
            format!(r#"{{"error": "{}"}}"#, e.replace('"', "'"))
        }
    };

    match env.new_string(&result) {
        Ok(output) => output.into_raw(),
        Err(e) => {
            log::error!("Failed to create result string: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// JNI Function: Library initialization
/// Called when System.loadLibrary() is executed
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(
    vm: jni::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    init_logger();
    log::info!("PayloadPack native library loaded successfully");
    
    // Verify we can get an environment
    match vm.get_env() {
        Ok(_) => log::debug!("JNI environment verified"),
        Err(e) => log::error!("Failed to get JNI environment: {:?}", e),
    }

    jni::sys::JNI_VERSION_1_6
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_display() {
        let error = PayloadPackError::InvalidInput("test".to_string());
        assert_eq!(error.to_string(), "Invalid input: test");
    }
}
