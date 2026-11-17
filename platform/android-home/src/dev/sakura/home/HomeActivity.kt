package dev.sakura.home

import android.app.Activity
import android.app.Dialog
import android.app.ActivityOptions
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
    private var motionEnabled=true
    private var glassEnabled=true
    private var powerSaving=false
    private var drawerProgress=0f
    private var drawerAnimator:ValueAnimator?=null
    private var drawerFocusRequested=false
    private var wasLaunched=false
    private var backdrop:Bitmap?=null
    private var backdropEpoch=0
    private val backdropRefresh=Runnable{refreshBackdrop()}
    private val blurEffects=HashMap<Int,RenderEffect>()
    private var blurStep=-1
    private lateinit var scene:FrameLayout
    private lateinit var wallpaperLayer:WallpaperView
    private lateinit var root:GestureRoot
    private lateinit var home:LinearLayout
    private lateinit var homeGrid:GridLayout
    private lateinit var dockView:LinearLayout
    private lateinit var dots:LinearLayout
    private lateinit var clock:TextView
    private lateinit var date:TextView
    private lateinit var space:TextView
    private lateinit var drawer:DrawerSurface
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
        motionEnabled=prefs.getBoolean("fluid_motion",true);glassEnabled=prefs.getBoolean("liquid_glass",true)
        powerSaving=(getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode
        loadWallpaper();buildUi();loadApps()
        val filter=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(packages,filter)
        android.util.Log.i("SakuraHome","Kotlin activity created; nativeSearch=${NativeBridge.available}")
    }
    override fun onResume(){super.onResume();resumed=true;handler.removeCallbacks(ticker);handler.post(ticker)
        powerSaving=(getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode
        if(::scene.isInitialized){applyDrawer(drawerProgress);if(wasLaunched&&canAnimate()){val view=if(drawerOpen)drawer else home;view.alpha=.7f;view.translationY=dp(10).toFloat();view.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(SpringCurve).start()};wasLaunched=false}
    }
    override fun onPause(){resumed=false;handler.removeCallbacks(ticker);drawerAnimator?.cancel();if(::drawer.isInitialized)applyDrawer(if(drawerOpen)1f else 0f);super.onPause()}
    override fun onDestroy(){unregisterReceiver(packages);generation++;backdropEpoch++;drawerAnimator?.cancel();blurEffects.clear();worker.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy()}
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
    private fun canAnimate()=FluidMotion.enabled(motionEnabled)&&!powerSaving
    private fun glassActive()=glassEnabled&&!powerSaving
    private fun glass(view:View,radius:Float=24f,panel:Boolean=false)=LiquidGlass(view,radius,{backdrop},{root.width to root.height},{glassActive()},panel)
    private fun touch(v:View,action:()->Unit){
        v.isClickable=true;v.isFocusable=true;v.setOnClickListener{action()}
        FluidMotion.installPress(v,{canAnimate()},if(v is TextView).96f else .94f)
    }
    private fun refreshBackdrop(){
        if(!::wallpaperLayer.isInitialized)return
        wallpaperLayer.invalidate();val ticket=++backdropEpoch
        if(!glassActive()||wallpaperLayer.width==0||wallpaperLayer.height==0){backdrop=null;root.invalidate();return}
        val scale=min(1f,640f/wallpaperLayer.height)
        val width=max(1,(wallpaperLayer.width*scale).toInt());val height=max(1,(wallpaperLayer.height*scale).toInt())
        val raw=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(raw);canvas.scale(scale,scale);wallpaperLayer.draw(canvas)
        worker.execute{
            val pixels=IntArray(width*height);raw.getPixels(pixels,0,width,0,0,width,height)
            val blurred=NativeBridge.blurBackdrop(pixels,width,height)
            val image=if(blurred!=null)Bitmap.createBitmap(blurred,width,height,Bitmap.Config.ARGB_8888)else raw
            handler.post{if(!isDestroyed&&ticket==backdropEpoch){backdrop=image;invalidateGlass(root)}}
        }
    }
    private fun invalidateGlass(view:View){view.background?.invalidateSelf();if(view is ViewGroup)for(i in 0 until view.childCount)invalidateGlass(view.getChildAt(i))}
    private fun LinearLayout.gap(n:Int){addView(View(this@HomeActivity),LinearLayout.LayoutParams(1,dp(n)))}
    private fun readList(key:String):MutableList<String> = try{val a=JSONArray(prefs.getString(key,"[]"));MutableList(a.length()){a.getString(it)}}catch(_:Exception){mutableListOf()}
    private fun saveLists(){prefs.edit().putString("favorites",JSONArray(favorites).toString()).putString("dock",JSONArray(dock).toString()).apply()}
    private fun buildUi(){
        drawerAnimator?.cancel();drawerProgress=0f;drawerOpen=false;blurStep=-1
        root=GestureRoot(this){direction->when(direction){"up"->openDrawer();"down"->customize();"left"->changePage(1);"right"->changePage(-1)}}
        root.onDrawerDrag={progress,released,velocity->
            prepareDrawer();applyDrawer(progress)
            if(released)animateDrawer(progress>.24f||velocity< -750f)
        }
        scene=FrameLayout(this).apply{clipChildren=false}
        wallpaperLayer=WallpaperView(this,{palette},{wallpaper})
        scene.addView(wallpaperLayer,FrameLayout.LayoutParams(-1,-1))
        root.addView(scene,FrameLayout.LayoutParams(-1,-1))
        root.addOnLayoutChangeListener{_,l,t,r,b,ol,ot,or,ob->if(r-l!=or-ol||b-t!=ob-ot){handler.removeCallbacks(backdropRefresh);handler.postDelayed(backdropRefresh,120)}}
        root.setOnLongClickListener{customize();true}
        home=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(20))}
        scene.addView(home,FrameLayout.LayoutParams(-1,-1))
        home.setOnApplyWindowInsetsListener{v,insets->
            v.setPadding(dp(22)+insets.systemWindowInsetLeft,dp(12)+insets.systemWindowInsetTop,dp(22)+insets.systemWindowInsetRight,dp(10)+insets.systemWindowInsetBottom)
            insets
        }
        val brand=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        brand.addView(txt("✿  S A K U R A",12f,white(210),true),LinearLayout.LayoutParams(0,dp(32),1f).apply{gravity=Gravity.CENTER_VERTICAL})
        val edit=txt("＋",27f).apply{gravity=Gravity.CENTER;background=glass(this,18f);contentDescription="Customize home"}
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
                orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));background=glass(this,24f)
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
        val searchPill=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),0,dp(16),0);background=glass(this,28f)}
        searchPill.addView(txt("⌕",28f,white(215)),LinearLayout.LayoutParams(dp(30),-2))
        searchPill.addView(txt("Search apps",14f,white(200)),LinearLayout.LayoutParams(0,-2,1f))
        searchPill.addView(txt("•••",16f,white(180)))
        touch(searchPill){openDrawer(focus=true)};home.addView(searchPill,LinearLayout.LayoutParams(-1,dp(46)))
        home.gap(12)
        dockView=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(8),dp(8),dp(8));background=glass(this,30f)}
        home.addView(dockView,LinearLayout.LayoutParams(-1,dp(78)))
        root.post {
            val available=(home.height-home.paddingTop-home.paddingBottom)/resources.displayMetrics.density
            rows=if(available>=790)3 else 2
            hero.visibility=if(available<600)View.GONE else View.VISIBLE
            if(loaded)renderHome()
            refreshBackdrop()
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
        homeGrid.animate().cancel();homeGrid.alpha=.3f;homeGrid.translationX=dp(if(delta>0)18 else -18).toFloat();renderHome();homeGrid.animate().alpha(1f).translationX(0f).setDuration(if(canAnimate())380 else 0).setInterpolator(SpringCurve).start()}
    private fun appTile(app:LaunchApp,showLabel:Boolean):LinearLayout{
        val tile=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL or Gravity.TOP;setPadding(dp(3),dp(4),dp(3),dp(2));contentDescription="Open ${app.label}"}
        val icon=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;clipToOutline=true;outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,dp(17).toFloat())}}}
        tile.addView(icon,LinearLayout.LayoutParams(dp(iconDp),dp(iconDp)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        bindIcon(icon,app)
        if(showLabel)tile.addView(txt(app.label,11.5f,white(235)).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=TextUtils.TruncateAt.END;setShadowLayer(dp(3).toFloat(),0f,1f,0x44000000)},LinearLayout.LayoutParams(-1,dp(21)).apply{topMargin=dp(5)})
        touch(tile){launch(app,tile)}
        FluidMotion.installPress(tile,{canAnimate()},.90f);tile.setOnLongClickListener{it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);appMenu(app);true};return tile
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
    private fun launch(app:LaunchApp,source:View?=null){try{
        val intent=Intent.makeMainActivity(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val icon=(source as? ViewGroup)?.getChildAt(0)?:source
        val options=if(icon!=null&&canAnimate())ActivityOptions.makeScaleUpAnimation(icon,0,0,icon.width.coerceAtLeast(1),icon.height.coerceAtLeast(1)).toBundle()else null
        wasLaunched=true;startActivity(intent,options);lastLaunch=app.id
    }catch(e:Exception){toast("${app.label} is unavailable");loadApps()}}
    private fun safeStart(intent:Intent){try{startActivity(intent)}catch(_:Exception){toast("This action is unavailable on your device")}}
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_SHORT).show()}

    private fun buildDrawer(){
        drawer=DrawerSurface(this).apply{orientation=LinearLayout.VERTICAL;background=glass(this,32f,true);setPadding(dp(20),dp(12),dp(20),dp(12));visibility=View.GONE;elevation=dp(20).toFloat()}
        drawer.setOnApplyWindowInsetsListener{v,i->v.setPadding(dp(20)+i.systemWindowInsetLeft,dp(12)+i.systemWindowInsetTop,dp(20)+i.systemWindowInsetRight,dp(12)+i.systemWindowInsetBottom);i}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(txt("App library",28f,white(),true),LinearLayout.LayoutParams(0,dp(48),1f))
        val close=txt("⌄",29f).apply{gravity=Gravity.CENTER;contentDescription="Close app library"};touch(close){closeDrawer()};header.addView(close,LinearLayout.LayoutParams(dp(44),dp(44)));drawer.addView(header)
        drawerCount=txt("Finding your apps…",12f,white(145));drawer.addView(drawerCount);drawer.gap(18)
        search=EditText(this).apply{isSingleLine=true;textSize=16f;hint="Search your apps";setTextColor(white());setHintTextColor(white(135));background=glass(this,18f);setPadding(dp(16),0,dp(16),0);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;contentDescription="Search installed apps";filters=arrayOf(android.text.InputFilter.LengthFilter(256))}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){query=s?.toString().orEmpty();filterApps(query)};override fun afterTextChanged(e:Editable?) {}})
        drawer.addView(search,LinearLayout.LayoutParams(-1,dp(50)));drawer.gap(20)
        grid=GridView(this).apply{numColumns=columns;verticalSpacing=dp(14);horizontalSpacing=dp(2);stretchMode=GridView.STRETCH_COLUMN_WIDTH;isVerticalScrollBarEnabled=false;clipToPadding=false;setPadding(0,dp(2),0,dp(8));selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)}
        adapter=AppAdapter();grid.adapter=adapter;drawer.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        drawer.allowPull={::grid.isInitialized&&!grid.canScrollVertically(-1)&&!search.hasFocus()}
        drawer.onPull={progress,released,velocity->
            drawerAnimator?.cancel();applyDrawer(progress)
            if(released)animateDrawer(if(velocity< -750f)true else if(velocity>750f)false else progress>.72f)
        }
        root.addView(drawer,FrameLayout.LayoutParams(-1,-1).apply{setMargins(dp(7),dp(7),dp(7),dp(7))})
    }
    private fun prepareDrawer(){
        if(drawer.visibility!=View.VISIBLE){drawer.visibility=View.VISIBLE;filterApps(query);home.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
    }
    private fun applyDrawer(progress:Float){
        drawerProgress=progress
        val p=progress.coerceIn(0f,1f)
        drawer.alpha=(p*2.4f).coerceIn(0f,1f)
        drawer.translationY=(1f-progress)*root.height*.24f
        drawer.scaleX=.96f+.04f*progress;drawer.scaleY=.96f+.04f*progress
        scene.scaleX=1f-.055f*p;scene.scaleY=1f-.055f*p
        scene.translationY=-dp(12)*p;scene.alpha=1f-.1f*p
        (drawer.background as? LiquidGlass)?.entrance=p
        if(Build.VERSION.SDK_INT>=31){
            val step=if(glassActive())(p*16).roundToInt()else 0
            if(step!=blurStep){blurStep=step
                scene.setRenderEffect(if(step==0)null else blurEffects.getOrPut(step){val radius=dp(18)*step/16f;RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)})
            }
        }
        if(p<=.001f&&!drawerOpen){drawer.visibility=View.GONE;home.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO}
    }
    private fun animateDrawer(open:Boolean){
        drawerAnimator?.cancel();drawerOpen=open;root.enabledGestures=false
        if(open)prepareDrawer() else {(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken,0);search.clearFocus()}
        val target=if(open)1f else 0f
        fun settle(){applyDrawer(target);root.enabledGestures=!open;if(open){revealCells();if(drawerFocusRequested){drawerFocusRequested=false;focusSearch()}}}
        if(!canAnimate()){settle();return}
        drawerAnimator=ValueAnimator.ofFloat(drawerProgress,target).apply{
            duration=if(open)560 else 420;interpolator=SpringCurve
            addUpdateListener{applyDrawer(it.animatedValue as Float)}
            var cancelled=false
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationCancel(a:Animator){cancelled=true};override fun onAnimationEnd(a:Animator){if(!cancelled)settle()}})
            start()
        }
    }
    private fun revealCells(){
        if(!drawerOpen||!canAnimate())return
        grid.post{
            if(!drawerOpen)return@post
            for(i in 0 until grid.childCount){val view=grid.getChildAt(i);FluidMotion.reset(view);view.alpha=0f;view.translationY=dp(18).toFloat();view.scaleX=.9f;view.scaleY=.9f
                view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(min(120,(i/columns)*24+(i%columns)*10).toLong()).setDuration(380).setInterpolator(SpringCurve).start()
            }
        }
    }
    internal fun openDrawer(focus:Boolean=false){
        drawerFocusRequested=focus
        if(drawerOpen&&drawerProgress>=.999f&&drawerAnimator?.isRunning!=true){if(focus)focusSearch();return}
        prepareDrawer();animateDrawer(true)
    }
    private fun focusSearch(){search.requestFocus();(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT)}
    internal fun closeDrawer(){if(!::drawer.isInitialized)return;drawerFocusRequested=false
        if(!drawerOpen&&drawerProgress<=.001f&&drawer.visibility!=View.VISIBLE){root.enabledGestures=true;return}
        animateDrawer(false)
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
            FluidMotion.reset(tile)
            val image=tile.getChildAt(0) as ImageView;bindIcon(image,app)
            (tile.getChildAt(1) as TextView).text=app.label
            tile.contentDescription="Open ${app.label}";tile.setOnClickListener{launch(app,tile)};tile.setOnLongClickListener{it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);appMenu(app);true}
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
    private fun option(body:LinearLayout,label:String,onTap:()->Unit){val row=txt(label,16f).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);background=glass(this,16f,true)};touch(row,onTap);body.addView(row,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(7)})}
    private fun sheet(title:String,content:(LinearLayout,Dialog)->Unit){
        val dialog=Dialog(this);val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(22),dp(20),dp(24));background=LiquidGlass(this,28f,{null},{root.width to root.height},{glassActive()&&Build.VERSION.SDK_INT>=31&&getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled},true)}
        body.addView(txt(title,23f,white(),true));body.gap(18);content(body,dialog)
        val scroll=ScrollView(this).apply{addView(body);isFillViewport=false};dialog.setContentView(scroll)
        dialog.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setDimAmount(.45f);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setGravity(Gravity.BOTTOM);setLayout(-1,-2)}
        dialog.show()
        dialog.window?.let { w->
            if(Build.VERSION.SDK_INT>=31&&glassActive()&&getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.attributes=w.attributes.apply{setBlurBehindRadius(dp(22))}}
            if(canAnimate()){body.alpha=0f;body.translationY=dp(48).toFloat();body.scaleX=.97f;body.scaleY=.97f;body.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(440).setInterpolator(SpringCurve).start()}
        }
        dialog.window?.setLayout(-1,min(dp(570),(resources.displayMetrics.heightPixels*.80f).toInt()))
    }
    private fun customize(){
        sheet("Make it yours"){body,dialog->
            option(body,"Wallpaper & color"){
                dialog.dismiss();sheet("Wallpaper & color"){colors,d->
                    listOf("Aurora · sapphire & lilac","Rose · warm dusk","Forest · deep teal","Midnight · AMOLED").forEachIndexed{i,name->option(colors,(if(i==palette)"✓  " else "")+name){palette=i;prefs.edit().putInt("palette",i).apply();refreshBackdrop();d.dismiss()}}
                    option(colors,"Choose a photo…"){d.dismiss();safeStartForImage()}
                    if(File(filesDir,"wallpaper.jpg").exists())option(colors,"Remove photo wallpaper"){File(filesDir,"wallpaper.jpg").delete();wallpaper=null;refreshBackdrop();d.dismiss()}
                }
            }
            option(body,"Home grid · $columns columns"){columns=if(columns==4)5 else 4;prefs.edit().putInt("columns",columns).apply();dialog.dismiss();buildUi()}
            option(body,"App labels · ${if(labels)"visible" else "hidden"}"){labels=!labels;prefs.edit().putBoolean("labels",labels).apply();dialog.dismiss();buildUi()}
            option(body,"Icon size · ${iconDp}"){iconDp=when(iconDp){48->56;56->64;else->48};prefs.edit().putInt("icon",iconDp).apply();dialog.dismiss();buildUi()}
            option(body,"Fluid motion · ${if(motionEnabled)"on" else "off"}"){motionEnabled=!motionEnabled;prefs.edit().putBoolean("fluid_motion",motionEnabled).apply();drawerAnimator?.cancel();applyDrawer(if(drawerOpen)1f else 0f);dialog.dismiss()}
            option(body,"Liquid glass · ${if(glassEnabled)"on" else "off"}"){glassEnabled=!glassEnabled;prefs.edit().putBoolean("liquid_glass",glassEnabled).apply();refreshBackdrop();blurStep=-1;applyDrawer(drawerProgress);invalidateGlass(root);dialog.dismiss()}
            option(body,"Choose default launcher…"){dialog.dismiss();chooseDefault()}
            option(body,"About Sakura Home") {dialog.dismiss();sheet("Sakura Home 0.1.2"){info,_->info.addView(txt("Your apps. Your space.\n\nKotlin interface · Rust search\nNative search: ${if(NativeBridge.available)"available" else "Kotlin fallback"}\n\nNo root or APatch module is required.",15f,white(190)))}}
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
            incoming.delete();handler.post{if(!isDestroyed){wallpaper=bitmap;refreshBackdrop();toast("Home wallpaper updated")}}
        }catch(e:Exception){handler.post{if(!isDestroyed)toast(e.message?:"Could not import that image")}}}
    }
    private fun loadWallpaper(){val path=File(filesDir,"wallpaper.jpg");if(path.exists())wallpaper=BitmapFactory.decodeFile(path.path)}

    internal fun smokeDrawerSettled(open:Boolean)=drawerAnimator?.isRunning!=true&&abs(drawerProgress-(if(open)1f else 0f))<.005f
    internal fun smokeMotion(value:Boolean){motionEnabled=value}
    internal fun smokeGlass(value:Boolean){glassEnabled=value;blurStep=-1;applyDrawer(drawerProgress);invalidateGlass(root)}
    internal fun smokeBackdropReady()=backdrop!=null
    internal fun smokeBackdropBlurred()=Build.VERSION.SDK_INT>=31&&blurStep>0
    internal fun smokeDrag(value:Float,release:Boolean){prepareDrawer();drawerAnimator?.cancel();applyDrawer(value);if(release)animateDrawer(value>.5f)}
    internal fun smokeLaunchSettings(){launch(checkNotNull(apps.firstOrNull{it.component.packageName=="com.android.settings"}){"Settings app not visible"})}
    internal fun smokeIconsReady()=loadingIcons.isEmpty()
    internal fun smokeReady()=loaded&&apps.isNotEmpty()
    internal fun smokeSearch(value:String):Int {search.setText(value);return matches.size}
    internal fun smokeColumns()=grid.numColumns
    internal fun smokeHasMatch(value:String)=matches.all{it.label.contains(value,true)||it.component.packageName.contains(value,true)}
}

private class GestureRoot(context:Context,val gesture:(String)->Unit):FrameLayout(context){
    var enabledGestures=true
    var onDrawerDrag:(Float,Boolean,Float)->Unit={_,_,_->}
    private var downX=0f;private var downY=0f;private var captured=false;private var drawerDrag=false
    private var tracker:VelocityTracker?=null
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        if(!enabledGestures&&!captured)return super.dispatchTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;captured=false;drawerDrag=false;tracker?.recycle();tracker=VelocityTracker.obtain();tracker?.addMovement(event)}
            MotionEvent.ACTION_MOVE->{tracker?.addMovement(event);val dx=event.x-downX;val dy=event.y-downY
                if(!captured&&max(abs(dx),abs(dy))>14*resources.displayMetrics.density){
                    captured=true;drawerDrag=dy<0&&abs(dy)>abs(dx)*1.2f
                    val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel);cancel.recycle()
                }
                if(captured){if(drawerDrag)onDrawerDrag((-dy/(height*.55f)).coerceIn(0f,1.04f),false,0f);return true}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                tracker?.addMovement(event);tracker?.computeCurrentVelocity(1000);val vy=tracker?.yVelocity?:0f;tracker?.recycle();tracker=null
                if(captured){captured=false;val dx=event.x-downX;val dy=event.y-downY
                    if(drawerDrag)onDrawerDrag(if(event.actionMasked==MotionEvent.ACTION_CANCEL)0f else (-dy/(height*.55f)).coerceIn(0f,1f),true,if(event.actionMasked==MotionEvent.ACTION_CANCEL)0f else vy)
                    else if(event.actionMasked!=MotionEvent.ACTION_CANCEL&&max(abs(dx),abs(dy))>36*resources.displayMetrics.density)gesture(if(abs(dx)>abs(dy)){if(dx<0)"left" else "right"}else "down")
                    return true
                }
            }
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
