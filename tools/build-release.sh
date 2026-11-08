#!/usr/bin/env bash
# Snapshot completed sources, then build ksuinit -> ksud -> Manager -> signed repack.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
stage="${1:-help}"
milksu_log "release-$stage"
milksu_space
case "$stage" in
    ksuinit|ksud|manager|manager-tests|manager-lint|manager-assemble|repack) milksu_heavy_lock ;;
esac
work="${MILKSU_RELEASE_SOURCE:-$MILKSU_CACHE/build/release/source}"
abis="${MILKSU_ABIS:-arm64-v8a x86_64}"
mkdir -p "$MILKSU_CACHE/build"
lock_name="$(printf '%s' "$work" | sha256sum | cut -c1-16)"
exec {release_lock}>"$MILKSU_CACHE/build/release-$lock_name.lock"
flock "$release_lock"
case "$stage" in
    snapshot)
        case "${2:-}" in
            --source-ready) input_state=source-ready ;;
            --preview) input_state=preview ;;
            *) milksu_die "Use snapshot --source-ready, or snapshot --preview for provisional compiler feedback." ;;
        esac
        mkdir -p "$(dirname "$work")"
        if [[ ! -d "$work/.git" ]]; then
            milksu_run git clone --shared --no-hardlinks "$MILKSU_ROOT" "$work"
        fi
        milksu_run rsync -a --delete --exclude=.git --exclude=out --exclude=target \
            --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.cxx \
            --exclude=node_modules --exclude=local.properties --exclude=dist \
            "$MILKSU_ROOT/" "$work/"
        mkdir -p "$MILKSU_OUT/manifests"
        git -C "$MILKSU_ROOT" rev-parse HEAD >"$MILKSU_OUT/manifests/source-head.txt"
        git -C "$MILKSU_ROOT" diff --binary HEAD >"$MILKSU_OUT/manifests/source-tracked.patch"
        git -C "$MILKSU_ROOT" status --short >"$MILKSU_OUT/manifests/source-status.txt"
        printf '%s\n' "$input_state" >"$work/.milksu-input-state"
        printf '%s\n' "$input_state" >"$MILKSU_OUT/manifests/source-state.txt"
        printf 'Release snapshot: %s\n' "$work"
        ;;
    manager-sync)
        [[ -d "$work/.git" ]] || milksu_die "Release snapshot is missing."
        milksu_run rsync -a --delete --exclude=build --exclude=.gradle --exclude=.kotlin \
            --exclude=.cxx --exclude=local.properties --exclude=libksud.so \
            "$MILKSU_ROOT/manager/" "$work/manager/"
        printf 'Manager preview sources refreshed; real daemon retained.\n'
        ;;
    ksuinit|ksud)
        [[ -d "$work/.git" ]] || milksu_die "Release snapshot is missing."
        cd "$work"
        export KSU_PACKAGE_NAME="$MILKSU_PACKAGE_NAME"
        export LIBCLANG_PATH="${LIBCLANG_PATH:-$(llvm-config --libdir)}"
        for abi in $abis; do
            case "$abi" in
                arm64-v8a) triple=aarch64-linux-android; arch=aarch64 ;;
                x86_64) triple=x86_64-linux-android; arch=x86_64 ;;
                *) milksu_die "ABI $abi needs its own pinned build-std toolchain; use arm64-v8a or x86_64." ;;
            esac
            # shellcheck source=/dev/null
            source <(sed 's/\r$//' "$work/.github/scripts/setup-rust-build.sh") "$triple" "$MILKSU_ANDROID_API"
            if [[ "$stage" == ksuinit ]]; then
                builtins="$("$CLANG_PATH" --print-resource-dir)/lib/linux/libclang_rt.builtins-$arch-android.a"
                [[ -f "$builtins" ]] || milksu_die "Missing compiler runtime: $builtins"
                export RUSTFLAGS="-C target-feature=+crt-static -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wno-unused-command-line-argument -C link-arg=$builtins"
                milksu_run cargo build --locked --package ksuinit --target "$triple" --release
                unset RUSTFLAGS
                mkdir -p "$work/userspace/ksud/bin/$arch" "$MILKSU_OUT/ksuinit/$abi"
                cp "$CARGO_TARGET_DIR/$triple/release/ksuinit" "$work/userspace/ksud/bin/$arch/ksuinit"
                cp "$CARGO_TARGET_DIR/$triple/release/ksuinit" "$MILKSU_OUT/ksuinit/$abi/ksuinit"
            else
                [[ -s "$MILKSU_OUT/ksuinit/$abi/ksuinit" ]] ||
                    milksu_die "Build ksuinit before ksud."
                cp "$MILKSU_OUT/ksuinit/$abi/ksuinit" "$work/userspace/ksud/bin/$arch/ksuinit"
                if [[ "$abi" == arm64-v8a ]]; then
                    for asset in kpimg kptools; do
                        [[ -s "$MILKSU_OUT/kpatch/aarch64/$asset" ]] || milksu_die "Missing source-built $asset."
                        cp "$MILKSU_OUT/kpatch/aarch64/$asset" "$work/userspace/ksud/bin/aarch64/$asset"
                    done
                fi
                module_dir="$MILKSU_OUT/$arch"
                for module in "$module_dir/"*_kernelsu.ko "$module_dir/"*_susfs_guard_lkm.ko; do
                    [[ -f "$module" ]] || continue
                    cp "$module" "$work/userspace/ksud/bin/$arch/"
                done
                if [[ "$abi" == arm64-v8a ]]; then
                    [[ -s "$work/userspace/ksud/bin/$arch/android14-6.1_kernelsu.ko" ]] ||
                        milksu_die "The priority android14-6.1 root LKM is missing."
                    [[ -s "$work/userspace/ksud/bin/$arch/android14-6.1_susfs_guard_lkm.ko" ]] ||
                        milksu_die "The priority android14-6.1 SUSFS LKM is missing."
                    if [[ "${MILKSU_REQUIRE_FULL_MATRIX:-1}" == 1 ]]; then
                        milksu_run python3 "$MILKSU_ROOT/tools/build-verify-modules.py"
                    else
                        milksu_run python3 "$MILKSU_ROOT/tools/build-verify-modules.py" \
                            --kmi android14-6.1 --kmi android12-5.10
                    fi
                fi
                python3 - "$work/userspace/ksud/bin/$arch" "$MILKSU_OUT/manifests/embedded-assets-$arch.json" <<'PY'
import hashlib, json, pathlib, sys
directory, output = map(pathlib.Path, sys.argv[1:])
assets = []
for path in sorted(directory.iterdir()):
    if path.is_file() and (path.suffix == ".ko" or path.name in ("ksuinit", "kpimg", "kptools")):
        data = path.read_bytes()
        assets.append({"name": path.name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(assets, indent=2) + "\n")
PY
                # rust-embed compression caches directory assets; force a new derive expansion.
                milksu_run cargo clean --package ksud --target "$triple" --release
                milksu_run cargo build --locked --package ksud --target "$triple" --release
                mkdir -p "$work/manager/app/src/main/jniLibs/$abi" "$MILKSU_OUT/ksud/$abi"
                cp "$CARGO_TARGET_DIR/$triple/release/ksud" "$work/manager/app/src/main/jniLibs/$abi/libksud.so"
                cp "$CARGO_TARGET_DIR/$triple/release/ksud" "$MILKSU_OUT/ksud/$abi/ksud"
            fi
        done
        ;;
    manager|manager-tests|manager-lint|manager-assemble)
        export GRADLE_USER_HOME="${MILKSU_MANAGER_GRADLE_HOME:-$MILKSU_CACHE/gradle-build}"
        [[ -d "$work/.git" ]] || milksu_die "Release snapshot is missing."
        for abi in $abis; do
            [[ -s "$work/manager/app/src/main/jniLibs/$abi/libksud.so" ]] ||
                milksu_die "Build ksud for $abi before Manager."
        done
        [[ -f "$MILKSU_OUT/private/certificate.json" ]] || bash "$MILKSU_ROOT/tools/build-signing.sh"
        signing_init="$MILKSU_OUT/private/gradle-signing.init.gradle"
        python3 "$MILKSU_ROOT/tools/signing_config.py" \
            --metadata "$MILKSU_OUT/private/certificate.json" gradle-init --output "$signing_init"
        cd "$work/manager"
        sed -i 's/\r$//' gradlew
        milksu_run bash "$MILKSU_ROOT/tools/bootstrap-gradle.sh"
        mkdir -p "$GRADLE_USER_HOME"
        gradle_arguments=(
            --no-daemon --console=plain --max-workers=1
            -Pandroid.r8.maxWorkers=1 -Dorg.gradle.parallel=false
            '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=512m'
            -Pkotlin.compiler.execution.strategy=in-process
            -Pandroid.injected.testOnly=false
            --init-script "$signing_init"
            "-PKSU_PACKAGE_NAME=$MILKSU_PACKAGE_NAME" -PKSU_NAME=xiaoyuSU
            "-PMILKSU_ABIS=$abis"
        )
        case "$stage" in
            manager-tests) manager_tasks=(:app:testReleaseUnitTest) ;;
            manager-lint) manager_tasks=(:app:lintRelease) ;;
            manager-assemble) manager_tasks=(:app:assembleRelease) ;;
            manager) manager_tasks=(:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease) ;;
        esac
        for manager_task in "${manager_tasks[@]}"; do
            milksu_run bash ./gradlew "${gradle_arguments[@]}" "$manager_task"
        done
        if [[ "$stage" == manager || "$stage" == manager-assemble ]]; then
            mkdir -p "$MILKSU_OUT/manager"
            # AGP 9.4 keeps the packaged APK in its artifact directory.
            if [[ -d app/build/intermediates/apk/release ]]; then
                mkdir -p app/build/outputs/apk/release
                find app/build/intermediates/apk/release -maxdepth 1 -name '*.apk' -type f \
                    -exec cp '{}' app/build/outputs/apk/release/ \;
            fi
            find app/build/outputs/apk/release -name '*.apk' -type f -exec cp '{}' "$MILKSU_OUT/manager/" \;
        fi
        ;;
    repack)
        [[ -d "$work/.git" ]] || milksu_die "Release snapshot is missing."
        [[ -f "$work/.milksu-input-state" && "$(cat "$work/.milksu-input-state")" == source-ready ]] ||
            milksu_die "Final repack requires a source-ready snapshot."
        # Repack in a temporary view with the existing helper's expected target layout.
        ln -sfn "$CARGO_TARGET_DIR" "$work/target"
        config="$MILKSU_OUT/private/repack-config.json"
        python3 "$MILKSU_ROOT/tools/signing_config.py" \
            --metadata "$MILKSU_OUT/private/certificate.json" repack-config --output "$config" --abis "$abis"
        apk_name="$(python3 "$MILKSU_ROOT/tools/signing_config.py" \
            --metadata "$MILKSU_OUT/private/certificate.json" apk-name)"
        milksu_run python3 "$MILKSU_ROOT/tools/build-repack.py" "$work" repack --config "$config" --out-dir "$MILKSU_OUT/manager"
        milksu_run apksigner verify --verbose --print-certs "$MILKSU_OUT/manager/$apk_name"
        milksu_run zipalign -c -P 16 4 "$MILKSU_OUT/manager/$apk_name"
        python3 "$MILKSU_ROOT/tools/build-verify.py" "$MILKSU_OUT/manager/$apk_name"
        python3 - "$work/version.properties" "$MILKSU_OUT/manager" "$apk_name" <<'PY'
import pathlib, shutil, sys
version_file, directory = map(pathlib.Path, sys.argv[1:3])
version = dict(line.split("=", 1) for line in version_file.read_text().splitlines() if "=" in line)
name = version["MILKSU_VERSION_NAME"]
code = version.get("MILKSU_MANAGER_VERSION_CODE", version["MILKSU_VERSION_CODE"])
target = directory / f"xiaoyuSU_{name}_{code}.apk"
shutil.copy2(directory / sys.argv[3], target)
print(f"Stable release APK: {target}")
PY
        ;;
    *) milksu_die "Usage: $0 {snapshot --source-ready|snapshot --preview|manager-sync|ksuinit|ksud|manager|manager-tests|manager-lint|manager-assemble|repack}. Default ABIs: arm64-v8a x86_64." ;;
esac
milksu_space
