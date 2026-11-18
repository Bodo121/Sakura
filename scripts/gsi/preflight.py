#!/usr/bin/env python3
"""Resource gate and truthful report, including failed image attempts."""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess
p=argparse.ArgumentParser();p.add_argument('--output',default='dist/gsi');p.add_argument('--aosp-dir',default=os.environ.get('SAKURA_AOSP_DIR',str(pathlib.Path.home()/'sakura-aosp-14')));args=p.parse_args()
out=pathlib.Path(args.output);out.mkdir(parents=True,exist_ok=True)
# Never leave a prior image beside a preflight report for a new build attempt.
(out/'SakuraOS-GSI-v0.2.0-arm64.img.xz').unlink(missing_ok=True)
source=pathlib.Path(args.aosp_dir);source.mkdir(parents=True,exist_ok=True)
free=shutil.disk_usage(source).free
memory=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemTotal:')))*1024
minimum_disk=(150 if (source/'.repo/manifest.xml').exists() else 400)*10**9
blocked=[]
if free<minimum_disk:blocked.append(f'AOSP disk capacity: {free//1024**3} GiB free; need {minimum_disk//10**9} GB for this checkout state')
low_memory = os.environ.get('SAKURA_LOW_MEMORY') == '1'
warnings = []
if memory < 60 * 1024**3:
 if low_memory:
  warnings.append('Low-memory mode enabled. AOSP build concurrency is limited to one job; the build may still run out of memory.')
 else:
  blocked.append(f'AOSP memory: {memory/1024**3:.1f} GiB; need 60 GiB or SAKURA_LOW_MEMORY=1 for a single-job build')
revision=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
coverage=json.loads(pathlib.Path('docs/gsi-application-status.json').read_text())
report={'version':'0.2.0','status':'blocked' if blocked else 'preflight_passed','warning':'UNTESTED DEVELOPER BUILD — FIRST BOOT NOT VERIFIED','image_generated':False,'image':None,'first_boot_verified':False,'physical_device_tested':False,'source_revisions':{'sakura':revision,'aosp_tag':'android-14.0.0_r75','aosp_resolved_manifest':None,'liquidglass':'fc6bd34fa3290638c3594cc39386f549d1316ab5'},'environment':{'toolchain_pins':{'flutter':'3.38.5','android_compile_sdk':36,'android_target_sdk':34,'ndk':'27.2.12479018','agp':'8.11.1','kotlin':'2.1.0','cmake':'3.22.1'},'os':platform.platform(),'architecture':platform.machine(),'cpu_count':os.cpu_count(),'memory_bytes':memory,'free_disk_bytes':free,'aosp_path':str(source.resolve()),'github_run_url':os.environ.get('SAKURA_RUN_URL')},'applications':coverage,'successfully_integrated_applications':[],'replaced_android_packages':[],'retained_android_packages':[],'retained_android_modules_policy':['Settings','Launcher3QuickStep','DocumentsUI','Dialer','Messaging','ContactsProvider','TeleService','SystemUI','PermissionController','PackageInstaller','MediaProvider','Camera2','DeskClock','CalendarProvider','WebView'],'image_architecture':'arm64','partition_requirements':{'layout':'system-as-root GSI with 64-bit Binder; dynamic partition fit must be inspected','system_partition_bytes':None,'vendor':'existing compatible Android 14 vendor and stock-compatible kernel; no vendor or boot image is generated'},'checks':{'rust_compilation':'not_run','kotlin_compilation':'not_run','flutter_compilation':'not_run','aosp_image_generation':'not_run','selinux_policy_compilation':'not_run','vintf_compatibility':'not_run_no_vendor_input','apk_signatures':'not_run','filesystem_integrity':'not_run'},'compilation_failures':[],'unresolved_integration_issues':blocked,'known_limitations':['The stock taoyao firmware layout was inspected separately. AOSP image compilation does not validate against the stock vendor image or the device’s active partition layout.','Twenty app package variants are not twenty completed replacements; see per-application capability status.','SystemUI overlay changes surface radii only; the requested full SystemUI redesign is not implemented.','Native daemon uses a private system-UID socket. The signature-protected Android app bridge is not implemented.','Complete OTA/system-framework updates, terminal PTY, default dialer/SMS roles and replacement-contract validation are incomplete; the patch UI is restricted to app-owned asset generations.','AOSP Android 14 r75 is pinned for reproducibility, not claimed current on security patches.','Without a protected persistent development signing identity, APK keys are generated per run; upgrade-compatible signing and bit-for-bit reproducibility are not established.','Fingerprint/FOD, camera HAL, IMS/VoLTE, modem, audio, NFC, Bluetooth, Wi-Fi, DRM, encryption and refresh-rate behavior on taoyao are unverified.']}
report['environment']['low_memory_mode'] = low_memory
report['environment']['resource_warnings'] = warnings
if os.environ.get('SAKURA_APPS_RESULT')=='failure':
 report['compilation_failures'].append({'job':'applications','status':'failed_before_all_compiler_checks_passed','log_url':os.environ.get('SAKURA_RUN_URL')})
compilation=pathlib.Path('dist/gsi-apps/compilation-report.json')
if compilation.exists():
 app_report=json.loads(compilation.read_text())
 if app_report.get('revision')==revision:
  for key in ['rust_compilation','kotlin_compilation','flutter_compilation','apk_signatures']:report['checks'][key]=app_report[key]
  for name,value in app_report['applications'].items():report['applications'][name].update(value)
  report['environment']['app_compiler_versions']={'rust':app_report['rust'],'flutter':app_report['flutter']}
(out/'SakuraOS-GSI-v0.2.0-build-report.json').write_text(json.dumps(report,indent=2)+'\n')
shutil.copyfile('docs/gsi-install-guide.md',out/'SakuraOS-GSI-v0.2.0-install-guide.md')
with (out/'SakuraOS-GSI-v0.2.0-SHA256SUMS.txt').open('w') as checksum:
 for file in sorted(out.iterdir()):
  if file.name.endswith(('.json','.md')):checksum.write(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+file.name+'\n')
print(json.dumps({'status':report['status'],'blockers':blocked,'warnings':warnings,'report':str(out/'SakuraOS-GSI-v0.2.0-build-report.json')},indent=2))
raise SystemExit(2 if blocked else 0)
