#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  TermuX Build Engine (Integrated with C-Compiler-Lite App)   ║
# ║  Native Build Toolchain Engine                               ║
# ╚══════════════════════════════════════════════════════════════╝
set -o pipefail

# Runtime paths are supplied by BootstrapInstaller. If launched manually, derive
# PREFIX from this script's installation path instead of assuming Termux's package.
if [ -z "${PREFIX:-}" ]; then
    _TERMUX_BUILD_SELF="$(command -v -- "${0##*/}" 2>/dev/null || true)"
    if [ "$_TERMUX_BUILD_SELF" = */bin/* ]; then
        PREFIX="${_TERMUX_BUILD_SELF%/bin/*}"
    else
        PREFIX="${0%/bin/*}"
    fi
fi
: "${HOME:=$(dirname "$PREFIX")/home}"
export PREFIX HOME
export SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"

detect_ndk_dir() {
    local ndk_base="$SDK_DIR/ndk"
    if [ -d "$ndk_base" ]; then
        local latest
        latest=$(ls -1 "$ndk_base" 2>/dev/null | grep -v '^tmp' | sort -V | tail -n1)
        if [ -n "$latest" ] && [ -d "$ndk_base/$latest" ]; then
            echo "$ndk_base/$latest"
            return
        fi
    fi
    echo "$ndk_base/25.2.9519653"
}
export NDK_DIR="$(detect_ndk_dir)"

export WRAPPER_DIR="$SDK_DIR/wrapper-template"
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$PREFIX/bin:$JAVA_HOME/bin:$PATH"

WORKSPACE="$HOME/workspace"
RESULT_JSON="$HOME/build-result.json"
TRACE_LOG="$HOME/build-trace.log"

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RESET='\033[0m'
info()  { echo -e "  ${CYAN}i${RESET}  $1" >&2; }
ok()    { echo -e "  ${GREEN}✓${RESET}  $1" >&2; }
warn()  { echo -e "  ${YELLOW}!${RESET}  $1" >&2; }
err()   { echo -e "  ${RED}x${RESET}  $1" >&2; }

write_result_json() {
    cat > "$RESULT_JSON" <<EOF
{ "success": $1, "build_type": "$2", "duration_seconds": $3, "apk_path": "$4", "error_message": "$5" }
EOF
}

LOCK_FILE="$HOME/.termux-build.lock"

acquire_lock() {
    local build_type="${1:-unknown}"
    if [ -f "$LOCK_FILE" ]; then
        local pid
        pid=$(cat "$LOCK_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            err "Build process is already running (PID: $pid). Please wait."
            write_result_json false "$build_type" 0 "" "Another build is already running (PID: $pid)"
            return 1
        fi
        warn "Stale lock detected (PID $pid). Cleaning up..."
        rm -f "$LOCK_FILE"
    fi
    echo $$ > "$LOCK_FILE"
    trap 'rm -f "$LOCK_FILE"' EXIT
    return 0
}

release_lock() {
    rm -f "$LOCK_FILE"
    trap - EXIT
}

live_animated_streamer() {
    python3 -u -c '
import sys, time, re
log_path = sys.argv[1] if len(sys.argv) > 1 else "/dev/null"
log_file = open(log_path, "w", encoding="utf-8", errors="ignore")

C_RED   = "\033[1;31m"; C_GREEN = "\033[1;32m"; C_YEL   = "\033[1;33m"
C_CYAN  = "\033[0;36m"; C_DIM   = "\033[2m"; C_RESET = "\033[0m"

spinner = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
spin_idx = 0; start_time = time.time(); line_count = 0

for line in sys.stdin:
    log_file.write(line); line_count += 1
    if line_count % 5 == 0: log_file.flush()

    stripped = line.strip()
    if not stripped: continue

    elapsed = int(time.time() - start_time)
    spin = spinner[spin_idx % len(spinner)]
    spin_idx += 1; s = line.rstrip()

    if re.search(r"(?i)\b(BUILD FAILED|ERROR|error:|e:|FAILED|Exception|fatal)\b", s): formatted = f"{C_RED}{s}{C_RESET}"
    elif re.search(r"(?i)\b(BUILD SUCCESSFUL|UP-TO-DATE|FROM-CACHE|Built target|SUCCESS)\b", s): formatted = f"{C_GREEN}{s}{C_RESET}"
    elif re.search(r"(?i)\b(WARNING|WARN|warning:|w:|Deprecated)\b", s): formatted = f"{C_YEL}{s}{C_RESET}"
    elif s.startswith("> Task") or "Building CXX" in s or "Linking CXX" in s or "Compiling" in s: formatted = f"{C_CYAN}{s}{C_RESET}"
    else: formatted = f"{C_DIM}{s}{C_RESET}"

    sys.stdout.write(f"\r\033[K{C_CYAN}[{spin} {elapsed}s]{C_RESET} {formatted}\n")
    sys.stdout.flush()
log_file.close()
' "$1"
}

detect_device_hardware() {
    local mem_kb=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}')
    [ -z "$mem_kb" ] && mem_kb=4000000
    local mem_mb=$(( mem_kb / 1024 ))
    if [ "$mem_mb" -le 3500 ]; then GRADLE_OPTS="-Xmx448m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -Xverify:none"; DYNAMIC_WORKERS=1
    elif [ "$mem_mb" -le 5200 ]; then GRADLE_OPTS="-Xmx640m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC -Xverify:none"; DYNAMIC_WORKERS=1
    elif [ "$mem_mb" -le 8500 ]; then GRADLE_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Xverify:none"; DYNAMIC_WORKERS=2
    else GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m -Xverify:none"; DYNAMIC_WORKERS=4; fi
}

repair_dpkg_state() {
    info "Repairing dpkg/apt states..."
    dpkg --configure -a --force-all --force-confold --force-confdef >/dev/null 2>&1 || true
    apt-get --fix-broken install -y --allow-unauthenticated -o DPkg::Options::=--force-all >/dev/null 2>&1 || true
}

patch_new_packages_rpath() {
    info "Patching ELF RPATHs..."
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$PREFIX/lib"
    local running_patchelf=$(readlink -f "$PREFIX/bin/patchelf" 2>/dev/null)
    for f in "$PREFIX/bin/"* "$PREFIX/lib/"*.so*; do
        [ -f "$f" ] || continue
        [ "$f" = "$running_patchelf" ] && continue

        local magic=$(head -c 4 "$f" | xxd -p 2>/dev/null)
        [ "$magic" != "7f454c46" ] && continue

        bash -c "timeout 20 patchelf --set-rpath '\$ORIGIN/../lib' '$f'" >/dev/null 2>&1 || {
            echo "[RPATH:FAIL] $f (patchelf failed) - skipped" >> "$TRACE_LOG"
        }
    done
    ok "RPATH patched successfully."
}

fix_ndk_permissions() {
    if [ -d "$NDK_DIR" ]; then
        chmod -R +x "$NDK_DIR/toolchains" 2>/dev/null || true
        chmod +x "$NDK_DIR/ndk-build" 2>/dev/null || true
        mkdir -p "$NDK_DIR/prebuilt/linux-aarch64/bin"
        [ -f "$PREFIX/bin/make" ] && ln -sf "$PREFIX/bin/make" "$NDK_DIR/prebuilt/linux-aarch64/bin/make"
        [ -f "$PREFIX/bin/python3" ] && ln -sf "$PREFIX/bin/python3" "$NDK_DIR/prebuilt/linux-aarch64/bin/python3"
    fi
}

ensure_wrapper_template() {
    mkdir -p "$WRAPPER_DIR/gradle/wrapper"
    if [ ! -f "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" ] || [ $(wc -c < "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || echo 0) -lt 10000 ]; then
        cd "$WRAPPER_DIR" || return
        echo "rootProject.name='wrapper-template'" > settings.gradle
        wget -q -O "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
        gradle wrapper --gradle-version 8.7 --no-daemon -q 2>/dev/null || true
        cd "$HOME" || return
    fi
}

download_platform_sdk() {
    local api_level="$1"
    local platform_dir="$SDK_DIR/platforms/android-$api_level"

    if [ -d "$platform_dir" ] && [ -f "$platform_dir/android.jar" ]; then
        return 0
    fi

    info "Downloading Android SDK Platform $api_level..."
    mkdir -p "$SDK_DIR/platforms"
    local ok=""
    for rev in 02 01; do
        local url="https://dl.google.com/android/repository/platform-${api_level}_r${rev}.zip"
        if wget -q --show-progress -O "$SDK_DIR/platform.zip" "$url"; then
            ok=1; break
        fi
    done

    if [ -n "$ok" ]; then
        unzip -q "$SDK_DIR/platform.zip" -d "$SDK_DIR/platforms/tmp"
        local ext_dir=$(find "$SDK_DIR/platforms/tmp" -maxdepth 1 -mindepth 1 -type d | head -n1)
        mv "$ext_dir" "$platform_dir"
        rm -rf "$SDK_DIR/platforms/tmp" "$SDK_DIR/platform.zip"
        echo -e "Pkg.Revision=1\nAndroidVersion.ApiLevel=$api_level" > "$platform_dir/source.properties"
        ok "Platform API $api_level installed."
    else
        err "Failed to download Platform SDK $api_level."
    fi
}

auto_setup() {
    echo -e "\n[ Initializing Toolkit Environment ]\n"
    repair_dpkg_state

    info "Updating APT repositories and fetching core binaries..."
    apt-get update -y
    apt-get install -y -o Dir::Cache::archives="$SDK_DIR/pkg-cache" openjdk-17 python gradle android-tools rsync aapt aapt2 apksigner d8 aidl cmake ninja make wget curl git zip unzip perl p7zip clang patchelf

    mkdir -p "$SDK_DIR/platforms" "$SDK_DIR/build-tools" "$SDK_DIR/licenses" "$SDK_DIR/cmake"
    setup_dummy_build_tools "33.0.1"
    setup_dummy_build_tools "34.0.0"
    setup_dummy_cmake "3.22.1"
    setup_dummy_cmake "3.18.1"
    download_platform_sdk 34

    echo "24333f8a637bced5e17096433f01641e5f692d6e" > "$SDK_DIR/licenses/android-sdk-license"
    ensure_wrapper_template

    if [ ! -d "$NDK_DIR" ]; then
        info "Downloading Android NDK r25c..."
        local NDK_URL="https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
        if wget -q --show-progress -O "$SDK_DIR/ndk.zip" "$NDK_URL"; then
            mkdir -p "$SDK_DIR/ndk/tmp"
            unzip -q "$SDK_DIR/ndk.zip" -d "$SDK_DIR/ndk/tmp"
            local EXTRACTED_NDK=$(ls "$SDK_DIR/ndk/tmp" | head -n1)
            mv "$SDK_DIR/ndk/tmp/$EXTRACTED_NDK" "$NDK_DIR"
            rm -rf "$SDK_DIR/ndk/tmp" "$SDK_DIR/ndk.zip"
            ok "NDK installed."
        fi
    fi

    fix_ndk_permissions
    patch_new_packages_rpath
    ok "Environment initialization complete."
}

import_backup() {
    local backup_file="$1"
    if [ ! -f "$backup_file" ]; then
        err "Package file not found: $backup_file"
        return 1
    fi
    echo -e "\n[ Extracting Local Environment: $(basename "$backup_file") ]\n"

    local TEMP_RESTORE="$HOME/.restore-temp"
    rm -rf "$TEMP_RESTORE"; mkdir -p "$TEMP_RESTORE"

    info "Unpacking archive... This might take a while..."
    if [[ "$backup_file" == *.7z ]]; then
        7z x -o"$TEMP_RESTORE/" "$backup_file" -y -bsp0 2>/dev/null
    else
        unzip -q -o "$backup_file" -d "$TEMP_RESTORE/"
    fi

    if [ -d "$TEMP_RESTORE/pkg-cache" ] && [ -n "$(ls -A "$TEMP_RESTORE/pkg-cache/"*.deb 2>/dev/null)" ]; then
        info "Installing offline system dependencies (.deb)..."
        dpkg -i --force-all --force-confold --force-confdef "$TEMP_RESTORE/pkg-cache/"*.deb
        repair_dpkg_state
        mkdir -p "$SDK_DIR/pkg-cache"
        cp "$TEMP_RESTORE/pkg-cache/"*.deb "$SDK_DIR/pkg-cache/" 2>/dev/null || true
    fi

    info "Restoring SDK platforms & Gradle cache..."
    [ -d "$TEMP_RESTORE/android-sdk" ] && rsync -a "$TEMP_RESTORE/android-sdk/" "$SDK_DIR/"
    [ -d "$TEMP_RESTORE/.gradle" ] && rsync -a "$TEMP_RESTORE/.gradle/" "$HOME/.gradle/"
    [ -d "$TEMP_RESTORE/wrapper-template" ] && rsync -a "$TEMP_RESTORE/wrapper-template/" "$WRAPPER_DIR/"

    mkdir -p "$SDK_DIR/ndk"
    local ndk_archive=$(find "$TEMP_RESTORE" "$SDK_DIR/ndk" /sdcard -maxdepth 2 \( -iname "android-ndk-*.7z" -o -iname "android-ndk-*.zip" \) 2>/dev/null | head -n1)
    if [ -n "$ndk_archive" ] && [ -f "$ndk_archive" ]; then
        info "Extracting Native Development Kit (NDK)..."
        mkdir -p "$SDK_DIR/ndk/tmp_ndk"
        if [[ "$ndk_archive" == *.7z ]]; then
            7z x -o"$SDK_DIR/ndk/tmp_ndk" "$ndk_archive" -y >/dev/null
        else
            unzip -q "$ndk_archive" -d "$SDK_DIR/ndk/tmp_ndk"
        fi

        local extracted_ndk_dir=$(find "$SDK_DIR/ndk/tmp_ndk" -maxdepth 2 -name "ndk-build" -exec dirname {} \; 2>/dev/null | head -n1)
        if [ -n "$extracted_ndk_dir" ]; then
            rm -rf "$NDK_DIR" 2>/dev/null
            mv "$extracted_ndk_dir" "$NDK_DIR"
            rm -rf "$SDK_DIR/ndk/tmp_ndk"
            ok "NDK restored successfully."
        else
            warn "Failed to find a valid NDK directory inside the archive."
        fi
    fi

    rm -rf "$TEMP_RESTORE"
    fix_ndk_permissions
    patch_new_packages_rpath
    ensure_wrapper_template
    ok "Environment restoration complete."
}

export_backup() {
    echo -e "\n[ Exporting Build Environment ]\n"
    local STAGE="$HOME/.backup-temp"
    rm -rf "$STAGE"; mkdir -p "$STAGE/pkg-cache"

    info "Archiving SDK platforms & NDK..."
    rsync -a "$SDK_DIR/" "$STAGE/android-sdk/"

    info "Archiving Gradle cache..."
    [ -d "$HOME/.gradle" ] && rsync -a "$HOME/.gradle/" "$STAGE/.gradle/"
    [ -d "$WRAPPER_DIR" ] && rsync -a "$WRAPPER_DIR/" "$STAGE/wrapper-template/"

    info "Caching system packages (.deb)..."
    [ -d "$SDK_DIR/pkg-cache" ] && cp "$SDK_DIR/pkg-cache/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true
    [ -d "$PREFIX/var/cache/apt/archives" ] && cp "$PREFIX/var/cache/apt/archives/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true

    local BACKUP_DIR="/sdcard/TermuX_Backups"
    mkdir -p "$BACKUP_DIR"
    local ZIPNAME="$BACKUP_DIR/builder-backup-complete-$(date +%Y%m%d).zip"
    info "Compressing archive to: $ZIPNAME ..."
    (cd "$STAGE" && zip -q -r "$ZIPNAME" .)
    rm -rf "$STAGE"
    ok "Exported successfully: $ZIPNAME"
}

setup_dummy_build_tools() {
    local bt_ver="$1"
    local bt_dir="$SDK_DIR/build-tools/$bt_ver"
    mkdir -p "$bt_dir/lib" "$bt_dir/renderscript/include" "$bt_dir/renderscript/clang-include"
    for tool in aapt aapt2 d8 zipalign apksigner; do
        [ -f "$PREFIX/bin/$tool" ] && ln -sf "$PREFIX/bin/$tool" "$bt_dir/$tool"
    done
    ln -sf "$PREFIX/bin/d8" "$bt_dir/dx" 2>/dev/null || true
    local dummy_execs=("dexdump" "split-select" "mainDexClasses" "mainDexClasses.bat" "llvm-rs-cc" "bcc_compat" "lld" "arm-linux-androideabi-ld" "i686-linux-android-ld" "mipsel-linux-android-ld" "aarch64-linux-android-ld" "x86_64-linux-android-ld")
    for dummy_exec in "${dummy_execs[@]}"; do
        if [ ! -f "$bt_dir/$dummy_exec" ]; then
            echo -e "#!/bin/sh\nexit 0" > "$bt_dir/$dummy_exec"
            chmod +x "$bt_dir/$dummy_exec"
        fi
    done
    local empty_zip_base64="UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA=="
    local dummy_jars=("core-lambda-stubs.jar" "mainDexClasses.rules" "lib/apksigner.jar" "lib/d8.jar" "lib/dx.jar" "lib/aapt2.jar" "lib/shrinkscript.jar")
    for jarfile in "${dummy_jars[@]}"; do
        if [ ! -s "$bt_dir/$jarfile" ]; then
            echo "$empty_zip_base64" | base64 -d > "$bt_dir/$jarfile" 2>/dev/null || touch "$bt_dir/$jarfile"
        fi
    done
    echo -e "Pkg.PluginsSource=Android SDK\nPkg.Revision=$bt_ver" > "$bt_dir/source.properties"
}

setup_dummy_cmake() {
    local cmake_ver="${1:-3.22.1}"
    local cmake_dir="$SDK_DIR/cmake/$cmake_ver"
    mkdir -p "$cmake_dir/bin"
    [ -f "$PREFIX/bin/cmake" ] && ln -sf "$PREFIX/bin/cmake" "$cmake_dir/bin/cmake"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja-build"
    echo -e "Pkg.PluginsSource=Android SDK\nPkg.Revision=$cmake_ver\nPkg.Path=cmake;$cmake_ver" > "$cmake_dir/source.properties"
}

clean_toolchains_python() {
    local target_file="$1"
    python3 - "$target_file" <<'PYEOF'
import re, sys
path = sys.argv[1]
try:
    with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()
    src = re.sub(r'JavaVersion\.VERSION_2[0-9]', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_19', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_18', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'sourceCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'sourceCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'targetCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'targetCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'jvmTarget\s*=\s*[\'"]2[0-9][\'"]', 'jvmTarget = "17"', src)
    while True:
        match = re.search(r'(?i)(\bjavaCompiler\s*=\s*javaToolchains[^{]*\{)', src)
        if not match:
            match = re.search(r'(?i)(\bjavaCompiler\s*=\s*[^\n]+)', src)
            if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[end:]
        else:
            line_end = src.find('\n', start)
            if line_end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[line_end:]
            else: break
    src = re.sub(r'(?i)(\bjvmToolchain\s*\([^)]*\))', r'/* \1 */', src)
    src = re.sub(r'(?i)(\bjvmToolchain\s*=.*)', r'/* \1 */', src)
    while True:
        match = re.search(r'(?i)(\btoolchain\s*\{)', src)
        if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end != -1: src = src[:start] + '/* toolchain disabled */' + src[end:]
        else: break
    with open(path, 'w', encoding='utf-8') as f: f.write(src)
except Exception: pass
PYEOF
}

inject_sdk_and_ndk() {
    local gradle_file="$1" sdk_ver="$2" ndk_ver="$3"
    python3 - "$gradle_file" "$sdk_ver" "$ndk_ver" <<'PYEOF'
import re, sys
path, sdk_ver, ndk_ver = sys.argv[1], sys.argv[2], sys.argv[3]
is_kts = path.endswith('.kts')
with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()
if is_kts:
    if re.search(r'compileSdk\s*=', src): src = re.sub(r'compileSdk\s*=\s*[0-9]+', f'compileSdk = {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\ncompileSdk = ' + sdk_ver, src, count=1)
    if re.search(r'ndkVersion\s*=', src): src = re.sub(r'ndkVersion\s*=\s*[\'"][^\'"]+[\'"]', f'ndkVersion = "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\nndkVersion = "' + ndk_ver + '"', src, count=1)
else:
    if re.search(r'compileSdk\s+[0-9]+', src): src = re.sub(r'compileSdk\s+[0-9]+', f'compileSdk {sdk_ver}', src)
    elif re.search(r'compileSdkVersion\s+[0-9]+', src): src = re.sub(r'compileSdkVersion\s+[0-9]+', f'compileSdkVersion {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\ncompileSdk ' + sdk_ver, src, count=1)
    if re.search(r'ndkVersion\s+[\'"]', src): src = re.sub(r'ndkVersion\s+[\'"][^\'"]+[\'"]', f'ndkVersion "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\nndkVersion "' + ndk_ver + '"', src, count=1)
with open(path, 'w', encoding='utf-8') as f: f.write(src)
PYEOF
}

build_project_direct() {
    local build_type="${1:-debug}"
    local src_path="$2"

    acquire_lock "$build_type" || return 1

    echo "=== Build Started: $(date) ===" > "$TRACE_LOG"
    rm -f "$RESULT_JSON"

    [ ! -d "$src_path" ] && { err "Project directory not found: $src_path"; write_result_json false "$build_type" 0 "" "Path not found"; release_lock; return 1; }

    local project_name=$(basename "$src_path")
    local target_dir="$WORKSPACE/$project_name"
    mkdir -p "$target_dir"

    info "Synchronizing workspace..."
    rsync -a --delete \
      --exclude='build/' \
      --exclude='app/build/' \
      --exclude='.gradle/' \
      --exclude='.cxx/' \
      --exclude='.idea/' \
      "$src_path/" "$target_dir/"

    cd "$target_dir" || { release_lock; return; }
    detect_device_hardware

    [ ! -d "$SDK_DIR" ] && auto_setup

    COMPILE_SDK=$(grep -rohE "(compileSdk|compileSdkVersion)\s*=?\s*[0-9]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9]+')
    [ -z "$COMPILE_SDK" ] && COMPILE_SDK=34

    download_platform_sdk "$COMPILE_SDK"

    BT_VER=$(grep -rohE "buildToolsVersion\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    [ -z "$BT_VER" ] && BT_VER="34.0.0"

    CMAKE_VER=$(grep -rohE "version\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    [ -z "$CMAKE_VER" ] && CMAKE_VER="3.22.1"

    setup_dummy_build_tools "$BT_VER"
    setup_dummy_build_tools "33.0.1"
    setup_dummy_cmake "$CMAKE_VER"

    mkdir -p gradle/wrapper
    cp "$WRAPPER_DIR/gradlew" . 2>/dev/null || true
    cp -r "$WRAPPER_DIR/gradle/"* gradle/ 2>/dev/null || true

    sed -i 's/\r$//' gradlew 2>/dev/null || true
    sed -i 's/DEFAULT_JVM_OPTS=.*/DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"/' gradlew 2>/dev/null || true
    chmod +x gradlew 2>/dev/null || true

    while IFS= read -r f_gradle; do clean_toolchains_python "$f_gradle"; done < <(find . -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "settings.gradle*" \) ! -path "*/build/*" 2>/dev/null)

    local installed_ndk=$(basename "$NDK_DIR" 2>/dev/null || echo "25.2.9519653")
    while IFS= read -r g_file; do inject_sdk_and_ndk "$g_file" "$COMPILE_SDK" "$installed_ndk"; done < <(find . -maxdepth 3 \( -name "build.gradle" -o -name "build.gradle.kts" \) ! -path "*/build/*" 2>/dev/null)

    cat > local.properties << EOF
sdk.dir=$SDK_DIR
cmake.dir=$SDK_DIR/cmake/$CMAKE_VER
EOF

    cat > gradle.properties << EOF
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$JAVA_HOME
org.gradle.native=false
systemProp.org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
org.gradle.caching=true
org.gradle.daemon.performance.disable-logging=true
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
org.gradle.workers.max=$DYNAMIC_WORKERS
org.gradle.parallel=false
org.gradle.jvmargs=$GRADLE_OPTS
EOF

    info "Starting Gradle Compilation ($build_type)..."
    local start_time=$SECONDS

    if [ "$build_type" = "release" ]; then
        ./gradlew assembleRelease --no-daemon --console=plain 2>&1 | tee -a "$TRACE_LOG" | live_animated_streamer "$TRACE_LOG"
    else
        ./gradlew assembleDebug --no-daemon --console=plain 2>&1 | tee -a "$TRACE_LOG" | live_animated_streamer "$TRACE_LOG"
    fi

    local build_status=${PIPESTATUS[0]}
    local elapsed=$(( SECONDS - start_time ))

    if [ $build_status -eq 0 ]; then
        local apk_file=$(find "$target_dir" -type f -name "*.apk" ! -name "*-unsigned.apk" 2>/dev/null | head -n1)
        if [ -n "$apk_file" ]; then

            local final_out_dir="$src_path/app/build/outputs/apk/$build_type"
            mkdir -p "$final_out_dir" 2>/dev/null || true
            local out_target="$final_out_dir/${project_name}-${build_type}.apk"

            cp "$apk_file" "$out_target" 2>/dev/null || true

            echo ""
            ok "BUILD SUCCESSFUL (${elapsed}s)"
            ok "APK Path: $out_target"
            write_result_json true "$build_type" "$elapsed" "$out_target" ""
        else
            err "APK not found after successful build."
            write_result_json false "$build_type" "$elapsed" "" "APK file not generated"
        fi
    else
        err "BUILD FAILED (Exit: $build_status)"
        local err_msg=$(grep -E -i -A 1 "error:|what went wrong" "$TRACE_LOG" | head -n 2 | tr '\n' ' ')
        write_result_json false "$build_type" "$elapsed" "" "$err_msg"
    fi

    release_lock
}

build_native_project_direct() {
    local src_path="$1"

    acquire_lock "native" || return 1

    echo "=== Native Build Started: $(date) ===" > "$TRACE_LOG"
    rm -f "$RESULT_JSON"

    [ ! -d "$src_path" ] && { err "Project directory not found: $src_path"; write_result_json false "native" 0 "" "Path not found"; release_lock; return 1; }

    local project_name=$(basename "$src_path")
    local target_dir="$WORKSPACE/Native_$project_name"
    mkdir -p "$target_dir"

    info "Synchronizing workspace..."
    rsync -a --delete \
      --exclude='build/' \
      --exclude='build_native/' \
      --exclude='libs/' \
      --exclude='obj/' \
      --exclude='.cxx/' \
      "$src_path/" "$target_dir/"

    cd "$target_dir" || { release_lock; return; }
    detect_device_hardware
    fix_ndk_permissions

    info "Starting Native Compilation..."
    local start_time=$SECONDS
    local BUILD_STATUS=1

    if [ -f "CMakeLists.txt" ]; then
        info "Build System Detected: CMake + Ninja"
        mkdir -p build_native && cd build_native

        cmake -G Ninja \
          -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="arm64-v8a" \
          -DANDROID_PLATFORM="android-24" \
          -DCMAKE_BUILD_TYPE="Release" \
          .. 2>&1 | tee -a "$TRACE_LOG" | live_animated_streamer "$TRACE_LOG"

        ninja -j"$DYNAMIC_NINJA_JOBS" 2>&1 | tee -a "$TRACE_LOG" | live_animated_streamer "$TRACE_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
        cd "$target_dir" || { release_lock; return; }
    elif [ -f "Android.mk" ] || [ -f "jni/Android.mk" ]; then
        info "Build System Detected: NDK-Build (Android.mk)"

        local ndk_args="NDK_PROJECT_PATH=. NDK_OUT=build NDK_LIBS_OUT=libs"

        if [ -f "Android.mk" ] && [ ! -f "jni/Android.mk" ]; then
            ndk_args="$ndk_args APP_BUILD_SCRIPT=./Android.mk"
            [ -f "Application.mk" ] && ndk_args="$ndk_args NDK_APPLICATION_MK=./Application.mk"
        fi

        "$NDK_DIR/ndk-build" $ndk_args -j"$DYNAMIC_NINJA_JOBS" 2>&1 | tee -a "$TRACE_LOG" | live_animated_streamer "$TRACE_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
    else
        err "No CMakeLists.txt or Android.mk found."
        write_result_json false "native" 0 "" "No build system found"
        release_lock
        return 1
    fi

    local elapsed=$(( SECONDS - start_time ))

    if [ $BUILD_STATUS -eq 0 ]; then
        local out_dir="$src_path/build/outputs/native"
        mkdir -p "$out_dir" 2>/dev/null || true

        find "$target_dir" -type f \( -name "*.so" -o -perm -111 \) ! -name "*.sh" ! -name "*.py" -exec cp {} "$out_dir/" \; 2>/dev/null || true

        echo ""
        ok "NATIVE BUILD SUCCESSFUL (${elapsed}s)"
        ok "Output: $out_dir"
        write_result_json true "native" "$elapsed" "$out_dir" ""
    else
        err "NATIVE BUILD FAILED (Exit: $BUILD_STATUS)"
        local err_msg=$(grep -E -i -A 1 "error:" "$TRACE_LOG" | head -n 2 | tr '\n' ' ')
        write_result_json false "native" "$elapsed" "" "$err_msg"
    fi

    release_lock
}

case "$1" in
    build) build_project_direct "$2" "$3" ;;
    native) build_native_project_direct "$2" ;;
    setup) auto_setup ;;
    restore) import_backup "$2" ;;
    export) export_backup ;;
    *) echo "Usage: termux-build [build|native|setup|restore|export]" ;;
esac