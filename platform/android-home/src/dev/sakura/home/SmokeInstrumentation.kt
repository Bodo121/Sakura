package dev.sakura.home

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Executed on an Android emulator, not a desktop rendering mock. */
class SmokeInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun ui(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync{try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let{throw it}}
    private fun screenshot(name:String){
        val shot=checkNotNull(uiAutomation.takeScreenshot()){ "Emulator screenshot failed" }
        val folder=checkNotNull(targetContext.getExternalFilesDir(null))
        File(folder,name).outputStream().use{check(shot.compress(Bitmap.CompressFormat.PNG,100,it))};shot.recycle()
    }
    override fun onStart(){
        val result=Bundle()
        try{
            val intent=Intent().setClassName("dev.sakura.home","dev.sakura.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity=startActivitySync(intent) as HomeActivity
            var ready=false
            for(i in 0 until 150){ui{ready=activity.smokeReady()};if(ready)break;Thread.sleep(100)}
            check(ready){"Activity did not discover launchable apps"}
            check(NativeBridge.available){"Rust library was not loaded"}
            check(NativeBridge.search(listOf("Camera","Settings","Photos"),"sett")==listOf(1)){"Real JNI search returned wrong indices"}
            check(NativeBridge.search(listOf("École","Camera"),"ÉCO")==listOf(0)){"JNI Unicode search failed"}
            Thread.sleep(1000);screenshot("home.png")
            ui{activity.openDrawer();check(activity.smokeSearch("settings")>0);check(activity.smokeHasMatch("settings"));check(activity.smokeColumns() in 4..5)}
            Thread.sleep(500);screenshot("search.png")
            ui{check(activity.smokeSearch("an_app_that_does_not_exist_938721")==0);activity.smokeSearch("")}
            Thread.sleep(300);screenshot("library.png")
            ui{activity.closeDrawer()};Thread.sleep(300)
            check(!activity.isFinishing){"Launcher finished unexpectedly"}
            result.putString("stream","Sakura Home smoke tests passed: Activity startup, app discovery, native JNI, Unicode search, empty results, drawer and screenshots.\n")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("stream","Sakura Home smoke tests FAILED: ${android.util.Log.getStackTraceString(t)}");finish(Activity.RESULT_CANCELED,result)}
    }
}
