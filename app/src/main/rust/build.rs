//! Build script for PayloadPack
//!
//! This script compiles the protobuf definitions for Android OTA payload parsing.
//! If protoc is not available, it will use pre-generated code.

use std::path::Path;

fn main() {
    let out_dir = Path::new("src/proto");
    let proto_file = Path::new("proto/update_metadata.proto");
    let generated_file = out_dir.join("chromeos_update_engine.rs");

    // Check if we need to regenerate
    if generated_file.exists() {
        println!("cargo:warning=Using pre-generated protobuf code");
        println!("cargo:rerun-if-changed=src/proto/chromeos_update_engine.rs");
        return;
    }

    // Ensure output directory exists
    std::fs::create_dir_all(out_dir).expect("Failed to create proto output directory");

    // Try to compile protobuf
    println!("cargo:rerun-if-changed=proto/update_metadata.proto");

    match prost_build::Config::new()
        .out_dir(out_dir)
        .compile_protos(&[proto_file], &[Path::new("proto/")])
    {
        Ok(_) => {
            println!("cargo:warning=Protobuf compiled successfully");
        }
        Err(e) => {
            // If protoc is not available, panic with a helpful message
            panic!(
                "Failed to compile protobuf files: {:?}\n\n\
                 Please either:\n\
                 1. Install protoc: https://github.com/protocolbuffers/protobuf/releases\n\
                 2. Or use the pre-generated code by running:\n\
                    protoc --rust_out=src/proto proto/update_metadata.proto\n\n\
                 The pre-generated file should be placed at: src/proto/chromeos_update_engine.rs",
                e
            );
        }
    }
}
