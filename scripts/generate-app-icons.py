#!/usr/bin/env python3
"""Original vector icons; Android adaptation of the Sakura design vocabulary."""
import json,pathlib
icons=json.loads(pathlib.Path('packages/sakura_design/android-icons/icons.json').read_text())
background='M18,0 L36,0 Q54,0 54,18 L54,36 Q54,54 36,54 L18,54 Q0,54 0,36 L0,18 Q0,0 18,0 Z'
for name,spec in icons.items():
 path=pathlib.Path(f'platform/flutter-suite/android/app/src/{name}/res/drawable/ic_launcher.xml');path.parent.mkdir(parents=True,exist_ok=True)
 path.write_text(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="54dp" android:height="54dp" android:viewportWidth="54" android:viewportHeight="54">
 <path android:fillColor="{spec['color']}" android:pathData="{background}"/>
 <path android:fillColor="#20FFFFFF" android:pathData="M0,18 Q0,0 18,0 L36,0 Q54,0 54,18 L54,22 C34,4 22,33 0,18 Z"/>
 <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="2.3" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{spec['path']}"/>
</vector>
''')
print(f'Generated {len(icons)} original Android application icons')
