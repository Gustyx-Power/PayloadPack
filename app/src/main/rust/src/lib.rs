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

/// JNI Function: Extract partition images from payload.bin
///
/// Extracts all partitions from a payload.bin file to the specified output directory.
/// Uses streaming I/O to handle large files without OOM.
///
/// # JNI Signature
/// ```
/// public static native String extractPayload(String payloadPath, String outputDir, ProgressListener listener);
/// ```
///
/// # Arguments
/// * `payloadPath` - Path to the payload.bin file
/// * `outputDir` - Directory where .img files will be written
/// * `progressListener` - Optional callback for progress updates
///
/// # Returns
/// * JSON string with status and result
///
/// Success response:
/// ```json
/// {
///   "status": "success",
///   "extracted": [
///     {"name": "system", "size": 2147483648, "path": "/data/PayloadPack/project/system.img"},
///     {"name": "vendor", "size": 536870912, "path": "/data/PayloadPack/project/vendor.img"}
///   ]
/// }
/// ```
///
/// Error response:
/// ```json
/// {
///   "status": "error",
///   "message": "Failed to write partition: Permission denied"
/// }
/// ```
///
/// # Safety
/// This function is called from the JVM and must not panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_id_xms_payloadpack_native_NativeLib_extractPayload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload_path: JString<'local>,
    output_dir: JString<'local>,
    progress_listener: jni::sys::jobject,
) -> jstring {
    init_logger();
    log::info!("extractPayload called");

    // Extract path strings from JNI
    let payload_path_str: String = match env.get_string(&payload_path) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get payload path: {:?}", e);
            let error_json = r#"{"status":"error","message":"Failed to get payload path"}"#;
            return match env.new_string(error_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    let output_dir_str: String = match env.get_string(&output_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get output dir: {:?}", e);
            let error_json = r#"{"status":"error","message":"Failed to get output directory"}"#;
            return match env.new_string(error_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    log::info!("Extracting payload: {} -> {}", payload_path_str, output_dir_str);

    // Create a progress callback closure
    let progress_callback: Option<Box<dyn Fn(&str, i32, i64, i64) + Send>> = if !progress_listener.is_null() {
        // Convert jobject to GlobalRef to keep it alive across calls
        let listener_global = match env.new_global_ref(unsafe { jni::objects::JObject::from_raw(progress_listener) }) {
            Ok(global) => global,
            Err(e) => {
                log::error!("Failed to create global ref for listener: {:?}", e);
                let error_json = r#"{"status":"error","message":"Failed to create global ref for listener"}"#;
                return match env.new_string(error_json) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        // Get JavaVM to attach thread for callbacks
        let jvm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(e) => {
                log::error!("Failed to get JavaVM: {:?}", e);
                let error_json = r#"{"status":"error","message":"Failed to get JavaVM"}"#;
                return match env.new_string(error_json) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        Some(Box::new(move |current_file: &str, progress: i32, bytes_processed: i64, total_bytes: i64| {
            // Attach current thread to JVM (safe to call multiple times)
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(e) => {
                    log::error!("Failed to attach thread: {:?}", e);
                    return;
                }
            };

            // Create Java string for current file
            let j_current_file = match env.new_string(current_file) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("Failed to create string: {:?}", e);
                    return;
                }
            };

            // Call onProgress method
            let result = env.call_method(
                listener_global.as_obj(),
                "onProgress",
                "(Ljava/lang/String;IJJ)V",
                &[
                    jni::objects::JValue::Object(&j_current_file),
                    jni::objects::JValue::Int(progress),
                    jni::objects::JValue::Long(bytes_processed),
                    jni::objects::JValue::Long(total_bytes),
                ],
            );

            if let Err(e) = result {
                log::error!("Failed to call onProgress: {:?}", e);
            }
        }))
    } else {
        None
    };

    // Call the extraction function with progress callback
    let result = match payload::extract_payload_json(&payload_path_str, &output_dir_str, progress_callback) {
        Ok(json) => json,
        Err(e) => {
            log::error!("Payload extraction failed: {}", e);
            format!(r#"{{"status":"error","message":"{}"}}"#, e.replace('"', "'"))
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
