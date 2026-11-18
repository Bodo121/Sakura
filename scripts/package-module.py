from pathlib import Path
import zipfile,stat,struct
root=Path(__file__).resolve().parents[1]
out=root/'dist/SakuraOS-Developer-APatch-v0.1.0.zip'
entries={p.relative_to(root/'modules/sakura-apatch').as_posix():p for p in (root/'modules/sakura-apatch').rglob('*') if p.is_file()}
for name in ['sakura','sakurad']:
 p=root/'dist/arm64'/name;b=p.read_bytes()
 assert b[:4]==b'\x7fELF' and b[4]==2 and struct.unpack_from('<H',b,18)[0]==183,'Not ARM64 ELF'
 entries['bin/'+name]=p
out.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 for name,p in sorted(entries.items()):
  i=zipfile.ZipInfo(name,(2026,11,17,0,0,0));i.create_system=3;i.external_attr=(stat.S_IFREG|(0o755 if name.startswith('bin/') or name.endswith('.sh') else 0o644))<<16
  z.writestr(i,p.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(out) as z:
 assert {'module.prop','skip_mount','customize.sh','service.sh','uninstall.sh','bin/sakura','bin/sakurad'}<=set(z.namelist())
 assert all(not n.startswith('/') and '..' not in n.split('/') for n in z.namelist())
 assert all(z.getinfo(n).external_attr>>16&0o111 for n in ['bin/sakura','bin/sakurad','service.sh'])
print(out)
