import sys
from pathlib import Path
out,package,label,native=sys.argv[1:]
queries=''.join(f'<package android:name="dev.sakura.{p}"/>' for p in ['settings','home','files','coremanager','patch','terminal'])
home='<category android:name="android.intent.category.HOME"/><category android:name="android.intent.category.DEFAULT"/>' if package=='home' else ''
Path(out).write_text(f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.sakura.{package}" android:versionCode="100" android:versionName="0.1.0">
<uses-sdk android:minSdkVersion="34" android:targetSdkVersion="34"/>
<uses-feature android:glEsVersion="0x00020000" android:required="true"/>
<queries>{queries}</queries>
<application android:label="{label}" android:hasCode="true" android:debuggable="false" android:allowBackup="false" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.NoActionBar">
<activity android:name="dev.sakura.host.SakuraActivity" android:exported="true" android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|density">
<meta-data android:name="android.app.lib_name" android:value="{native}"/>
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>{home}</intent-filter>
</activity></application></manifest>""")
