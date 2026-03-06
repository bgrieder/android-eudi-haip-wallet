#!/usr/bin/env sh

# --- Configuration ---
AVD_NAME="EUDI_Dev_Device"
DEVICE_PROFILE="pixel_9"

ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    SYS_IMG="system-images;android-34;google_apis;arm64-v8a"
else
    SYS_IMG="system-images;android-34;google_apis;x86_64"
fi

if [ -z "$ANDROID_SDK_ROOT" ]; then
    if [ "$(uname)" = "Darwin" ]; then
        ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    else
        ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    fi
fi

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"

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

# Robust profile check
AVD_INI="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
EXISTING_PROFILE=$(grep -E "hw.device.name|device.name" "$AVD_INI" 2>/dev/null | cut -d'=' -f2)

if [ -n "$EXISTING_PROFILE" ] && [ "$EXISTING_PROFILE" != "$DEVICE_PROFILE" ]; then
    echo "Existing AVD has profile '$EXISTING_PROFILE', but '$DEVICE_PROFILE' is requested."
    echo "Deleting old AVD to apply new profile..."
    "$AVDMANAGER" delete avd -n "$AVD_NAME"
fi

if ! "$EMULATOR" -list-avds | grep -q "^$AVD_NAME$"; then
    echo "AVD '$AVD_NAME' not found. Creating with profile $DEVICE_PROFILE..."
    yes | "$SDKMANAGER" --install "$SYS_IMG"
    echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYS_IMG" -d "$DEVICE_PROFILE" --force
fi

# Apply Hardware Config
if [ -f "$AVD_INI" ]; then
    echo "Forcing VirtualScene Camera, Keyboard and Performance config..."
    sed -i.bak -e '/hw.keyboard/d' -e '/hw.camera.back/d' -e '/hw.camera.front/d' \
               -e '/hw.mainKeys/d' -e '/hw.gpu.enabled/d' -e '/hw.gpu.mode/d' \
               -e '/hw.ramSize/d' -e '/vm.heapSize/d' "$AVD_INI"
    {
        echo "hw.keyboard=yes"
        echo "hw.camera.back=virtualscene"
        echo "hw.camera.front=emulated"
        echo "hw.mainKeys=no"
        echo "hw.gpu.enabled=yes"
        echo "hw.gpu.mode=host"
        echo "hw.ramSize=4096"
        echo "vm.heapSize=512"
    } >> "$AVD_INI"
    rm "$AVD_INI.bak" 2>/dev/null
fi

SERIAL=$($ADB devices | grep emulator | head -n 1 | awk '{print $1}')
if [ -z "$SERIAL" ]; then
    echo "Starting emulator..."
    "$EMULATOR" -avd "$AVD_NAME" -writable-system -no-snapshot-load > /dev/null 2>&1 &
    "$ADB" wait-for-device
    SERIAL=$($ADB devices | grep emulator | head -n 1 | awk '{print $1}')
fi

if [ "$(uname)" = "Darwin" ]; then
    LOCAL_IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1)
else
    LOCAL_IP=$(hostname -I | awk '{print $1}')
fi

echo "Waiting for boot..."
while [ "$($ADB -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done

echo "Setting up root/remount..."
"$ADB" -s "$SERIAL" root
sleep 2
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell avbctl disable-verification > /dev/null 2>&1
"$ADB" -s "$SERIAL" reboot
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" root
sleep 2
"$ADB" -s "$SERIAL" remount

echo "Updating hosts..."
"$ADB" -s "$SERIAL" pull /system/etc/hosts ./android-hosts
sed -i.bak '/ewqwe.local/d' ./android-hosts
echo "$LOCAL_IP    ewqwe.local" >> ./android-hosts
"$ADB" -s "$SERIAL" push ./android-hosts /system/etc/hosts
rm ./android-hosts ./android-hosts.bak 2>/dev/null

echo "SUCCESS: Emulator ready at $LOCAL_IP"
