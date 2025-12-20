#!/bin/bash
# ============================================================================
# PayloadPack - Rust Development Setup for Arch Linux
# ============================================================================
# This script installs all necessary dependencies for Rust Android development
# Run this ONCE before using build-debug-rust.sh
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
echo -e " PayloadPack - Rust Setup for Arch Linux"
echo -e "========================================${NC}"
echo ""

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

# Check if running on Arch Linux
if [[ ! -f /etc/arch-release ]]; then
    print_warning "This script is designed for Arch Linux."
    read -p "Continue anyway? (y/N): " CONTINUE
    if [[ "${CONTINUE,,}" != "y" ]]; then
        echo "Exiting."
        exit 0
    fi
fi

# =========================================================================
# STEP 1: Install Base Packages
# =========================================================================
echo -e "${CYAN}[1/6] Installing base development packages...${NC}"

PACKAGES=(
    "base-devel"
    "git"
    "curl"
    "wget"
    "unzip"
)

sudo pacman -S --needed --noconfirm "${PACKAGES[@]}"
print_status "Base packages installed"

# =========================================================================
# STEP 2: Install JDK
# =========================================================================
echo ""
echo -e "${CYAN}[2/6] Installing JDK 17...${NC}"

if ! command -v java &>/dev/null; then
    sudo pacman -S --needed --noconfirm jdk17-openjdk
    print_status "JDK 17 installed"
else
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    print_status "Java already installed: $JAVA_VERSION"
fi

# Set JAVA_HOME if not set
if [[ -z "$JAVA_HOME" ]]; then
    print_info "Adding JAVA_HOME to ~/.bashrc"
    echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
fi

# =========================================================================
# STEP 3: Install Android Tools (ADB)
# =========================================================================
echo ""
echo -e "${CYAN}[3/6] Installing Android tools...${NC}"

if ! command -v adb &>/dev/null; then
    sudo pacman -S --needed --noconfirm android-tools
    print_status "Android tools (ADB) installed"
else
    print_status "ADB already installed: $(adb --version | head -n 1)"
fi

# =========================================================================
# STEP 4: Install Rust
# =========================================================================
echo ""
echo -e "${CYAN}[4/6] Installing Rust...${NC}"

if ! command -v rustc &>/dev/null; then
    print_info "Installing Rust via rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
    print_status "Rust installed: $(rustc --version)"
else
    print_status "Rust already installed: $(rustc --version)"
fi

# Ensure cargo is in PATH
if [[ -f "$HOME/.cargo/env" ]]; then
    source "$HOME/.cargo/env"
fi

# Add to bashrc if not present
if ! grep -q "cargo/env" ~/.bashrc 2>/dev/null; then
    echo '' >> ~/.bashrc
    echo '# Rust' >> ~/.bashrc
    echo 'source "$HOME/.cargo/env"' >> ~/.bashrc
    print_info "Added Rust to ~/.bashrc"
fi

# =========================================================================
# STEP 5: Install cargo-ndk and Android targets
# =========================================================================
echo ""
echo -e "${CYAN}[5/6] Installing cargo-ndk and Android targets...${NC}"

# Install cargo-ndk
if ! command -v cargo-ndk &>/dev/null; then
    print_info "Installing cargo-ndk..."
    cargo install cargo-ndk
    print_status "cargo-ndk installed"
else
    print_status "cargo-ndk already installed"
fi

# Add Android targets
print_info "Adding Rust Android targets..."
rustup target add aarch64-linux-android    # arm64-v8a
rustup target add armv7-linux-androideabi  # armeabi-v7a
rustup target add x86_64-linux-android     # x86_64
rustup target add i686-linux-android       # x86

print_status "Android targets added:"
echo "  - aarch64-linux-android (arm64-v8a)"
echo "  - armv7-linux-androideabi (armeabi-v7a)"
echo "  - x86_64-linux-android"
echo "  - i686-linux-android"

# =========================================================================
# STEP 6: Android SDK/NDK Setup
# =========================================================================
echo ""
echo -e "${CYAN}[6/6] Checking Android SDK/NDK setup...${NC}"

# Check ANDROID_HOME
if [[ -z "$ANDROID_HOME" ]]; then
    POSSIBLE_SDK_PATHS=(
        "$HOME/Android/Sdk"
        "/opt/android-sdk"
        "$HOME/.android/sdk"
    )
    
    for SDK_PATH in "${POSSIBLE_SDK_PATHS[@]}"; do
        if [[ -d "$SDK_PATH" ]]; then
            export ANDROID_HOME="$SDK_PATH"
            break
        fi
    done
    
    if [[ -z "$ANDROID_HOME" ]]; then
        print_warning "Android SDK not found!"
        echo ""
        echo "You need to install Android SDK. Options:"
        echo ""
        echo "Option 1: Install Android Studio (recommended)"
        echo "  - Download from: https://developer.android.com/studio"
        echo "  - SDK will be at: ~/Android/Sdk"
        echo ""
        echo "Option 2: Install command-line tools only"
        echo "  yay -S android-sdk  # From AUR"
        echo "  # or manually download command-line tools"
        echo ""
        echo "After installation, add to ~/.bashrc:"
        echo '  export ANDROID_HOME=$HOME/Android/Sdk'
        echo '  export PATH=$PATH:$ANDROID_HOME/platform-tools'
        echo ""
    else
        print_status "ANDROID_HOME: $ANDROID_HOME"
        
        # Add to bashrc if not present
        if ! grep -q "ANDROID_HOME" ~/.bashrc 2>/dev/null; then
            echo '' >> ~/.bashrc
            echo '# Android SDK' >> ~/.bashrc
            echo "export ANDROID_HOME=\"$ANDROID_HOME\"" >> ~/.bashrc
            echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
            print_info "Added ANDROID_HOME to ~/.bashrc"
        fi
    fi
fi

# Check ANDROID_NDK_HOME
if [[ -z "$ANDROID_NDK_HOME" ]] && [[ -n "$ANDROID_HOME" ]]; then
    NDK_BASE="$ANDROID_HOME/ndk"
    if [[ -d "$NDK_BASE" ]]; then
        LATEST_NDK=$(ls -1 "$NDK_BASE" 2>/dev/null | sort -V | tail -1)
        if [[ -n "$LATEST_NDK" ]]; then
            export ANDROID_NDK_HOME="${NDK_BASE}/${LATEST_NDK}"
            
            # Add to bashrc if not present
            if ! grep -q "ANDROID_NDK_HOME" ~/.bashrc 2>/dev/null; then
                echo "export ANDROID_NDK_HOME=\"$ANDROID_NDK_HOME\"" >> ~/.bashrc
                print_info "Added ANDROID_NDK_HOME to ~/.bashrc"
            fi
        fi
    fi
fi

if [[ -n "$ANDROID_NDK_HOME" ]]; then
    print_status "ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
else
    print_warning "Android NDK not found!"
    echo ""
    echo "Install NDK from Android Studio SDK Manager or manually:"
    echo "  1. Open Android Studio > SDK Manager"
    echo "  2. SDK Tools tab > Check 'NDK (Side by side)'"
    echo "  3. Apply"
    echo ""
    echo "Or install via command line (if you have sdkmanager):"
    echo '  $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;26.1.10909125"'
    echo ""
fi

# =========================================================================
# Summary
# =========================================================================
echo ""
echo -e "${CYAN}========================================"
echo -e " Setup Complete!"
echo -e "========================================${NC}"
echo ""

# Check status
echo "Current Status:"
echo ""

if command -v rustc &>/dev/null; then
    echo -e "  ${GREEN}✓${NC} Rust: $(rustc --version 2>/dev/null)"
else
    echo -e "  ${RED}✗${NC} Rust: Not installed"
fi

if command -v cargo-ndk &>/dev/null; then
    echo -e "  ${GREEN}✓${NC} cargo-ndk: Installed"
else
    echo -e "  ${RED}✗${NC} cargo-ndk: Not installed"
fi

if command -v java &>/dev/null; then
    echo -e "  ${GREEN}✓${NC} Java: $(java --version 2>&1 | head -n 1)"
else
    echo -e "  ${RED}✗${NC} Java: Not installed"
fi

if command -v adb &>/dev/null; then
    echo -e "  ${GREEN}✓${NC} ADB: $(adb --version 2>/dev/null | head -n 1)"
else
    echo -e "  ${RED}✗${NC} ADB: Not installed"
fi

if [[ -n "$ANDROID_HOME" ]] && [[ -d "$ANDROID_HOME" ]]; then
    echo -e "  ${GREEN}✓${NC} ANDROID_HOME: $ANDROID_HOME"
else
    echo -e "  ${YELLOW}⚠${NC} ANDROID_HOME: Not set"
fi

if [[ -n "$ANDROID_NDK_HOME" ]] && [[ -d "$ANDROID_NDK_HOME" ]]; then
    echo -e "  ${GREEN}✓${NC} ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
else
    echo -e "  ${YELLOW}⚠${NC} ANDROID_NDK_HOME: Not set (required for Rust build)"
fi

echo ""
echo "Next steps:"
echo "  1. Restart your terminal (or run: source ~/.bashrc)"
echo "  2. Run: ./build-debug-rust.sh"
echo ""
