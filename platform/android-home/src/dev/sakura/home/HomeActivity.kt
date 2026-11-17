package dev.sakura.home

import android.app.Activity
import android.app.Dialog
import android.app.role.RoleManager
import android.content.*
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.*
import android.text.format.DateFormat
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import java.io.File
import java.text.Collator
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*

data class LaunchApp(val id:String, val label:String, val component:ComponentName, val info:ResolveInfo)

class HomeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("sakura_home", MODE_PRIVATE) }
    private val worker=Executors.newFixedThreadPool(2)
    private val handler=Handler(Looper.getMainLooper())
    private val iconCache=android.util.LruCache<String,Drawable>(96)
    private val loadingIcons=HashSet<String>()
    private var apps=emptyList<LaunchApp>()
    private var matches=emptyList<LaunchApp>()
    private var favorites=mutableListOf<String>()
    private var dock=mutableListOf<String>()
    private var page=0
    private var columns=4
    private var rows=2
    private var labels=true
    private var palette=0
    private var iconDp=56
    private var query=""
    private var loaded=false
    private var resumed=false
    private var drawerOpen=false
    private var generation=0
    private var wallpaper:Bitmap?=null
    private lateinit var root:GestureRoot
    private lateinit var home:LinearLayout
    private lateinit var homeGrid:GridLayout
    private lateinit var dockView:LinearLayout
    private lateinit var dots:LinearLayout
    private lateinit var clock:TextView
    private lateinit var date:TextView
    private lateinit var space:TextView
    private lateinit var drawer:LinearLayout
    private lateinit var grid:GridView
    private lateinit var search:EditText
    private lateinit var drawerCount:TextView
    private lateinit var adapter:AppAdapter
    internal var lastLaunch:String?=null
    private val packages=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){loadApps()}}
    private val ticker=object:Runnable{override fun run(){if(!resumed)return;updateClock();handler.postDelayed(this,60000L-System.currentTimeMillis()%60000L)}}

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        page=state?.getInt("page",0)?:0
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        columns=prefs.getInt("columns",4).coerceIn(4,5);labels=prefs.getBoolean("labels",true)
        palette=prefs.getInt("palette",0).coerceIn(0,3);iconDp=prefs.getInt("icon",56).coerceIn(44,64)
        favorites=readList("favorites");dock=readList("dock")
        loadWallpaper();buildUi();loadApps()
        val filter=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(packages,filter)
        android.util.Log.i("SakuraHome","Kotlin activity created; nativeSearch=${NativeBridge.available}")
    }
    override fun onResume(){super.onResume();resumed=true;handler.removeCallbacks(ticker);handler.post(ticker)}
    override fun onPause(){resumed=false;handler.removeCallbacks(ticker);super.onPause()}
    override fun onDestroy(){unregisterReceiver(packages);generation++;worker.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putInt("page",page);super.onSaveInstanceState(out)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);closeDrawer();page=0;if(loaded)renderHome()}
    @Deprecated("Android Activity back compatibility") override fun onBackPressed(){
        if(drawerOpen)closeDrawer()
        else if(page>0){page=0;renderHome()}
        else if(!isDefaultHome())super.onBackPressed()
    }
    private fun isDefaultHome():Boolean = if(Build.VERSION.SDK_INT>=29){
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }else{
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName==packageName
    }

    private fun dp(n:Int)=(n*resources.displayMetrics.density).roundToInt()
    private fun white(a:Int=255)=Color.argb(a,255,255,255)
    private fun txt(value:String,size:Float=14f,color:Int=white(),weight:Boolean=false)=TextView(this).apply{
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        typeface=Typeface.create(if(weight)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun rounded(color:Int,radius:Int=24,stroke:Int=white(35))=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)
    }
    private fun touch(v:View,action:()->Unit){
        v.isClickable=true;v.isFocusable=true;v.setOnClickListener{action()}
        val typed=android.util.TypedValue();theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,typed,true)
        if(typed.resourceId!=0)v.foreground=getDrawable(typed.resourceId)
    }
    private fun LinearLayout.gap(n:Int){addView(View(this@HomeActivity),LinearLayout.LayoutParams(1,dp(n)))}
    private fun readList(key:String):MutableList<String> = try{val a=JSONArray(prefs.getString(key,"[]"));MutableList(a.length()){a.getString(it)}}catch(_:Exception){mutableListOf()}
    private fun saveLists(){prefs.edit().putString("favorites",JSONArray(favorites).toString()).putString("dock",JSONArray(dock).toString()).apply()}
    private fun buildUi(){
        drawerOpen=false
        root=GestureRoot(this){direction->when(direction){"up"->openDrawer();"down"->customize();"left"->changePage(1);"right"->changePage(-1)}}
        root.addView(WallpaperView(this,{palette},{wallpaper}),FrameLayout.LayoutParams(-1,-1))
        root.setOnLongClickListener{customize();true}
        home=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(20))}
        root.addView(home,FrameLayout.LayoutParams(-1,-1))
        home.setOnApplyWindowInsetsListener{v,insets->
            v.setPadding(dp(22)+insets.systemWindowInsetLeft,dp(12)+insets.systemWindowInsetTop,dp(22)+insets.systemWindowInsetRight,dp(10)+insets.systemWindowInsetBottom)
            insets
        }
        val brand=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        brand.addView(txt("✿  S A K U R A",12f,white(210),true),LinearLayout.LayoutParams(0,dp(32),1f).apply{gravity=Gravity.CENTER_VERTICAL})
        val edit=txt("＋",27f).apply{gravity=Gravity.CENTER;background=rounded(white(20),18);contentDescription="Customize home"}
        touch(edit){customize()};brand.addView(edit,LinearLayout.LayoutParams(dp(36),dp(36)))
        home.addView(brand);home.gap(18)
        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        date=txt("",15f,white(205));hero.addView(date)
        clock=txt("",76f).apply{typeface=Typeface.create("sans-serif-light",Typeface.NORMAL);letterSpacing=-.055f;contentDescription="Clock. Tap to open alarms"}
        touch(clock){safeStart(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))};hero.addView(clock,LinearLayout.LayoutParams(-1,dp(92)))
        home.addView(hero);home.gap(16)
        val cards=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun card(title:String,subtitle:String,mark:String,onTap:()->Unit):LinearLayout{
            return LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));background=rounded(white(23),22)
                val head=LinearLayout(this@HomeActivity).apply{gravity=Gravity.CENTER_VERTICAL}
                head.addView(txt(title,14f,white(),true),LinearLayout.LayoutParams(0,-2,1f));head.addView(txt(mark,18f,white(210)))
                addView(head);addView(txt(subtitle,11f,white(170)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
                touch(this,onTap)
            }
        }
        val card1=card("Your space","Apps, all together","↗"){openDrawer()}
        space=card1.getChildAt(1) as TextView
        cards.addView(card1,LinearLayout.LayoutParams(0,dp(73),1f).apply{rightMargin=dp(5)})
        cards.addView(card("Connections","Wi-Fi & Bluetooth","⌁"){safeStart(Intent(Settings.ACTION_WIRELESS_SETTINGS))},LinearLayout.LayoutParams(0,dp(73),1f).apply{leftMargin=dp(5)})
        home.addView(cards);home.gap(16)
        homeGrid=GridLayout(this).apply{columnCount=columns;alignmentMode=GridLayout.ALIGN_BOUNDS;useDefaultMargins=false}
        val gridFrame=FrameLayout(this).apply{clipChildren=false}
        gridFrame.addView(homeGrid,FrameLayout.LayoutParams(-1,-2).apply{gravity=Gravity.BOTTOM})
        home.addView(gridFrame,LinearLayout.LayoutParams(-1,0,1f))
        dots=LinearLayout(this).apply{gravity=Gravity.CENTER}
        home.addView(dots,LinearLayout.LayoutParams(-1,dp(26)))
        val searchPill=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),0,dp(16),0);background=rounded(white(26),28)}
        searchPill.addView(txt("⌕",28f,white(215)),LinearLayout.LayoutParams(dp(30),-2))
        searchPill.addView(txt("Search apps",14f,white(200)),LinearLayout.LayoutParams(0,-2,1f))
        searchPill.addView(txt("•••",16f,white(180)))
        touch(searchPill){openDrawer(focus=true)};home.addView(searchPill,LinearLayout.LayoutParams(-1,dp(46)))
        home.gap(12)
        dockView=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(white(37),30)}
        home.addView(dockView,LinearLayout.LayoutParams(-1,dp(78)))
        root.post {
            val available=(home.height-home.paddingTop-home.paddingBottom)/resources.displayMetrics.density
            rows=if(available>=790)3 else 2
            hero.visibility=if(available<600)View.GONE else View.VISIBLE
            if(loaded)renderHome()
        }
        buildDrawer();setContentView(root);root.requestApplyInsets();updateClock()
        if(loaded)renderHome()
    }
    private fun updateClock(){if(!::clock.isInitialized)return;clock.text=DateFormat.getTimeFormat(this).format(Date());date.text=java.text.SimpleDateFormat("EEEE, MMMM d",Locale.getDefault()).format(Date())}
    private fun loadApps(){
        val ticket=++generation
        worker.execute {
            val result=try{
                val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved=if(Build.VERSION.SDK_INT>=33)packageManager.queryIntentActivities(intent,android.content.pm.PackageManager.ResolveInfoFlags.of(0)) else packageManager.queryIntentActivities(intent,0)
                val collator=Collator.getInstance()
                resolved.mapNotNull { info-> val ai=info.activityInfo?:return@mapNotNull null
                    if(!ai.exported||!ai.enabled||!ai.applicationInfo.enabled)return@mapNotNull null
                    val component=ComponentName(ai.packageName,ai.name)
                    LaunchApp(component.flattenToString(),info.loadLabel(packageManager).toString(),component,info)
                }.distinctBy{it.id}.sortedWith{a,b->collator.compare(a.label,b.label)}
            }catch(e:Exception){android.util.Log.e("SakuraHome","Cannot discover launchable applications",e);emptyList()}
            handler.post {
                if(isDestroyed||ticket!=generation)return@post
                apps=result;loaded=true;iconCache.evictAll();loadingIcons.clear()
                if(!prefs.contains("favorites")){
                    val preferred=listOf("phone","dialer","messaging","chrome","camera","gallery","photos","settings","music","files","calendar","clock")
                    val picks=preferred.mapNotNull{word->apps.firstOrNull{it.component.packageName.lowercase(Locale.ROOT).contains(word)}}.distinctBy{it.id}
                    favorites=(picks+apps).distinctBy{it.id}.take(12).map{it.id}.toMutableList()
                    dock=preferred.take(5).mapNotNull{word->apps.firstOrNull{it.component.packageName.lowercase(Locale.ROOT).contains(word)}}.distinctBy{it.id}.take(4).map{it.id}.toMutableList()
                    for(app in apps){if(dock.size>=4)break;if(app.id !in dock)dock.add(app.id)}
                    saveLists()
                }
                renderHome();filterApps(query)
            }
        }
    }
    private fun renderHome(){
        if(!::homeGrid.isInitialized)return
        val map=apps.associateBy{it.id};val pinned=favorites.mapNotNull{map[it]};val pageSize=columns*rows
        val pages=max(1,(pinned.size+pageSize-1)/pageSize);page=page.coerceIn(0,pages-1)
        homeGrid.removeAllViews();homeGrid.columnCount=columns;homeGrid.rowCount=rows
        pinned.drop(page*pageSize).take(pageSize).forEachIndexed { index,app->
            val tile=appTile(app,labels)
            homeGrid.addView(tile,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply{width=0;height=dp(if(labels)iconDp+38 else iconDp+23)})
        }
        dots.removeAllViews()
        for(i in 0 until pages){val dot=View(this).apply{background=rounded(if(i==page)white(240) else white(75),5,Color.TRANSPARENT);contentDescription="Home page ${i+1}"}
            touch(dot){page=i;renderHome()};dots.addView(dot,LinearLayout.LayoutParams(dp(if(i==page)18 else 6),dp(6)).apply{marginStart=dp(4);marginEnd=dp(4)})}
        dockView.removeAllViews()
        dock.mapNotNull{map[it]}.take(4).forEach { app->dockView.addView(appTile(app,false),LinearLayout.LayoutParams(0,-1,1f)) }
        space.text="${apps.size} apps · ${pinned.size} favorites"
    }
    private fun changePage(delta:Int){if(drawerOpen)return;val pageSize=columns*rows;val pages=max(1,(favorites.size+pageSize-1)/pageSize);page=(page+delta).coerceIn(0,pages-1)
        homeGrid.animate().cancel();homeGrid.alpha=.3f;homeGrid.translationX=dp(if(delta>0)18 else -18).toFloat();renderHome();homeGrid.animate().alpha(1f).translationX(0f).setDuration(180).start()}
    private fun appTile(app:LaunchApp,showLabel:Boolean):LinearLayout{
        val tile=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL or Gravity.TOP;setPadding(dp(3),dp(4),dp(3),dp(2));contentDescription="Open ${app.label}"}
        val icon=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;clipToOutline=true;outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,dp(17).toFloat())}}}
        tile.addView(icon,LinearLayout.LayoutParams(dp(iconDp),dp(iconDp)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        bindIcon(icon,app)
        if(showLabel)tile.addView(txt(app.label,11.5f,white(235)).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=TextUtils.TruncateAt.END;setShadowLayer(dp(3).toFloat(),0f,1f,0x44000000)},LinearLayout.LayoutParams(-1,dp(21)).apply{topMargin=dp(5)})
        touch(tile){launch(app)};tile.setOnLongClickListener{it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);appMenu(app);true};return tile
    }
    private fun bindIcon(view:ImageView,app:LaunchApp){
        view.tag=app.id;val cached=iconCache.get(app.id)
        if(cached!=null){view.setImageDrawable(cached.constantState?.newDrawable()?.mutate()?:cached);return}
        view.setImageDrawable(packageManager.defaultActivityIcon)
        if(!loadingIcons.add(app.id))return
        val ticket=generation
        worker.execute {
            val icon=try{app.info.loadIcon(packageManager)}catch(_:Exception){null}
            handler.post {
                loadingIcons.remove(app.id);if(isDestroyed||ticket!=generation)return@post
                if(icon!=null){iconCache.put(app.id,icon);refreshIconViews(root,app.id,icon)}
            }
        }
    }
    private fun refreshIconViews(view:View,id:String,drawable:Drawable){
        if(view is ImageView&&view.tag==id)view.setImageDrawable(drawable.constantState?.newDrawable()?.mutate()?:drawable)
        if(view is ViewGroup)for(i in 0 until view.childCount)refreshIconViews(view.getChildAt(i),id,drawable)
    }
    private fun launch(app:LaunchApp){try{
        val intent=Intent.makeMainActivity(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent);lastLaunch=app.id
    }catch(e:Exception){toast("${app.label} is unavailable");loadApps()}}
    private fun safeStart(intent:Intent){try{startActivity(intent)}catch(_:Exception){toast("This action is unavailable on your device")}}
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_SHORT).show()}

    private fun buildDrawer(){
        drawer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(Color.argb(248,16,25,46),28);setPadding(dp(20),dp(12),dp(20),dp(12));visibility=View.GONE;elevation=dp(20).toFloat()}
        drawer.setOnApplyWindowInsetsListener{v,i->v.setPadding(dp(20)+i.systemWindowInsetLeft,dp(12)+i.systemWindowInsetTop,dp(20)+i.systemWindowInsetRight,dp(12)+i.systemWindowInsetBottom);i}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(txt("App library",28f,white(),true),LinearLayout.LayoutParams(0,dp(48),1f))
        val close=txt("⌄",29f).apply{gravity=Gravity.CENTER;contentDescription="Close app library"};touch(close){closeDrawer()};header.addView(close,LinearLayout.LayoutParams(dp(44),dp(44)));drawer.addView(header)
        drawerCount=txt("Finding your apps…",12f,white(145));drawer.addView(drawerCount);drawer.gap(18)
        search=EditText(this).apply{isSingleLine=true;textSize=16f;hint="Search your apps";setTextColor(white());setHintTextColor(white(135));background=rounded(white(18),18);setPadding(dp(16),0,dp(16),0);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;contentDescription="Search installed apps";filters=arrayOf(android.text.InputFilter.LengthFilter(256))}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){query=s?.toString().orEmpty();filterApps(query)};override fun afterTextChanged(e:Editable?) {}})
        drawer.addView(search,LinearLayout.LayoutParams(-1,dp(50)));drawer.gap(20)
        grid=GridView(this).apply{numColumns=columns;verticalSpacing=dp(14);horizontalSpacing=dp(2);stretchMode=GridView.STRETCH_COLUMN_WIDTH;isVerticalScrollBarEnabled=false;clipToPadding=false;setPadding(0,dp(2),0,dp(8));selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)}
        adapter=AppAdapter();grid.adapter=adapter;drawer.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(drawer,FrameLayout.LayoutParams(-1,-1))
    }
    internal fun openDrawer(focus:Boolean=false){
        if(drawerOpen){if(focus)focusSearch();return};drawerOpen=true;root.enabledGestures=false
        drawer.animate().cancel();drawer.visibility=View.VISIBLE;drawer.alpha=0f;drawer.translationY=dp(80).toFloat()
        drawer.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        home.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        filterApps(query);if(focus)search.postDelayed({if(drawerOpen)focusSearch()},280)
    }
    private fun focusSearch(){search.requestFocus();(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT)}
    internal fun closeDrawer(){if(!::drawer.isInitialized||!drawerOpen)return;drawerOpen=false;root.enabledGestures=true
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken,0);search.clearFocus()
        home.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        drawer.animate().cancel();drawer.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(180).withEndAction{if(!drawerOpen)drawer.visibility=View.GONE}.start()
    }
    internal fun filterApps(value:String){if(!::adapter.isInitialized)return;matches=NativeBridge.search(apps.map{it.label+" "+it.component.packageName},value).map{apps[it]};drawerCount.text=if(value.isBlank())"${apps.size} apps · hold an icon to personalize" else "${matches.size} ${if(matches.size==1)"match" else "matches"}";adapter.notifyDataSetChanged()}
    private inner class AppAdapter:BaseAdapter(){
        override fun getCount()=matches.size
        override fun getItem(position:Int)=matches[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{
            // GridView recycles the visible cells; rebinding keeps touch targets and labels correct after search.
            val app=matches[position]
            val tile=convertView as? LinearLayout ?: appTile(app,true).apply{layoutParams=AbsListView.LayoutParams(-1,dp(iconDp+40))}
            val image=tile.getChildAt(0) as ImageView;bindIcon(image,app)
            (tile.getChildAt(1) as TextView).text=app.label
            tile.contentDescription="Open ${app.label}";tile.setOnClickListener{launch(app)};tile.setOnLongClickListener{it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);appMenu(app);true}
            return tile
        }
    }
    private fun appMenu(app:LaunchApp){
        sheet(app.label){body,dialog->
            option(body,if(app.id in favorites)"Remove from Home" else "Add to Home"){
                if(app.id in favorites)favorites.remove(app.id) else favorites.add(app.id);saveLists();renderHome();dialog.dismiss()
            }
            option(body,if(app.id in dock)"Remove from dock" else "Pin to dock"){
                if(app.id in dock)dock.remove(app.id) else {if(dock.size>=4)dock.removeAt(0);dock.add(app.id)};saveLists();renderHome();dialog.dismiss()
            }
            option(body,"App information"){safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.component.packageName}")));dialog.dismiss()}
            option(body,"Uninstall…"){safeStart(Intent(Intent.ACTION_DELETE,Uri.parse("package:${app.component.packageName}")));dialog.dismiss()}
        }
    }
    private fun option(body:LinearLayout,label:String,onTap:()->Unit){val row=txt(label,16f).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);background=rounded(white(10),16,Color.TRANSPARENT)};touch(row,onTap);body.addView(row,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(7)})}
    private fun sheet(title:String,content:(LinearLayout,Dialog)->Unit){
        val dialog=Dialog(this);val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(22),dp(20),dp(24));background=rounded(0xff182139.toInt(),28)}
        body.addView(txt(title,23f,white(),true));body.gap(18);content(body,dialog)
        val scroll=ScrollView(this).apply{addView(body);isFillViewport=false};dialog.setContentView(scroll)
        dialog.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setDimAmount(.45f);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setGravity(Gravity.BOTTOM);setLayout(-1,-2)}
        dialog.show();dialog.window?.setLayout(-1,min(dp(570),(resources.displayMetrics.heightPixels*.80f).toInt()))
    }
    private fun customize(){
        sheet("Make it yours"){body,dialog->
            option(body,"Wallpaper & color"){
                dialog.dismiss();sheet("Wallpaper & color"){colors,d->
                    listOf("Aurora · sapphire & lilac","Rose · warm dusk","Forest · deep teal","Midnight · AMOLED").forEachIndexed{i,name->option(colors,(if(i==palette)"✓  " else "")+name){palette=i;prefs.edit().putInt("palette",i).apply();root.getChildAt(0).invalidate();d.dismiss()}}
                    option(colors,"Choose a photo…"){d.dismiss();safeStartForImage()}
                    if(File(filesDir,"wallpaper.jpg").exists())option(colors,"Remove photo wallpaper"){File(filesDir,"wallpaper.jpg").delete();wallpaper=null;root.getChildAt(0).invalidate();d.dismiss()}
                }
            }
            option(body,"Home grid · $columns columns"){columns=if(columns==4)5 else 4;prefs.edit().putInt("columns",columns).apply();dialog.dismiss();buildUi()}
            option(body,"App labels · ${if(labels)"visible" else "hidden"}"){labels=!labels;prefs.edit().putBoolean("labels",labels).apply();dialog.dismiss();buildUi()}
            option(body,"Icon size · ${iconDp}"){iconDp=when(iconDp){48->56;56->64;else->48};prefs.edit().putInt("icon",iconDp).apply();dialog.dismiss();buildUi()}
            option(body,"Choose default launcher…"){dialog.dismiss();chooseDefault()}
            option(body,"About Sakura Home") {dialog.dismiss();sheet("Sakura Home 0.1.1"){info,_->info.addView(txt("Your apps. Your space.\n\nKotlin interface · Rust search\nNative search: ${if(NativeBridge.available)"available" else "Kotlin fallback"}\n\nNo root or APatch module is required.",15f,white(190)))}}
        }
    }
    private fun chooseDefault(){
        if(Build.VERSION.SDK_INT>=29){val role=getSystemService(RoleManager::class.java);if(role.isRoleAvailable(RoleManager.ROLE_HOME)&&!role.isRoleHeld(RoleManager.ROLE_HOME)){startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_HOME),52);return}}
        safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
    }
    private fun safeStartForImage(){try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),51)}catch(_:Exception){toast("No photo picker is available")}}
    @Deprecated("Activity document picker callback") override fun onActivityResult(request:Int,result:Int,data:Intent?){
        super.onActivityResult(request,result,data);if(request!=51||result!=RESULT_OK)return;val uri=data?.data?:return
        worker.execute{try{
            val incoming=File(filesDir,"wallpaper-import.tmp")
            contentResolver.openInputStream(uri).use{input->checkNotNull(input);incoming.outputStream().use{output->val buf=ByteArray(16384);var total=0;while(true){val count=input.read(buf);if(count<0)break;total+=count;check(total<=16*1024*1024){"Image exceeds 16 MiB"};output.write(buf,0,count)}}}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(incoming.path,bounds)
            check(bounds.outWidth>0&&bounds.outHeight>0){"Unsupported image"}
            var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
            val bitmap=checkNotNull(BitmapFactory.decodeFile(incoming.path,BitmapFactory.Options().apply{inSampleSize=sample}))
            val target=File(filesDir,"wallpaper-next.jpg");target.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};check(target.renameTo(File(filesDir,"wallpaper.jpg")))
            incoming.delete();handler.post{if(!isDestroyed){wallpaper=bitmap;root.getChildAt(0).invalidate();toast("Home wallpaper updated")}}
        }catch(e:Exception){handler.post{if(!isDestroyed)toast(e.message?:"Could not import that image")}}}
    }
    private fun loadWallpaper(){val path=File(filesDir,"wallpaper.jpg");if(path.exists())wallpaper=BitmapFactory.decodeFile(path.path)}

    internal fun smokeLaunchSettings(){launch(checkNotNull(apps.firstOrNull{it.component.packageName=="com.android.settings"}){"Settings app not visible"})}
    internal fun smokeIconsReady()=loadingIcons.isEmpty()
    internal fun smokeReady()=loaded&&apps.isNotEmpty()
    internal fun smokeSearch(value:String):Int {search.setText(value);return matches.size}
    internal fun smokeColumns()=grid.numColumns
    internal fun smokeHasMatch(value:String)=matches.all{it.label.contains(value,true)||it.component.packageName.contains(value,true)}
}

private class GestureRoot(context:Context,val gesture:(String)->Unit):FrameLayout(context){
    var enabledGestures=true
    private var downX=0f;private var downY=0f;private var captured=false
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{
        if(!enabledGestures)return super.dispatchTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;captured=false}
            MotionEvent.ACTION_MOVE->{val dx=event.x-downX;val dy=event.y-downY
                if(!captured&&max(abs(dx),abs(dy))>44*resources.displayMetrics.density){captured=true;val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel);cancel.recycle()}
                if(captured)return true
            }
            MotionEvent.ACTION_UP->{if(captured){val dx=event.x-downX;val dy=event.y-downY;captured=false;gesture(if(abs(dx)>abs(dy)){if(dx<0)"left" else "right"}else{if(dy<0)"up" else "down"});return true}}
            MotionEvent.ACTION_CANCEL->captured=false
        }
        return super.dispatchTouchEvent(event)
    }
}

private class WallpaperView(context:Context,val palette:()->Int,val photo:()->Bitmap?):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        val w=width.toFloat();val h=height.toFloat()
        val colors=when(palette()){
            1->intArrayOf(0xff43284f.toInt(),0xff8d526c.toInt(),0xff423762.toInt())
            2->intArrayOf(0xff092d38.toInt(),0xff196c73.toInt(),0xff173544.toInt())
            3->intArrayOf(0xff020408.toInt(),0xff0b1222.toInt(),0xff03050a.toInt())
            else->intArrayOf(0xff111b3b.toInt(),0xff344e83.toInt(),0xff5b527e.toInt())
        }
        paint.shader=LinearGradient(0f,0f,w,h,colors,null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,paint)
        val image=photo()
        if(image!=null&&!image.isRecycled){paint.shader=null;val scale=max(w/image.width,h/image.height);val dw=image.width*scale;val dh=image.height*scale;canvas.drawBitmap(image,null,RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),paint);canvas.drawColor(0x55000000);return}
        if(palette()!=3){
            paint.shader=RadialGradient(w*.85f,h*.36f,w*1.08f,intArrayOf(0x889ac1ff.toInt(),0x003b6fdd),null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,paint)
            paint.shader=RadialGradient(w*.14f,h*.82f,w*1.12f,intArrayOf(0x998c83df.toInt(),0x00693e92),null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,paint)
            val path=Path().apply{moveTo(-w*.2f,h*.78f);cubicTo(w*.25f,h*.08f,w*.9f,h*.98f,w*1.4f,h*.23f);lineTo(w*1.5f,h*1.2f);lineTo(-w*.2f,h*1.2f);close()}
            paint.shader=LinearGradient(0f,h*.3f,w,h,intArrayOf(0x005d9eed,0x555d91dc,0x997e75b8.toInt()),null,Shader.TileMode.CLAMP);canvas.drawPath(path,paint)
        }
        paint.shader=null
    }
}
