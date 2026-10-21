package com.kamal.tvremote

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import android.animation.TimeInterpolator
import android.provider.Settings
import android.widget.TextView

/**
 * Motion, following Emil Kowalski's rules (animations.dev): a strong ease-out
 * curve, short durations, transform and opacity only, nothing from scale 0.
 */
val EASE_OUT: TimeInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
val EASE_IN_OUT: TimeInterpolator = PathInterpolator(0.77f, 0f, 0.175f, 1f)

fun View.animsOn(): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Subtle entrance: fade in while rising a few dp, staggered by index. */
fun View.enterFx(index: Int = 0) {
    if (!animsOn()) return
    post {
        alpha = 0f
        translationY = context.dp(10f).toFloat()
        animate().alpha(1f).translationY(0f)
            .setInterpolator(EASE_OUT)
            .setStartDelay((index * 35).coerceAtMost(210).toLong())
            .setDuration(240)
            .start()
    }
}

/** Small settle pop, used for the selected bottom-nav icon. */
fun View.popFx() {
    if (!animsOn()) return
    post {
        scaleX = 0.82f
        scaleY = 0.82f
        animate().scaleX(1f).scaleY(1f).setInterpolator(EASE_OUT).setDuration(220).start()
    }
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
).toInt()

fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(ctx.dp(1f), strokeColor)
    }

fun gradient(top: Int, bottom: Int, radiusDp: Float, ctx: Context): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

fun oval(color: Int): GradientDrawable =
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun Context.rippleKey(color: Int, radiusDp: Float): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
        rounded(color, radiusDp, this),
        null
    )

private val MEDIUM: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

fun Context.label(
    text: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
    gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    this.gravity = gravity
    // "bold" in this codebase means emphasis — rendered as sans-serif-medium.
    if (bold) typeface = MEDIUM
}

/** 1dp hairline divider at 12% white — the only "stroke" in the new design. */
fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(Color.argb(31, 255, 255, 255))
    layoutParams = android.view.ViewGroup.LayoutParams(-1, dp(1f))
}

/**
 * Edge-to-edge insets: pads the root with the status-bar height at the top
 * and the navigation-bar height at the bottom, preserving its own padding.
 */
fun View.applySystemInsets() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        v.setPadding(
            baseLeft + insets.systemWindowInsetLeft,
            baseTop + insets.systemWindowInsetTop,
            baseRight + insets.systemWindowInsetRight,
            baseBottom + insets.systemWindowInsetBottom
        )
        insets
    }
    requestApplyInsets()
}

fun View.tap(action: () -> Unit) {
    // Press feedback: quick scale to 0.96 and back (150ms total), without
    // consuming the touch so the click still fires normally.
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(EASE_OUT).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(EASE_OUT).start()
        }
        false
    }
    setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }
}
