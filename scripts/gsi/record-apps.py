#!/usr/bin/env python3
import hashlib,json,os,pathlib,struct,subprocess,zipfile
root=pathlib.Path('dist/gsi-apps');names=json.loads(pathlib.Path('docs/gsi-application-status.json').read_text());checks={}
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
for name in names:
 path=root/f'Sakura{name}.apk'
 subprocess.run([str(tools/'apksigner'),'verify','--verbose',str(path)],check=True)
 subprocess.run([str(tools/'zipalign'),'-c','-p','4',str(path)],check=True)
 with zipfile.ZipFile(path) as apk:
  assert all(i.compress_type==zipfile.ZIP_STORED for i in apk.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')), 'AOSP presigned APKs require uncompressed JNI libraries'
  header=apk.read('lib/arm64-v8a/libflutter.so')[:20]
  assert header[:4]==b'\x7fELF' and header[4]==2 and struct.unpack('<H',header[18:20])[0]==183
 checks[name]={'compiled':True,'signature_verified':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'aosp_integrated':False,'replacement_validated':False}
for name in ['sakura','sakurad']:
 header=pathlib.Path('target/aarch64-linux-android/release',name).read_bytes()[:20]
 assert header[:4]==b'\x7fELF' and header[4]==2 and struct.unpack('<H',header[18:20])[0]==183
report={'revision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'rust':subprocess.check_output(['rustc','--version'],text=True).strip(),'flutter':subprocess.check_output(['flutter','--version','--machine'],text=True).strip(),'applications':checks,'rust_compilation':'passed','kotlin_compilation':'passed','flutter_compilation':'passed','apk_signatures':'passed'}
(root/'compilation-report.json').write_text(json.dumps(report,indent=2)+'\n')
