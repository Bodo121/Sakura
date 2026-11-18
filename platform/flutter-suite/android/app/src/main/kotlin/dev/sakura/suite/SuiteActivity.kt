package dev.sakura.suite

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.*
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.*
import java.io.*
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class SuiteActivity:FlutterActivity(){
 private val worker=Executors.newSingleThreadExecutor()
 private val main=Handler(Looper.getMainLooper())
 private val prefs by lazy{getSharedPreferences("sakura",MODE_PRIVATE)}
 private val kind by lazy{packageManager.getApplicationInfo(packageName,PackageManager.GET_META_DATA).metaData.getString("sakura.app")!!}
 private var pending:MethodChannel.Result?=null
 private var permissionCall:MethodCall?=null
 private var player:MediaPlayer?=null
 private var recorder:MediaRecorder?=null
 private var captureFile:File?=null
 override fun configureFlutterEngine(engine:FlutterEngine){
  super.configureFlutterEngine(engine)
  MethodChannel(engine.dartExecutor.binaryMessenger,"dev.sakura/system").setMethodCallHandler{call,result->handle(call,result)}
 }
 private fun permissionFor():String?=when(kind){"contacts"->Manifest.permission.READ_CONTACTS;"calendar"->Manifest.permission.READ_CALENDAR;"gallery"->Manifest.permission.READ_MEDIA_IMAGES;"music"->Manifest.permission.READ_MEDIA_AUDIO;else->null}
 private fun handle(call:MethodCall,result:MethodChannel.Result){try{when(call.method){
  "list"->{
   val permission=permissionFor()
   if(permission!=null && checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){check(pending==null){"Another request is open"};pending=result;permissionCall=call;requestPermissions(arrayOf(permission),10)}
   else worker.execute{try{val data=listData(call.arguments as? String);main.post{result.success(data)}}catch(e:Exception){main.post{result.error("read",e.message,null)}}}
  }
  "notes"->result.success(prefs.getString("notes","[]"))
  "saveNotes"->{val json=call.arguments as String;require(json.length<2*1024*1024);JSONArray(json);prefs.edit().putString("notes",json).apply();result.success(null)}
  "info"->{val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val stat=StatFs(filesDir.path);result.success(mapOf("device" to "${Build.MANUFACTURER} ${Build.MODEL}","android" to Build.VERSION.RELEASE,"kernel" to System.getProperty("os.version"),"battery" to battery?.getIntExtra("level",-1),"charging" to battery?.getIntExtra("plugged",0),"storageFree" to stat.availableBytes,"storageTotal" to stat.totalBytes,"memory" to File("/proc/meminfo").takeIf{it.canRead()}?.readText()?.take(2048),"sakuraVersion" to "0.2.0","daemon" to "Private IPC: no Android app bridge provisioned"))}
  "action"->{perform(call.argument<String>("name")!!,call.arguments as Map<*,*>);result.success(null)}
  "pick"->{check(pending==null){"Another picker is open"};pending=result
   val intent=when(kind){"files"->Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);"gallery","wallpapers"->Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);"installer"->Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE);else->Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)}
   startActivityForResult(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),20)
  }
  "open"->{val uri=Uri.parse(call.arguments as String);val mime=contentResolver.getType(uri)?:"*/*";startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));result.success(null)}
  "thumbnail"->worker.execute{try{
    val uri=Uri.parse(call.arguments as String);val b=try {contentResolver.loadThumbnail(uri,android.util.Size(512,512),null)}catch(e:Exception){
      val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
      var sample=1;while(bounds.outWidth/sample>1024||bounds.outHeight/sample>1024)sample*=2
      contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
    }
    val bytes=b?.let{ByteArrayOutputStream().also{o->it.compress(Bitmap.CompressFormat.JPEG,85,o)}.toByteArray()};main.post{result.success(bytes)}
   }catch(e:Exception){main.post{result.error("image",e.message,null)}}}
  "play"->{player?.release();player=MediaPlayer().apply{setDataSource(this@SuiteActivity,Uri.parse(call.arguments as String));setOnPreparedListener{it.start()};setOnErrorListener{_,_,_->true};prepareAsync()};result.success(null)}
  "stop"->{player?.release();player=null;stopRecording();result.success(null)}
  "record"->{
   if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){check(pending==null);pending=result;permissionCall=call;requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10)}
   else{val file=File(filesDir,"recording-${System.currentTimeMillis()}.m4a");recorder=MediaRecorder().apply{setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setOutputFile(file.path);prepare();start()};result.success(file.name)}
  }
  "capture"->{check(pending==null);pending=result;captureFile=File(filesDir,"photo-${System.currentTimeMillis()}.jpg");val uri=FileProvider.getUriForFile(this,"$packageName.files",captureFile!!);startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),21)}
  "wallpaper"->{val uri=Uri.parse(call.arguments as String);worker.execute{try{contentResolver.openInputStream(uri)!!.use{WallpaperManager.getInstance(this).setStream(it)};main.post{result.success(null)}}catch(e:Exception){main.post{result.error("wallpaper",e.message,null)}}}}
  "file"->{val action=call.argument<String>("action");val uri=call.argument<String>("uri")?.let{Uri.parse(it)};when(action){"delete"->DocumentsContract.deleteDocument(contentResolver,requireNotNull(uri));"rename"->DocumentsContract.renameDocument(contentResolver,requireNotNull(uri),call.argument<String>("name")!!);"folder"->{val tree=Uri.parse(prefs.getString("tree",null)?:error("Choose a folder"));val parent=call.argument<String>("uri")?.let{Uri.parse(it)}?:DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));DocumentsContract.createDocument(contentResolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,call.argument<String>("name")!!)}};result.success(null)}
  "patch"->worker.execute{try{
    val verb=call.argument<String>("verb")?:error("Missing patch operation")
    val store=File(filesDir,"sakura-store").apply{mkdirs();android.system.Os.chmod(path,448)}
    val key=File(filesDir,"trusted-patch-key.pub");val patch=File(filesDir,"imported.patch")
    var answer=""
    if(verb=="trustKey"||verb=="import"){
     val uri=Uri.parse(call.argument<String>("uri")?:error("Choose a file"));val max=if(verb=="trustKey")32 else 16*1024*1024
     val bytes=contentResolver.openInputStream(uri)!!.use{it.readNBytes(max+1)};require(bytes.size<=max && bytes.isNotEmpty()){ "Input file exceeds limit" }
     if(verb=="trustKey")require(bytes.size==32){"Ed25519 public key must be 32 raw bytes"}
     val target=if(verb=="trustKey")key else patch;val temp=File(filesDir,"patch-import.tmp");temp.writeBytes(bytes);android.system.Os.chmod(temp.path,384);check(temp.renameTo(target))
     answer=if(verb=="trustKey")"Trusted public-key SHA-256: "+java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}else "Imported ${bytes.size} bytes. Not applied."
    }else{
     require(verb in listOf("verify","stage","apply","list","rollback")){"Unsupported patch operation"}
     fun runCli(operation:String,argument:String?=null):String{
      val command=mutableListOf("/system/bin/sakura","patch",operation);if(argument!=null)command.add(argument)
      val process=ProcessBuilder(command).redirectErrorStream(true).apply{environment()["SAKURA_STATE_DIR"]=store.path;environment()["SAKURA_UPDATE_DIR"]=File(store,"updates").path;environment()["SAKURA_PATCH_KEY"]=key.path}.start()
      val output=process.inputStream.bufferedReader().use{it.readText()};check(process.waitFor()==0){output.take(4096)};return output.trim()
     }
     if(verb in listOf("verify","stage")){
      check(key.exists()){ "Choose and explicitly trust an Ed25519 public key first" };check(patch.exists()){ "Import a patch first" }
      val metadata=runCli("verify",patch.path)
      require(JSONObject(metadata).getString("activation")=="AssetsReload"){ "Only owned-file asset generations can be activated in this UI" }
      answer=if(verb=="verify")metadata else runCli("stage",patch.path)
     }else if(verb=="apply"){
      val generation=call.argument<String>("generation")?:error("No staged generation");require(generation.matches(Regex("[a-f0-9]{64}")));answer=runCli("apply",generation)
     }else{answer=runCli(verb)}
    }
    main.post{result.success(answer)}
   }catch(e:Exception){main.post{result.error("patch",e.message,null)}}}
  "command"->{val command=call.arguments as String;require(command.length<=4096);worker.execute{try{val process=ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true).start();val output=ByteArrayOutputStream();val reader=Thread{process.inputStream.use{input->val buf=ByteArray(4096);while(true){val n=input.read(buf);if(n<0)break;if(output.size()<65536)output.write(buf,0,minOf(n,65536-output.size()))}}}.apply{isDaemon=true};reader.start();val ended=process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);if(!ended)process.destroyForcibly();reader.join(1000);val text=output.toString("UTF-8");main.post{result.success(mapOf("output" to text,"exit" to if(ended)process.exitValue() else 124))}}catch(e:Exception){main.post{result.error("shell",e.message,null)}}}}
  else->result.notImplemented()
 }}catch(e:Exception){if(pending===result)pending=null;result.error("system",e.message,null)}}
 private fun perform(name:String,args:Map<*,*>){val value=args["value"] as? String?:"";when(name){
  "dial"->{require(value.matches(Regex("[+0-9*# -]{1,40}")));startActivity(Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",value,null)))}
  "sms"->{require(value.matches(Regex("[+0-9 -]{1,40}")));startActivity(Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",value,null)).putExtra("sms_body",args["body"] as? String?:""))}
  "contact"->startActivity(Intent(Intent.ACTION_INSERT,ContactsContract.Contacts.CONTENT_URI).setType(ContactsContract.Contacts.CONTENT_TYPE))
  "event"->startActivity(Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI))
  "alarm"->{val parts=value.split(":");val hour=parts[0].toInt();val minute=parts[1].toInt();require(hour in 0..23&&minute in 0..59);startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute).putExtra(AlarmClock.EXTRA_MESSAGE,"Sakura Clock"))}
  "alarms"->startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
  "install"->{val uri=Uri.parse(value);startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
  "settings"->{val action=mapOf("display" to Settings.ACTION_DISPLAY_SETTINGS,"network" to Settings.ACTION_WIRELESS_SETTINGS,"battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,"apps" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,"security" to Settings.ACTION_SECURITY_SETTINGS,"sound" to Settings.ACTION_SOUND_SETTINGS,"storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS)[value]?:Settings.ACTION_SETTINGS;startActivity(Intent(action))}
  else->error("Unsupported system action")
 }}
 private fun listData(folder:String?=null):List<Map<String,Any?>>{
  if(kind=="files"){
   val tree=Uri.parse(prefs.getString("tree",null)?:return emptyList());val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,(if(folder==null)DocumentsContract.getTreeDocumentId(tree)else DocumentsContract.getDocumentId(Uri.parse(folder))))
   val out=mutableListOf<Map<String,Any?>>()
   contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use{c->while(c.moveToNext()&&out.size<1000)out.add(mapOf("id" to DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)).toString(),"title" to c.getString(1),"subtitle" to "${c.getLong(3)} bytes","folder" to (c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR)))};return out.sortedBy{it["title"].toString().lowercase()}
  }
  if(kind=="recorder")return filesDir.listFiles()?.filter{it.extension=="m4a"}?.sortedByDescending{it.lastModified()}?.map{mapOf("id" to FileProvider.getUriForFile(this,"$packageName.files",it).toString(),"title" to it.name,"subtitle" to "${it.length()} bytes")}?:emptyList()
  val (uri,projection,selection,sort)=when(kind){
   "contacts"->arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf("_id","display_name","data1"),null,"display_name ASC")
   "calendar"->arrayOf(CalendarContract.Events.CONTENT_URI,arrayOf("_id","title","dtstart"),"deleted=0","dtstart DESC")
   "gallery"->arrayOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id","_display_name","date_added"),null,"date_added DESC")
   "music"->arrayOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,arrayOf("_id","title","artist"),"is_music!=0","title ASC")
   else->return emptyList()
  }
  val out=mutableListOf<Map<String,Any?>>()
  @Suppress("UNCHECKED_CAST")
  contentResolver.query(uri as Uri,projection as Array<String>,selection as String?,null,sort as String?)?.use{c->while(c.moveToNext()&&out.size<500){out.add(mapOf("id" to ContentUris.withAppendedId(uri as Uri,c.getLong(0)).toString(),"title" to (c.getString(1)?:"Untitled"),"subtitle" to (c.getString(2)?:"")))}}
  return out
 }
 private fun stopRecording(){recorder?.let{try{it.stop()}catch(e:RuntimeException){};it.release()};recorder=null}
 override fun onPause(){stopRecording();player?.release();player=null;super.onPause()}
 override fun onDestroy(){worker.shutdown();stopRecording();player?.release();pending?.error("closed","Activity closed",null);super.onDestroy()}
 override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code!=10)return;val result=pending?:return;val call=permissionCall;pending=null;permissionCall=null;if(results.isNotEmpty()&&results.all{it==PackageManager.PERMISSION_GRANTED}&&call!=null)handle(call,result)else result.error("permission","Permission was not granted",null)}
 @Deprecated("Legacy activity bridge")
 override fun onActivityResult(code:Int,status:Int,data:Intent?){super.onActivityResult(code,status,data);if(code !in listOf(20,21))return;val result=pending?:return;pending=null;if(status!=RESULT_OK){result.success(null);return};try{val uri=if(code==21)FileProvider.getUriForFile(this,"$packageName.files",captureFile!!)else data?.data?:error("No selected file");if(kind=="files"){val flags=data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);contentResolver.takePersistableUriPermission(uri,flags);prefs.edit().putString("tree",uri.toString()).apply()};result.success(uri.toString())}catch(e:Exception){result.error("selection",e.message,null)}}
}
