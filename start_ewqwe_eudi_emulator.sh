#!/usr/bin/env sh

# --- Configuration ---
AVD_NAME="EUDI_Dev_Device"
# Using a larger device profile (Pixel 6 Pro)
DEVICE_PROFILE="pixel_6_pro"

# Change to "x86_64" if on an Intel Mac or Linux PC
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    SYS_IMG="system-images;android-34;google_apis;arm64-v8a"
else
    SYS_IMG="system-images;android-34;google_apis;x86_64"
fi

# 1. Detect SDK path based on OS (MacOS vs Linux)
if [ -z "$ANDROID_SDK_ROOT" ]; then
    if [ "$(uname)" = "Darwin" ]; then
        ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    else
        ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    fi
fi

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"

# Search for avdmanager and sdkmanager
find_tool() {
    tool_name=$1
    for path in "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/$tool_name" \
                "$ANDROID_SDK_ROOT/cmdline-tools/bin/$tool_name" \
                "$ANDROID_SDK_ROOT/tools/bin/$tool_name"; do
        if [ -f "$path" ]; then
            echo "$path"
            return 0
        fi
    done
    find "$ANDROID_SDK_ROOT/cmdline-tools" -name "$tool_name" -type f 2>/dev/null | head -n 1
}

SDKMANAGER=$(find_tool sdkmanager)
AVDMANAGER=$(find_tool avdmanager)

# 2. Check if the rootable AVD exists
if ! "$EMULATOR" -list-avds | grep -q "^$AVD_NAME$"; then
    echo "AVD '$AVD_NAME' not found. Attempting to create it..."

    if [ -z "$SDKMANAGER" ] || [ ! -f "$SDKMANAGER" ]; then
        echo "Error: sdkmanager not found in $ANDROID_SDK_ROOT"
        echo "Please install 'Android SDK Command-line Tools' in Android Studio."
        exit 1
    fi

    echo "Installing system image $SYS_IMG..."
    yes | "$SDKMANAGER" --install "$SYS_IMG"

    echo "Creating AVD with profile $DEVICE_PROFILE..."
    # -d specifies the device profile
    echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYS_IMG" -d "$DEVICE_PROFILE" --force

    # Enable hardware keyboard in config.ini
    AVD_CONFIG="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
    if [ -f "$AVD_CONFIG" ]; then
        echo "Enabling hardware keyboard and soft keys..."
        sed -i.bak '/hw.keyboard/d' "$AVD_CONFIG"
        echo "hw.keyboard=yes" >> "$AVD_CONFIG"
        sed -i.bak '/hw.mainKeys/d' "$AVD_CONFIG"
        echo "hw.mainKeys=no" >> "$AVD_CONFIG"
    fi
fi

# 3. Check if the emulator is already running
SERIAL=$($ADB devices | grep emulator | head -n 1 | awk '{print $1}')

if [ -z "$SERIAL" ]; then
    echo "Starting emulator '$AVD_NAME' with writable system..."
    "$EMULATOR" -avd "$AVD_NAME" -writable-system -no-snapshot-load > /dev/null 2>&1 &

    echo "Waiting for device to appear..."
    "$ADB" wait-for-device
    SERIAL=$($ADB devices | grep emulator | head -n 1 | awk '{print $1}')
else
    # Check if the running emulator is rootable
    RUNNING_AVD=$($ADB -s "$SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')
    echo "Emulator '$RUNNING_AVD' is already running ($SERIAL)."

    FLAVOR=$($ADB -s "$SERIAL" shell getprop ro.build.flavor 2>/dev/null)
    if echo "$FLAVOR" | grep -q "playstore"; then
        echo "WARNING: The running emulator has Play Store. Rooting will fail."
        echo "Please close it and run this script again."
        exit 1
    fi
fi

# 4. Get the local IP address
if [ "$(uname)" = "Darwin" ]; then
    LOCAL_IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1)
else
    LOCAL_IP=$(hostname -I | awk '{print $1}')
fi

echo "Using Local IP: $LOCAL_IP"

# 5. Root and Remount logic
echo "Waiting for boot to complete..."
while [ "$($ADB -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
    sleep 2
done

echo "Gaining root access..."
"$ADB" -s "$SERIAL" root
sleep 3
"$ADB" -s "$SERIAL" wait-for-device

# Disable AVB verification
VERITY_STATE=$($ADB -s "$SERIAL" shell getprop partition.system.verified)
if [ "$VERITY_STATE" != "" ]; then
    echo "Disabling AVB verification..."
    "$ADB" -s "$SERIAL" shell avbctl disable-verification
    "$ADB" -s "$SERIAL" reboot
    echo "Rebooting to apply verification changes..."
    "$ADB" -s "$SERIAL" wait-for-device
    sleep 5
    "$ADB" -s "$SERIAL" root
    sleep 3
    "$ADB" -s "$SERIAL" wait-for-device
fi

echo "Remounting /system as writable..."
"$ADB" -s "$SERIAL" remount

if [ $? -ne 0 ]; then
    echo "Error: Remount failed. Ensure you started the emulator with '-writable-system'."
    exit 1
fi

# 6. Update hosts
echo "Updating hosts file..."
"$ADB" -s "$SERIAL" pull /system/etc/hosts ./android-hosts
sed -i.bak '/ewqwe.local/d' ./android-hosts
echo "$LOCAL_IP    ewqwe.local" >> ./android-hosts
"$ADB" -s "$SERIAL" push ./android-hosts /system/etc/hosts

if [ $? -eq 0 ]; then
    echo "SUCCESS: 'ewqwe.local' now points to $LOCAL_IP on the emulator."
else
    echo "FAILURE: Could not update hosts file."
fi

rm ./android-hosts ./android-hosts.bak 2>/dev/null
