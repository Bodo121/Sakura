package dev.sakura.host;
import android.app.NativeActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import java.io.*;
public final class SakuraActivity extends NativeActivity {
    private static final int IMPORT=41, EXPORT=42;
    private File exportSource;
    public String launchApp(String pkg) {
        if (!pkg.matches("dev\\.sakura\\.(settings|home|files|coremanager|patch|terminal)")) return "Unknown Sakura application";
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i==null)return "Application is not installed";
        runOnUiThread(()->startActivity(i));return "Launching "+pkg;
    }
    public String cliPath(String unused) {return getApplicationInfo().nativeLibraryDir+"/libsakura_cli.so";}
    public String requestDiagnostics(String unused) {
        new Thread(()->{
            Process process=null;
            try {
                process=new ProcessBuilder("su","-c","/data/adb/modules/sakuraos_dev/bin/sakura diagnostics").redirectErrorStream(true).start();
                if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("APatch request timed out");}
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(process.getInputStream(),bytes,65536);
                File tmp=new File(getFilesDir(),"diagnostics.tmp");
                try(OutputStream out=new FileOutputStream(tmp)){out.write(bytes.toByteArray());}
                if(!tmp.renameTo(new File(getFilesDir(),"diagnostics.json")))throw new IOException("Cannot save diagnostics");
            } catch(Exception e){android.util.Log.e("SakuraHost","Explicit diagnostics request failed",e);}
            finally{if(process!=null)process.destroy();}
        },"SakuraApprovedDiagnostics").start();
        return "Approve only if you trust SakuraCore in APatch. Tap Refresh after approval. No root shell is exposed.";
    }
    public String deviceInfo(String unused) {
        android.view.Display d=getWindowManager().getDefaultDisplay();
        android.util.DisplayMetrics m=new android.util.DisplayMetrics();d.getRealMetrics(m);
        Intent b=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String battery=b==null?"Unavailable":b.getIntExtra("level",-1)+"/"+b.getIntExtra("scale",-1)+" · plugged="+b.getIntExtra("plugged",-1);
        return android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" · Android "+android.os.Build.VERSION.RELEASE+"\n"+m.widthPixels+"×"+m.heightPixels+" · "+m.densityDpi+" dpi · "+d.getRefreshRate()+" Hz\nBattery "+battery+"\nWi-Fi setting="+Settings.Global.getInt(getContentResolver(),"wifi_on",-1)+" · Bluetooth setting="+Settings.Global.getInt(getContentResolver(),"bluetooth_on",-1);
    }
    public String importDocument(String unused) {
        runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);});
        return "Choose a document, then tap Refresh. Import limit: 16 MiB.";
    }
    public String exportDocument(String path) {
        try {
            File source=new File(path).getCanonicalFile();File root=new File(getFilesDir(),"files").getCanonicalFile();
            if(!source.getPath().startsWith(root.getPath()+File.separator)||!source.isFile())return "Export outside private files denied";
            exportSource=source;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,source.getName());startActivityForResult(i,EXPORT);});return "Choose export destination";
        }catch(IOException e){return e.toString();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        new Thread(()->{
            try {
                if(request==IMPORT) {
                    String name="import-"+System.currentTimeMillis();
                    try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
                        if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&n.matches("[a-zA-Z0-9._-]{1,128}")&&!n.equals(".")&&!n.equals(".."))name=n;}
                    }
                    File root=new File(getFilesDir(),"files");root.mkdirs();File target=new File(root,name);
                    if(!target.createNewFile())throw new IOException("Destination exists");
                    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){copy(in,out,16*1024*1024);}
                    catch(Exception e){target.delete();throw e;}
                } else if(request==EXPORT && exportSource!=null){try(InputStream in=new FileInputStream(exportSource);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){copy(in,out,16*1024*1024);}}
            }catch(Exception e){android.util.Log.e("SakuraHost","Document transfer failed",e);}
        },"SakuraDocumentTransfer").start();
    }
    private static void copy(InputStream in,OutputStream out,int max)throws IOException {
        if(in==null||out==null)throw new IOException("Document stream unavailable");
        byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("Document exceeds 16 MiB");out.write(b,0,n);}
    }
}
