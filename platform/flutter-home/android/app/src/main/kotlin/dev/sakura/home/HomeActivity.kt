package dev.sakura.home

import android.app.role.RoleManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import com.example.liquidglass.LiquidGlassView
import com.example.liquidglass.GlassMaterial
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class HomeActivity : FlutterActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sakura_home", MODE_PRIVATE) }
    private lateinit var channel: MethodChannel
    private var pickerResult: MethodChannel.Result? = null
    internal var wallpaper: Bitmap? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (::channel.isInitialized) channel.invokeMethod("catalogChanged", null) }
    }
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        engine.platformViewsController.registry.registerViewFactory("dev.sakura.home/glass", GlassFactory(this))
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "dev.sakura.home/launcher")
        channel.setMethodCallHandler { call, result ->
            try { when (call.method) {
                "capture" -> {
                    check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    val name=(call.arguments as String);require(name in listOf("home","search","library"))
                    val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window,bitmap,{status ->
                        if(status==PixelCopy.SUCCESS){File(getExternalFilesDir(null),"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};result.success(true)}
                        else result.error("capture","PixelCopy status $status",null)
                        bitmap.recycle()
                    },main)
                }
                "load" -> worker.execute {
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val apps = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.packageName != packageName }
                            .distinctBy { ComponentName(it.activityInfo.packageName,it.activityInfo.name).flattenToString() }
                            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                            .map { mapOf("id" to ComponentName(it.activityInfo.packageName,it.activityInfo.name).flattenToString(),"label" to it.loadLabel(packageManager).toString()) }
                        if (wallpaper == null) wallpaper = loadWallpaper()
                        val bytes = ByteArrayOutputStream().also { wallpaper!!.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
                        val data = mapOf("apps" to apps, "favorites" to jsonList("favorites"), "dock" to jsonList("dock"),
                            "initialized" to prefs.getBoolean("flutter_initialized",false), "columns" to prefs.getInt("columns",4), "palette" to prefs.getInt("palette",0),
                            "themedIcons" to prefs.getBoolean("themed_icons",true), "labels" to prefs.getBoolean("labels",true), "motion" to prefs.getBoolean("fluid_motion",true),
                            "glass" to prefs.getBoolean("liquid_glass",true), "theme" to prefs.getString("flutter_theme","system"),
                            "wallpaper" to bytes,"accent" to if(Build.VERSION.SDK_INT>=31)getColor(android.R.color.system_accent1_500)else 0xff7161b6.toInt(),"rust" to NativeBridge.available)
                        main.post { result.success(data) }
                    } catch (e:Exception) { main.post { result.error("catalog",e.message,null) } }
                }
                "icon" -> worker.execute {
                    try {
                        val component = ComponentName.unflattenFromString(call.arguments as String) ?: error("Invalid app")
                        val drawable = packageManager.getActivityIcon(component)
                        val bitmap = Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0,0,144,144);drawable.draw(Canvas(bitmap))
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
                        bitmap.recycle(); main.post { result.success(bytes) }
                    } catch (e:Exception) { main.post { result.error("icon",e.message,null) } }
                }
                "search" -> { val labels = call.argument<List<String>>("labels") ?: emptyList(); result.success(NativeBridge.search(labels,call.argument<String>("query") ?: "")) }
                "save" -> {
                    val value=call.arguments as Map<*,*>; val edit=prefs.edit().putBoolean("flutter_initialized",true)
                    (value["favorites"] as? List<*>)?.let { edit.putString("favorites",JSONArray(it).toString()) }
                    (value["dock"] as? List<*>)?.let { edit.putString("dock",JSONArray(it).toString()) }
                    (value["columns"] as? Int)?.let { edit.putInt("columns",it.coerceIn(4,5)) }
                    (value["theme"] as? String)?.let { edit.putString("flutter_theme",it) }
                    (value["motion"] as? Boolean)?.let { edit.putBoolean("fluid_motion",it) }
                    (value["glass"] as? Boolean)?.let { edit.putBoolean("liquid_glass",it) }
                    (value["themedIcons"] as? Boolean)?.let { edit.putBoolean("themed_icons",it) }
                    (value["labels"] as? Boolean)?.let { edit.putBoolean("labels",it) }
                    edit.apply();result.success(null)
                }
                "launch" -> {
                    val component=ComponentName.unflattenFromString(call.arguments as String) ?: error("Invalid app")
                    val activity=packageManager.getActivityInfo(component,0)
                    require(activity.exported && activity.enabled) { "App unavailable" }
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    result.success(null)
                }
                "details", "uninstall" -> {
                    val component=ComponentName.unflattenFromString(call.arguments as String) ?: error("Invalid app")
                    startActivity(Intent(if(call.method=="details") Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Intent.ACTION_DELETE, Uri.parse("package:${component.packageName}")))
                    result.success(null)
                }
                "defaultLauncher" -> {
                    if(Build.VERSION.SDK_INT>=29) { val role=getSystemService(RoleManager::class.java);startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                    else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    result.success(null)
                }
                "wallpaper" -> {
                    check(pickerResult==null) { "Picker already open" };pickerResult=result
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),42)
                }
                "clock" -> {startActivity(Intent("android.intent.action.SHOW_ALARMS"));result.success(null)}
                else -> result.notImplemented()
            }} catch (e:Exception) { result.error("launcher", e.message, null) }
        }
    }
    private fun jsonList(key:String): List<String> = try { val array=JSONArray(prefs.getString(key,"[]"));(0 until array.length()).map{array.getString(it)} }catch(e:Exception){emptyList()}
    private fun loadWallpaper():Bitmap {
        val file=File(filesDir,"wallpaper.jpg")
        if(file.exists()) try {
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,options)
            var sample=1;while(options.outWidth/sample>2048 || options.outHeight/sample>3072)sample*=2
            return BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample}) ?: defaultWallpaper()
        } catch(e:Exception) { }
        return defaultWallpaper()
    }
    private fun defaultWallpaper():Bitmap {
        val bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);val p=Paint(3)
        p.shader=LinearGradient(0f,0f,720f,1440f,intArrayOf(0xffe4e6fa.toInt(),0xffb0bded.toInt(),0xff797bb6.toInt()),null,Shader.TileMode.CLAMP);c.drawPaint(p)
        p.shader=RadialGradient(80f,410f,700f,intArrayOf(0xfff4dbe9.toInt(),0x00ecd5f4),null,Shader.TileMode.CLAMP);c.drawPaint(p)
        for(i in 0..3){
            val path=Path();path.moveTo(-180f,900f+i*90);path.cubicTo(200f,500f+i*40,450f,1650f-i*90,950f,720f+i*80);path.lineTo(1000f,1600f);path.lineTo(-180f,1600f);path.close()
            p.shader=LinearGradient(0f,650f+i*60,600f,1550f,intArrayOf(0x55ffffff,0x117b64ba,0x66534b94),null,Shader.TileMode.CLAMP);c.drawPath(path,p)
            p.shader=null;p.color=0x33ffffff;p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawPath(path,p);p.style=Paint.Style.FILL
        };return bitmap
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val filter=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);if(::channel.isInitialized)channel.invokeMethod("homePressed",null)}
    @Deprecated("Legacy activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=42)return
        val result=pickerResult ?: return;pickerResult=null
        if(resultCode!=RESULT_OK || data?.data==null){result.success(false);return}
        val uri=data.data!!
        worker.execute {try {
            val bytes=contentResolver.openInputStream(uri)!!.use { it.readNBytes(16*1024*1024+1) };require(bytes.size<=16*1024*1024){"Choose an image smaller than 16 MB"}
            val temp=File(filesDir,"wallpaper.import");temp.writeBytes(bytes)
            val size=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(temp.path,size);require(size.outWidth>0 && size.outHeight>0){"Invalid image"}
            var sample=1;while(size.outWidth/sample>2048 || size.outHeight/sample>3072)sample*=2
            val bitmap=BitmapFactory.decodeFile(temp.path,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("Image not supported")
            val output=File(filesDir,"wallpaper.new");output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,92,it) }
            check(output.renameTo(File(filesDir,"wallpaper.jpg"))){"Could not save wallpaper"};temp.delete();wallpaper=bitmap
            main.post{result.success(true)}
        } catch(e:Exception){main.post{result.error("wallpaper",e.message,null)}}}
    }
    override fun onDestroy(){unregisterReceiver(receiver);worker.shutdown();pickerResult?.error("closed","Activity closed",null);super.onDestroy()}
}

private class GlassFactory(private val activity:HomeActivity):PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context:Context,id:Int,args:Any?):PlatformView = object:PlatformView {
        val root=FrameLayout(context)
        val backdrop=object:View(context){
            val paint=Paint(3);val location=IntArray(2)
            override fun onDraw(canvas:Canvas){
                val bitmap=activity.wallpaper ?: return
                getLocationOnScreen(location)
                val metrics=resources.displayMetrics
                val scale=maxOf(metrics.widthPixels.toFloat()/bitmap.width,metrics.heightPixels.toFloat()/bitmap.height)
                val w=bitmap.width*scale;val h=bitmap.height*scale
                canvas.drawBitmap(bitmap,null,RectF((metrics.widthPixels-w)/2-location[0],(metrics.heightPixels-h)/2-location[1],(metrics.widthPixels+w)/2-location[0],(metrics.heightPixels+h)/2-location[1]),paint)
            }
        }
        val glass=LiquidGlassView(context).apply {
            cornerRadius=32*resources.displayMetrics.density
            material=GlassMaterial.REGULAR
            refractionHeight=30*resources.displayMetrics.density
            bevelWidth=18*resources.displayMetrics.density
            dispersionStrength=.08f
            enableDynamicBackground=false
            enableSensorHighlight=false
            enablePressEffect=false
            backdropSource=backdrop
        }
        init {root.addView(backdrop,FrameLayout.LayoutParams(-1,-1));root.addView(glass,FrameLayout.LayoutParams(-1,-1))}
        override fun getView():View=root
        override fun dispose(){glass.backdropSource=null}
    }
}
