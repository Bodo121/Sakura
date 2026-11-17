package dev.sakura.home

import android.animation.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.*
import android.widget.LinearLayout
import kotlin.math.*

/** A finite, analytic damped response. Android's animator-duration policy still applies. */
internal object SpringCurve:TimeInterpolator {
    override fun getInterpolation(t:Float):Float = when {t<=0f->0f;t>=1f->1f;else->(1.0-exp(-9.0*t)*(cos(9.0*t)+sin(9.0*t))).toFloat()}
}
internal object FluidMotion {
    fun enabled(preference:Boolean)=preference&&ValueAnimator.areAnimatorsEnabled()
    fun installPress(view:View,enabled:()->Boolean,amount:Float=.95f){
        view.setOnTouchListener{v,e->
            val glass=v.background as? LiquidGlass
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{glass?.focus(e.x,e.y,1f);if(enabled()){v.animate().cancel();v.animate().setStartDelay(0).scaleX(amount).scaleY(amount).setDuration(105).setInterpolator(android.view.animation.DecelerateInterpolator()).start()}}
                MotionEvent.ACTION_MOVE->glass?.focus(e.x,e.y,1f)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{glass?.release(enabled());v.animate().cancel();if(enabled())v.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(SpringCurve).start()else{v.scaleX=1f;v.scaleY=1f}}
            };false
        }
    }
    fun reset(v:View){v.animate().cancel();v.animate().setStartDelay(0);v.alpha=1f;v.scaleX=1f;v.scaleY=1f;v.translationX=0f;v.translationY=0f}
}

/** Cached, blurred wallpaper sampling plus edge refraction and finite moving light. Text is never blurred. */
internal class LiquidGlass(
    private val host:View,
    radiusDp:Float,
    private val backdrop:()->Bitmap?,
    private val wallpaperSize:()->Pair<Int,Int>,
    private val enabled:()->Boolean,
    private val panel:Boolean=false,
):Drawable(){
    private val density=host.resources.displayMetrics.density
    private val radius=radiusDp*density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    private val path=Path()
    private val location=IntArray(2)
    private val matrix=Matrix()
    private var texture:Bitmap?=null
    private var textureShader:BitmapShader?=null
    private var lightX=0f;private var lightY=0f;private var pressure=0f
    private var fade:ValueAnimator?=null
    var entrance=1f
        set(value){field=value;invalidateSelf()}
    override fun onBoundsChange(bounds:Rect){rect.set(bounds);lightX=rect.width()*.2f;lightY=rect.height()*.15f}
    fun focus(x:Float,y:Float,amount:Float){fade?.cancel();lightX=x;lightY=y;pressure=amount;invalidateSelf()}
    fun release(animate:Boolean){fade?.cancel();if(!animate){pressure=0f;invalidateSelf();return};fade=ValueAnimator.ofFloat(pressure,0f).apply{duration=300;addUpdateListener{pressure=it.animatedValue as Float;invalidateSelf()};start()}}
    override fun draw(c:Canvas){
        if(rect.isEmpty)return
        val active=enabled()
        val image=if(active&&!panel)backdrop() else null
        c.save();path.reset();path.addRoundRect(rect,radius,radius,Path.Direction.CW);c.clipPath(path)
        p.style=Paint.Style.FILL;p.shader=null
        if(image!=null&&!image.isRecycled){
            if(texture!==image){texture=image;textureShader=BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
            val (width,height)=wallpaperSize();host.getLocationInWindow(location)
            matrix.setScale(width.toFloat()/image.width,height.toFloat()/image.height)
            matrix.postTranslate(-location[0].toFloat(),-location[1].toFloat())
            matrix.postScale(1.025f+pressure*.014f,1.025f+pressure*.014f,rect.centerX(),rect.centerY())
            textureShader?.setLocalMatrix(matrix);p.shader=textureShader;p.alpha=245;c.drawRoundRect(rect,radius,radius,p)
        }
        p.alpha=255
        val colors=if(panel){
            if(active)intArrayOf(0xb52a3c60.toInt(),0xb4111933.toInt(),0xc5222448.toInt()) else intArrayOf(0xfa17223a.toInt(),0xfc10182c.toInt())
        }else if(active){intArrayOf(0x34e4f3ff,0x0c8ea6eb,0x35d9c8f1)}else{intArrayOf(0x684c6384,0x68657298)}
        p.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,colors,null,Shader.TileMode.CLAMP);c.drawRoundRect(rect,radius,radius,p)
        // Soft upper reflection and a cyan/lilac dispersion rim, not a white opaque border.
        p.shader=LinearGradient(0f,rect.top,0f,rect.bottom,intArrayOf(0x21ffffff,0x00ffffff,0x08d3dcff),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(rect,radius,radius,p)
        if(pressure>.001f){p.shader=RadialGradient(lightX,lightY,max(rect.width()*.65f,1f),intArrayOf(Color.argb((38*pressure).toInt(),255,255,255),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRoundRect(rect,radius,radius,p)}
        val flash=sin(entrance.coerceIn(0f,1f)*Math.PI).toFloat()
        if(flash>.01f){val x=rect.width()*entrance;p.shader=LinearGradient(x-rect.width()*.35f,0f,x+rect.width()*.35f,rect.height(),intArrayOf(Color.TRANSPARENT,Color.argb((22*flash).toInt(),221,241,255),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRoundRect(rect,radius,radius,p)}
        c.restore()
        p.style=Paint.Style.STROKE;p.strokeWidth=density*.9f
        val rim=RectF(rect).apply{inset(p.strokeWidth/2,p.strokeWidth/2)}
        p.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,intArrayOf(0xa5f4fcff.toInt(),0x21bceeff,0x0affffff,0x72e7c7ff),floatArrayOf(0f,.28f,.62f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(rim,radius,radius,p)
        p.strokeWidth=density*.5f;p.shader=null;p.color=0x2297dbff;c.drawRoundRect(RectF(rim).apply{inset(density*1.5f,density*1.5f)},radius,radius,p)
        p.style=Paint.Style.FILL;p.shader=null;p.alpha=255
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Header/overscroll pull-down gesture coexists with the app library's native scrolling. */
internal class DrawerSurface(context:Context):LinearLayout(context){
    init{isClickable=true;isFocusableInTouchMode=true}
    var allowPull:()->Boolean={false}
    var onPull:(Float,Boolean,Float)->Unit={_,_,_->}
    private var startY=0f;private var startX=0f;private var captured=false
    private var tracker:VelocityTracker?=null
    override fun dispatchTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{startY=e.y;startX=e.x;captured=false;tracker?.recycle();tracker=VelocityTracker.obtain();tracker?.addMovement(e)}
            MotionEvent.ACTION_MOVE->{tracker?.addMovement(e);val dy=e.y-startY
                if(!captured&&dy>14*resources.displayMetrics.density&&dy>abs(e.x-startX)*1.2f&&(startY<190*resources.displayMetrics.density||allowPull())){
                    captured=true;val cancel=MotionEvent.obtain(e);cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel);cancel.recycle()
                }
                if(captured){onPull((1f-dy/(height*.55f)).coerceIn(0f,1f),false,0f);return true}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                tracker?.addMovement(e);tracker?.computeCurrentVelocity(1000);val velocity=tracker?.yVelocity?:0f;tracker?.recycle();tracker=null
                if(captured){captured=false;onPull((1f-(e.y-startY)/(height*.55f)).coerceIn(0f,1f),true,if(e.actionMasked==MotionEvent.ACTION_CANCEL)-10000f else velocity);return true}
            }
        }
        return super.dispatchTouchEvent(e)
    }
}
