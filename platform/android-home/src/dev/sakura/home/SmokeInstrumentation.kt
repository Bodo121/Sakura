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
    private fun awaitUi(message:String,condition:()->Boolean){
        for(i in 0 until 200){var done=false;ui{done=condition()};if(done)return;Thread.sleep(25)}
        error(message)
    }
    private fun swipe(x:Float,y0:Float,y1:Float){
        val down=android.os.SystemClock.uptimeMillis()
        for(i in 0..16){val action=when(i){0->android.view.MotionEvent.ACTION_DOWN;16->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE}
            val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y0+(y1-y0)*i/16f,0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(e,false)){"Could not inject drawer gesture"};e.recycle();Thread.sleep(7)
        }
    }
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
            check(NativeBridge.lastEngine=="rust"){"JNI calls fell back to Kotlin"}
            var iconsReady=false
            for(i in 0 until 100){ui{iconsReady=activity.smokeIconsReady()};if(iconsReady)break;Thread.sleep(100)}
            check(iconsReady){"Home icons did not finish loading"}
            awaitUi("Glass backdrop was not generated"){activity.smokeBackdropReady()}
            val solid=IntArray(35){0xff557799.toInt()}
            check(NativeBridge.blurBackdrop(solid,5,7)?.contentEquals(solid)==true){"Real Rust blur JNI failed"}
            waitForIdleSync();Thread.sleep(250);screenshot("home.png")
            ui{activity.openDrawer()}
            awaitUi("Drawer spring did not settle open"){activity.smokeDrawerSettled(true)}
            ui{check(activity.smokeBackdropBlurred()){ "Home scene blur was not applied" }}
            waitForIdleSync();Thread.sleep(550)
            ui{
                check(activity.smokeSearch("settings")>0){"Installed Settings app missing from search"}
                check(activity.smokeHasMatch("settings")){"Search returned a non-matching app"}
                check(activity.smokeColumns() in 4..5){"Laid-out drawer column count is ${activity.smokeColumns()}"}
            }
            waitForIdleSync();Thread.sleep(250);screenshot("search.png")
            ui{check(activity.smokeSearch("an_app_that_does_not_exist_938721")==0);activity.smokeSearch("")}
            Thread.sleep(300);screenshot("library.png")
            ui{activity.closeDrawer()}
            awaitUi("Drawer spring did not settle closed"){activity.smokeDrawerSettled(false)}
            // Rapid reversals must not leave a transparent interactive panel or a blurred Home.
            ui{activity.openDrawer();activity.closeDrawer();activity.openDrawer()}
            awaitUi("Interrupted drawer did not recover"){activity.smokeDrawerSettled(true)}
            ui{activity.closeDrawer()};awaitUi("Close after interruption failed"){activity.smokeDrawerSettled(false)}
            val metrics=targetContext.resources.displayMetrics
            swipe(metrics.widthPixels*.5f,metrics.heightPixels*.84f,metrics.heightPixels*.3f)
            awaitUi("Finger-tracked drawer did not settle"){activity.smokeDrawerSettled(true)}
            swipe(metrics.widthPixels*.5f,metrics.heightPixels*.13f,metrics.heightPixels*.75f)
            awaitUi("Pull-down gesture did not close drawer"){activity.smokeDrawerSettled(false)}
            ui{activity.smokeMotion(false);activity.openDrawer();check(activity.smokeDrawerSettled(true));activity.smokeGlass(false);check(!activity.smokeBackdropBlurred());activity.closeDrawer();check(activity.smokeDrawerSettled(false));activity.smokeGlass(true);activity.smokeMotion(true)}
            check(!activity.isFinishing){"Launcher finished unexpectedly"}
            ui{activity.smokeLaunchSettings()}
            Thread.sleep(700)
            val descriptor=uiAutomation.executeShellCommand("dumpsys activity activities")
            val activities=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{it.readText()}
            check(activities.lineSequence().filter{it.contains("ResumedActivity")||it.contains("topResumedActivity")}.any{it.contains("com.android.settings")}){"Settings did not become the resumed app"}
            result.putString("stream","Sakura Home smoke tests passed: Activity startup, app discovery, native JNI, Unicode search, empty results, real Rust glass blur, GPU backdrop blur, spring interruption, real swipe-up and pull-down gestures, reduced motion, fallback, screenshots and launching Android Settings.\n")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("stream","Sakura Home smoke tests FAILED: ${android.util.Log.getStackTraceString(t)}");finish(Activity.RESULT_CANCELED,result)}
    }
}
