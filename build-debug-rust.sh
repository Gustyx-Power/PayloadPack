#!/bin/bash
# ============================================================================
# PayloadPack - Rust + Debug Build Script for Linux (Arch Linux)
# ============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo ""
echo -e "${CYAN}========================================"
echo -e " PayloadPack Debug Build (with Rust)"
echo -e "========================================${NC}"
echo ""

# Get script directory (project root)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_PROJECT="${PROJECT_ROOT}/app/src/main/rust"
JNILIBS_DIR="${PROJECT_ROOT}/app/src/main/jniLibs"
LOG_FILE="${PROJECT_ROOT}/build_log.txt"

# Load Telegram credentials from gradle.properties
TG_BOT_TOKEN=""
TG_CHAT_ID=""
TG_MESSAGE_ID=""

if [[ -f "${PROJECT_ROOT}/gradle.properties" ]]; then
    TG_BOT_TOKEN=$(grep "^telegramBotToken=" "${PROJECT_ROOT}/gradle.properties" | cut -d'=' -f2)
    TG_CHAT_ID=$(grep "^telegramChatId=" "${PROJECT_ROOT}/gradle.properties" | cut -d'=' -f2)
fi

# Initialize log file
echo "PayloadPack Debug Build Log" > "${LOG_FILE}"
echo "Started: $(date)" >> "${LOG_FILE}"
echo "========================================" >> "${LOG_FILE}"
echo "" >> "${LOG_FILE}"

# Function to print colored status
print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

# Ask for Telegram upload
UPLOAD_TO_TELEGRAM=0
read -p "Apakah ingin mengirim APK ke Telegram? (y/N): " UPLOAD_CHOICE
if [[ "${UPLOAD_CHOICE,,}" == "y" ]]; then
    UPLOAD_TO_TELEGRAM=1
    echo "OK APK akan dikirim ke Telegram setelah build selesai."
else
    echo "OK APK tidak akan dikirim ke Telegram."
fi
echo ""

# =========================================================================
# [STEP 0/5] INSTALL DEPENDENCIES (Arch Linux)
# =========================================================================
install_dependencies() {
    echo -e "${CYAN}[0/5] Checking and installing dependencies...${NC}"
    
    # Check if running on Arch Linux
    if [[ -f /etc/arch-release ]]; then
        print_info "Detected Arch Linux"
        
        # Check if pacman packages are installed
        PACKAGES_TO_INSTALL=""
        
        # Base development tools
        for pkg in base-devel; do
            if ! pacman -Qi "$pkg" &>/dev/null; then
                PACKAGES_TO_INSTALL="$PACKAGES_TO_INSTALL $pkg"
            fi
        done
        
        # Android SDK related - from AUR or manual setup
        if ! command -v adb &>/dev/null; then
            print_warning "ADB not found. You may need to install android-tools from pacman."
            if ! pacman -Qi android-tools &>/dev/null; then
                PACKAGES_TO_INSTALL="$PACKAGES_TO_INSTALL android-tools"
            fi
        fi
        
        # JDK for Gradle
        if ! command -v java &>/dev/null; then
            print_warning "Java not found. Installing JDK 17..."
            PACKAGES_TO_INSTALL="$PACKAGES_TO_INSTALL jdk17-openjdk"
        fi
        
        # curl for Telegram API
        if ! command -v curl &>/dev/null; then
            PACKAGES_TO_INSTALL="$PACKAGES_TO_INSTALL curl"
        fi
        
        # Install missing packages
        if [[ -n "$PACKAGES_TO_INSTALL" ]]; then
            print_info "Installing missing packages:$PACKAGES_TO_INSTALL"
            sudo pacman -S --needed --noconfirm $PACKAGES_TO_INSTALL
        else
            print_status "All pacman dependencies are installed"
        fi
    fi
    
    # Check and install Rust
    if ! command -v rustc &>/dev/null; then
        print_warning "Rust not found. Installing Rust via rustup..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        source "$HOME/.cargo/env"
    else
        print_status "Rust is installed: $(rustc --version)"
    fi
    
    # Ensure cargo is in PATH
    if [[ -f "$HOME/.cargo/env" ]]; then
        source "$HOME/.cargo/env"
    fi
    
    # Check and install cargo-ndk
    if ! command -v cargo-ndk &>/dev/null; then
        print_info "Installing cargo-ndk..."
        cargo install cargo-ndk
    else
        print_status "cargo-ndk is installed"
    fi
    
    # Add Android targets
    print_info "Adding Android Rust targets..."
    rustup target add aarch64-linux-android
    rustup target add armv7-linux-androideabi
    rustup target add x86_64-linux-android
    rustup target add i686-linux-android
    
    # Check ANDROID_NDK_HOME
    if [[ -z "$ANDROID_NDK_HOME" ]]; then
        print_warning "ANDROID_NDK_HOME not set!"
        
        # Try to find NDK
        POSSIBLE_NDK_PATHS=(
            "$HOME/Android/Sdk/ndk"
            "$ANDROID_HOME/ndk"
            "/opt/android-sdk/ndk"
            "/opt/android-ndk"
        )
        
        for NDK_BASE in "${POSSIBLE_NDK_PATHS[@]}"; do
            if [[ -d "$NDK_BASE" ]]; then
                # Find the latest NDK version
                LATEST_NDK=$(ls -1 "$NDK_BASE" 2>/dev/null | sort -V | tail -1)
                if [[ -n "$LATEST_NDK" ]]; then
                    export ANDROID_NDK_HOME="${NDK_BASE}/${LATEST_NDK}"
                    print_status "Found NDK at: $ANDROID_NDK_HOME"
                    break
                fi
            fi
        done
        
        if [[ -z "$ANDROID_NDK_HOME" ]]; then
            print_error "Android NDK not found!"
            echo ""
            echo "Please install Android NDK and set ANDROID_NDK_HOME:"
            echo "  1. Install via Android Studio SDK Manager"
            echo "  2. Or manually: https://developer.android.com/ndk/downloads"
            echo "  3. Export: export ANDROID_NDK_HOME=/path/to/ndk"
            echo ""
            echo "Add to your ~/.bashrc or ~/.zshrc:"
            echo "  export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/<version>"
            exit 1
        fi
    else
        print_status "ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
    fi
    
    echo ""
}

# Run dependency check
install_dependencies

# =========================================================================
# [STEP 1/5] BUILD RUST NATIVE LIBRARY
# =========================================================================
echo -e "${CYAN}[1/5] Building Rust native library...${NC}"
echo "[1/5] Building Rust native library..." >> "${LOG_FILE}"

# Check Rust installation one more time
if ! command -v rustc &>/dev/null; then
    print_error "Rust not found! Please install from https://rustup.rs"
    exit 1
fi
print_status "Rust: $(rustc --version)"

# Check cargo-ndk
if ! command -v cargo-ndk &>/dev/null; then
    print_info "Installing cargo-ndk..."
    cargo install cargo-ndk >> "${LOG_FILE}" 2>&1
fi
print_status "cargo-ndk: $(cargo ndk --version 2>/dev/null || echo 'installed')"

# Navigate to Rust project
cd "${RUST_PROJECT}"

# Clean up stale locks
rm -f target/aarch64-linux-android/debug/.cargo-lock 2>/dev/null
rm -f target/.cargo-lock 2>/dev/null

# Build for Android
echo "Compiling Rust for Android (arm64-v8a)..."
mkdir -p "${JNILIBS_DIR}/arm64-v8a"

# Build with retry
BUILD_ATTEMPT=1
MAX_ATTEMPTS=3

while [[ $BUILD_ATTEMPT -le $MAX_ATTEMPTS ]]; do
    if cargo ndk -t arm64-v8a -o "${JNILIBS_DIR}" build 2>&1 | tee -a "${LOG_FILE}"; then
        break
    else
        if [[ $BUILD_ATTEMPT -lt $MAX_ATTEMPTS ]]; then
            print_warning "Rust build failed, retrying... (Attempt $BUILD_ATTEMPT/$MAX_ATTEMPTS)"
            ((BUILD_ATTEMPT++))
            
            # Clean deps
            rm -rf target/aarch64-linux-android/debug/deps 2>/dev/null
            sleep 2
        else
            print_error "RUST BUILD FAILED after $MAX_ATTEMPTS attempts!"
            exit 1
        fi
    fi
done

print_status "Rust library built successfully!"
echo ""

# =========================================================================
# [STEP 2/5] VERIFY NATIVE LIBRARY
# =========================================================================
echo -e "${CYAN}[2/5] Verifying native library...${NC}"

if [[ -f "${JNILIBS_DIR}/arm64-v8a/libpayloadpack.so" ]]; then
    LIB_SIZE=$(du -h "${JNILIBS_DIR}/arm64-v8a/libpayloadpack.so" | cut -f1)
    print_status "arm64-v8a/libpayloadpack.so ($LIB_SIZE)"
else
    print_error "Native library libpayloadpack.so not found!"
    echo "Looking for library in: ${JNILIBS_DIR}/arm64-v8a/"
    ls -la "${JNILIBS_DIR}/arm64-v8a/" 2>/dev/null || echo "Directory not found"
    exit 1
fi
echo ""

cd "${PROJECT_ROOT}"

# =========================================================================
# DEVICE DETECTION
# =========================================================================
echo -e "${CYAN}Detecting connected Android devices...${NC}"

DEVICE_COUNT=0
SKIP_INSTALL=0
SELECTED_DEVICE=""

if command -v adb &>/dev/null; then
    # Get list of connected devices
    while IFS= read -r line; do
        if [[ "$line" =~ ^[a-zA-Z0-9]+ ]] && [[ "$line" =~ device$ ]]; then
            DEVICE_SERIAL=$(echo "$line" | awk '{print $1}')
            DEVICE_MODEL=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
            DEVICE_VERSION=$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
            
            ((DEVICE_COUNT++))
            eval "DEVICE_${DEVICE_COUNT}=\"$DEVICE_SERIAL\""
            eval "MODEL_${DEVICE_COUNT}=\"$DEVICE_MODEL\""
            eval "VERSION_${DEVICE_COUNT}=\"$DEVICE_VERSION\""
        fi
    done < <(adb devices 2>/dev/null)
    
    if [[ $DEVICE_COUNT -eq 0 ]]; then
        print_warning "No devices connected via USB."
        SKIP_INSTALL=1
    elif [[ $DEVICE_COUNT -eq 1 ]]; then
        SELECTED_DEVICE="$DEVICE_1"
        print_status "Device detected: $DEVICE_1 - $MODEL_1 - Android $VERSION_1"
    else
        echo "Multiple devices detected:"
        for i in $(seq 1 $DEVICE_COUNT); do
            eval "echo \"$i. \$DEVICE_$i - \$MODEL_$i - Android \$VERSION_$i\""
        done
        read -p "Select device (1-$DEVICE_COUNT): " CHOICE
        if [[ "$CHOICE" =~ ^[0-9]+$ ]] && [[ $CHOICE -ge 1 ]] && [[ $CHOICE -le $DEVICE_COUNT ]]; then
            eval "SELECTED_DEVICE=\"\$DEVICE_$CHOICE\""
        else
            print_warning "Invalid choice, skipping installation."
            SKIP_INSTALL=1
        fi
    fi
else
    print_warning "ADB not found, skipping device detection."
    SKIP_INSTALL=1
fi

# =========================================================================
# [STEP 3/5] BUILD ANDROID APK
# =========================================================================
echo ""
echo -e "${CYAN}[3/5] Building debug APK...${NC}"
echo ""

WORKERS=$(nproc 2>/dev/null || echo 4)

if [[ -f "${PROJECT_ROOT}/gradlew" ]]; then
    chmod +x "${PROJECT_ROOT}/gradlew"
    ./gradlew assembleDebug --parallel --max-workers="$WORKERS" --console=plain
    GRADLE_EXIT=$?
else
    print_error "gradlew not found!"
    exit 1
fi

if [[ $GRADLE_EXIT -ne 0 ]]; then
    print_error "GRADLE BUILD FAILED!"
    exit 1
fi

print_status "Debug APK built successfully!"
echo ""

# =========================================================================
# [STEP 4/5] INSTALL APK
# =========================================================================
APK_PATH=$(find "${PROJECT_ROOT}/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)

if [[ -f "$APK_PATH" ]]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    print_status "APK: $APK_PATH ($APK_SIZE)"
    
    if [[ $SKIP_INSTALL -eq 0 ]] && [[ -n "$SELECTED_DEVICE" ]]; then
        echo -e "${CYAN}[4/5] Installing APK to device $SELECTED_DEVICE...${NC}"
        if adb -s "$SELECTED_DEVICE" install -r "$APK_PATH" >> "${LOG_FILE}" 2>&1; then
            print_status "APK installed successfully."
        else
            print_warning "Installation failed. May need to uninstall manually first."
        fi
    else
        echo -e "${YELLOW}[4/5] Skipping installation - no device connected.${NC}"
    fi
else
    print_error "APK not found!"
    exit 1
fi

echo ""

# =========================================================================
# [STEP 5/5] UPLOAD TO TELEGRAM
# =========================================================================
if [[ $UPLOAD_TO_TELEGRAM -eq 1 ]]; then
    echo -e "${CYAN}[5/5] Uploading to Telegram...${NC}"
    
    # Rename APK
    ./gradlew renameDebugApk >> "${LOG_FILE}" 2>&1
    
    # Upload to Telegram
    if ./gradlew uploadDebugApkToTelegram >> "${LOG_FILE}" 2>&1; then
        print_status "APK uploaded to Telegram successfully!"
    else
        print_warning "Telegram upload may have failed. Check logs."
    fi
else
    echo -e "${YELLOW}[5/5] Skipped - Telegram upload not requested.${NC}"
fi

# =========================================================================
# BUILD SUCCESS
# =========================================================================
echo ""
echo -e "${GREEN}========================================"
echo -e " BUILD COMPLETED SUCCESSFULLY!"
echo -e "========================================${NC}"
echo ""
echo "APK Location: $APK_PATH"
echo ""
echo "Build log: $LOG_FILE"
echo ""
