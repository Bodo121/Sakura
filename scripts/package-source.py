from pathlib import Path
import subprocess,zipfile,hashlib,stat
root=Path(__file__).resolve().parents[1];out=root/'dist';out.mkdir(exist_ok=True)
files=subprocess.check_output(['git','ls-files'],cwd=root,text=True).splitlines()
# Explicit excludes; signing keys and generated outputs must never be included.
with zipfile.ZipFile(out/'SakuraOS-Source-v0.1.0.zip','w',zipfile.ZIP_DEFLATED) as z:
 for f in sorted(files):
  if f.startswith(('.local/','dist/','target/')) or f.endswith(('.jks','.keystore')):continue
  path=root/f
  info=zipfile.ZipInfo('Sakura/'+f,(2026,11,17,0,0,0));info.create_system=3;info.external_attr=(stat.S_IFREG|(0o755 if path.stat().st_mode&0o111 else 0o644))<<16
  z.writestr(info,path.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
items=sorted(out.glob('SakuraOS-*.zip'))+sorted((out/'apps').glob('*.apk'))
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.relative_to(out).as_posix()+'\n' for f in items))
print('\n'.join(str(f) for f in items))
