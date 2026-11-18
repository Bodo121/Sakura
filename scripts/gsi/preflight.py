#!/usr/bin/env python3
"""Resource gate and truthful report, including failed image attempts."""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess
p=argparse.ArgumentParser();p.add_argument('--output',default='dist/gsi');p.add_argument('--aosp-dir',default='aosp');args=p.parse_args()
out=pathlib.Path(args.output);out.mkdir(parents=True,exist_ok=True)
source=pathlib.Path(args.aosp_dir);source.mkdir(parents=True,exist_ok=True)
free=shutil.disk_usage(source).free
memory=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemTotal:')))*1024
minimum_disk=(150 if (source/'.repo/manifest.xml').exists() else 400)*1024**3
blocked=[]
if free<minimum_disk:blocked.append(f'AOSP disk capacity: {free//1024**3} GiB free; need {minimum_disk//1024**3} GiB for this checkout state')
if memory<64*1024**3:blocked.append(f'AOSP memory: {memory/1024**3:.1f} GiB; configured minimum 64 GiB')
revision=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
coverage=json.loads(pathlib.Path('docs/gsi-application-status.json').read_text())
report={'version':'0.2.0','status':'blocked' if blocked else 'preflight_passed','warning':'UNTESTED DEVELOPER BUILD — FIRST BOOT NOT VERIFIED','image_generated':False,'image':None,'first_boot_verified':False,'physical_device_tested':False,'source_revisions':{'sakura':revision,'aosp_tag':'android-14.0.0_r75','aosp_resolved_manifest':None,'liquidglass':'fc6bd34fa3290638c3594cc39386f549d1316ab5'},'environment':{'os':platform.platform(),'architecture':platform.machine(),'cpu_count':os.cpu_count(),'memory_bytes':memory,'free_disk_bytes':free,'aosp_path':str(source.resolve()),'github_run_url':os.environ.get('SAKURA_RUN_URL')},'applications':coverage,'successfully_integrated_applications':[],'replaced_android_packages':[],'retained_android_packages':['Settings','Launcher3QuickStep','DocumentsUI','Dialer','Messaging','ContactsProvider','TeleService','SystemUI','PermissionController','PackageInstaller','MediaProvider','Camera2','DeskClock','CalendarProvider','WebView'],'image_architecture':'arm64','partition_requirements':{'layout':'system-as-root GSI with 64-bit Binder; dynamic partition fit must be inspected','system_partition_bytes':None,'vendor':'existing compatible Android 14 vendor and stock-compatible kernel; no vendor or boot image is generated'},'checks':{'rust_compilation':'not_run','kotlin_compilation':'not_run','flutter_compilation':'not_run','aosp_image_generation':'not_run','selinux_policy_compilation':'not_run','vintf_compatibility':'not_run_no_vendor_input','apk_signatures':'not_run','filesystem_integrity':'not_run'},'compilation_failures':[],'unresolved_integration_issues':blocked,'known_limitations':['No Windows drive or supplied stock ROM is accessible in this cloud workspace.','Twenty app package variants are not twenty completed replacements; see per-application capability status.','SystemUI overlay changes surface radii only; the requested full SystemUI redesign is not implemented.','Native daemon uses a private system-UID socket. The signature-protected Android app bridge is not implemented.','Framework update activation UI, complete OTA updates, terminal PTY, default dialer/SMS roles and app replacement validation are incomplete.','AOSP Android 14 r75 is pinned for reproducibility, not claimed current on security patches.','Fingerprint/FOD, camera HAL, IMS/VoLTE, modem, audio, NFC, Bluetooth, Wi-Fi, DRM, encryption and refresh-rate behavior on taoyao are unverified.']}
(out/'SakuraOS-GSI-v0.2.0-build-report.json').write_text(json.dumps(report,indent=2)+'\n')
shutil.copyfile('docs/gsi-install-guide.md',out/'SakuraOS-GSI-v0.2.0-install-guide.md')
with (out/'SakuraOS-GSI-v0.2.0-SHA256SUMS.txt').open('w') as checksum:
 for file in sorted(out.iterdir()):
  if file.name.endswith(('.json','.md')):checksum.write(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+file.name+'\n')
print(json.dumps({'status':report['status'],'blockers':blocked,'report':str(out/'SakuraOS-GSI-v0.2.0-build-report.json')},indent=2))
raise SystemExit(2 if blocked else 0)
