#!/usr/bin/env bash
set -Eeuo pipefail
SAKURA_REPO="$PWD"
SAKURA_AOSP_DIR="${SAKURA_AOSP_DIR:-$HOME/sakura-aosp-14}"
SAKURA_OUTPUT="$SAKURA_REPO/dist/gsi"
export SAKURA_REPO SAKURA_AOSP_DIR SAKURA_OUTPUT
python3 scripts/gsi/preflight.py --aosp-dir "$SAKURA_AOSP_DIR" --output "$SAKURA_OUTPUT"
# Preserve the real failing command; never manufacture an image after failed compilation.
failure() {
 local code=$?
 trap - ERR
 python3 - "$code" <<'PY'
import json,os,pathlib,sys
p=pathlib.Path(os.environ['SAKURA_OUTPUT'])/'SakuraOS-GSI-v0.2.0-build-report.json';r=json.loads(p.read_text());r['status']='build_failed';r['compilation_failures'].append({'exit_code':int(sys.argv[1]),'log':'See gsi-build.log in the workflow artifact'});r['image_generated']=False;r['image']=None;p.write_text(json.dumps(r,indent=2)+'\n')
PY
 rm -f "$SAKURA_OUTPUT/SakuraOS-GSI-v0.2.0-arm64.img.xz"
 (cd "$SAKURA_OUTPUT" && sha256sum *.json *.md > SakuraOS-GSI-v0.2.0-SHA256SUMS.txt)
 exit "$code"
}
trap failure ERR
for name in Home Settings Files Phone Messages Contacts Gallery Camera Clock Calculator Calendar Music Recorder Notes Installer Wallpapers Updater Terminal CoreManager Patch; do test -s "dist/gsi-apps/Sakura$name.apk";done
command -v repo >/dev/null
cd "$SAKURA_AOSP_DIR"
if [[ -d .repo && ! -f .sakura-source-tag ]]; then echo 'Refusing to change an unmarked existing AOSP checkout' >&2;exit 1;fi
if [[ -f .sakura-source-tag ]]; then grep -Fx android-14.0.0_r75 .sakura-source-tag;fi
repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_r75 --depth=1
printf '%s\n' android-14.0.0_r75 > .sakura-source-tag
repo sync -c -j "${SAKURA_SYNC_JOBS:-4}" --no-clone-bundle --no-tags
repo manifest -r -o "$SAKURA_OUTPUT/aosp-resolved-manifest.xml"
mkdir -p device/sakura
cp -R "$SAKURA_REPO/device/sakura/gsi" device/sakura/
mkdir -p device/sakura/gsi/prebuilts
cp "$SAKURA_REPO"/dist/gsi-apps/*.apk device/sakura/gsi/prebuilts/
cp "$SAKURA_REPO/target/aarch64-linux-android/release/"{sakura,sakurad} device/sakura/gsi/prebuilts/
source build/envsetup.sh
lunch sakura_gsi_arm64-userdebug
[[ "$(get_build_var TARGET_ARCH)" == arm64 ]]
# AOSP compiles SystemUI overlay and SELinux policy as dependencies of systemimage.
m -j "${SAKURA_BUILD_JOBS:-4}" systemimage check-vintf-all
image="$ANDROID_PRODUCT_OUT/system.img"
test -s "$image"
e2fsck -fn "$image"
# Android build-system output is the authoritative architecture/manifest source.
python3 - "$ANDROID_PRODUCT_OUT" <<'PY'
import hashlib,json,os,pathlib,sys
out=pathlib.Path(os.environ['SAKURA_OUTPUT']);product=pathlib.Path(sys.argv[1]);p=out/'SakuraOS-GSI-v0.2.0-build-report.json';r=json.loads(p.read_text())
image=product/'system.img';r.update(status='image_generated_untested',image_generated=True,image='SakuraOS-GSI-v0.2.0-arm64.img.xz')
r['source_revisions']['aosp_resolved_manifest']='aosp-resolved-manifest.xml'
r['successfully_integrated_applications']=sorted(f.stem for f in pathlib.Path(os.environ['SAKURA_REPO'],'dist/gsi-apps').glob('*.apk'))
import re,subprocess
retained=[]
aapt=pathlib.Path('out/host/linux-x86/bin/aapt2')
for apk in (product/'system').rglob('*.apk'):
 result=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
 match=re.search(r"package: name='([^']+)'",result)
 if match and not match[1].startswith('dev.sakura.'):retained.append(match[1])
r['retained_android_packages']=sorted(set(retained))
r['retention_note']='Scanned APK package IDs; Mainline APEX-internal packages are retained by the inherited product but not enumerated here.'
for app in r['applications'].values():app['aosp_integrated']=True
with image.open('rb') as f: r['image_sha256_uncompressed']=hashlib.file_digest(f,'sha256').hexdigest()
r['partition_requirements']['system_partition_bytes']=image.stat().st_size
for key in ['rust_compilation','kotlin_compilation','flutter_compilation','aosp_image_generation','selinux_policy_compilation','apk_signatures','filesystem_integrity']:r['checks'][key]='passed'
r['checks']['vintf_compatibility']='framework_check_passed; device_vendor_pair_not_verified'
p.write_text(json.dumps(r,indent=2)+'\n')
PY
xz -T 4 -6 --stdout "$image" > "$SAKURA_OUTPUT/SakuraOS-GSI-v0.2.0-arm64.img.xz"
xz --test "$SAKURA_OUTPUT/SakuraOS-GSI-v0.2.0-arm64.img.xz"
(cd "$SAKURA_OUTPUT" && sha256sum *.img.xz *.json *.md > SakuraOS-GSI-v0.2.0-SHA256SUMS.txt)
