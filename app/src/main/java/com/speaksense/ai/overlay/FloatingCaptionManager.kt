package com.speaksense.ai.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.speaksense.ai.data.AppSettings
import com.speaksense.ai.data.SettingsRepository
import com.speaksense.ai.model.SessionPhase
import com.speaksense.ai.model.SessionUiState

/**
 * Floating caption window drawn over the call screen with TYPE_APPLICATION_OVERLAY.
 * Shows only recognised speech (plus one short hint/error line when captions cannot run),
 * a red dot as recording indicator while the microphone is on, and Pause/Resume/Close controls.
 * Plain Views are used on purpose: Compose inside a service-owned window needs lifecycle owners
 * and is much more fragile.
 */
class FloatingCaptionManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    interface Controls {
        fun onStart()
        fun onPause()
        fun onResume()
        fun onClose()
    }

    @Volatile
    var controls: Controls? = null

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var actionButton: TextView? = null
    private var scrollView: MaxHeightScrollView? = null
    private var captionText: TextView? = null
    private var background: GradientDrawable? = null

    private var settings = AppSettings()
    private var state = SessionUiState()

    val isShowing: Boolean get() = root != null

    // ---- public API (safe from any thread) ------------------------------------------------

    fun show() = onMain { showInternal() }

    fun hide() = onMain { hideInternal() }

    fun applySettings(newSettings: AppSettings) = onMain {
        settings = newSettings
        applySettingsInternal()
    }

    fun render(newState: SessionUiState) = onMain {
        state = newState
        renderInternal()
    }

    // ---- internals (main thread) ----------------------------------------------------------

    private fun showInternal() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            val view = buildView()
            val layoutParams = buildParams()
            windowManager.addView(view, layoutParams)
            root = view
            params = layoutParams
            applySettingsInternal()
            renderInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Could not show caption overlay", e)
            root = null
            params = null
        }
    }

    private fun hideInternal() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        root = null
        params = null
        titleView = null
        actionButton = null
        scrollView = null
        captionText = null
        background = null
    }

    private fun buildView(): LinearLayout {
        val bg = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
        background = bg

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            this.background = bg
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            setTextColor(GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            contentDescription = "SpeakSense AI live captions. Drag to move."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = title

        val action = pillButton("Start") { onActionClicked() }
        actionButton = action

        val close = pillButton("Close") { controls?.onClose() }.apply { contentDescription = "Close captions" }

        header.addView(title)
        header.addView(action)
        header.addView(close)
        attachDrag(header)
        attachDrag(title)

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
            textSize = 24f
        }
        captionText = text

        val scroll = MaxHeightScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(text)
        }
        scrollView = scroll

        container.addView(header)
        container.addView(scroll)
        return container
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor("#04210F"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(40)
        minWidth = dp(64)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        this.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(GREEN)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(8)
        layoutParams = lp
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun onActionClicked() {
        when (state.phase) {
            SessionPhase.LISTENING -> controls?.onPause()
            SessionPhase.PAUSED -> controls?.onResume()
            SessionPhase.AWAITING_START, SessionPhase.ERROR -> controls?.onStart()
            else -> Unit
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            computeWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (settings.overlayX >= 0) settings.overlayX else (screenWidth() - p.width) / 2
        p.y = if (settings.overlayY >= 0) settings.overlayY else dp(96)
        clamp(p)
        return p
    }

    private fun applySettingsInternal() {
        val view = root ?: return
        val p = params ?: return
        captionText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp.toFloat())
        background?.setColor(Color.argb(settings.captionOpacityPercent * 255 / 100, 10, 16, 14))
        p.width = computeWidth()
        if (settings.overlayX >= 0) p.x = settings.overlayX
        if (settings.overlayY >= 0) p.y = settings.overlayY
        clamp(p)
        scrollView?.maxHeightPx = (screenHeight() * 0.38f).toInt()
        updateLayout(view, p)
    }

    private fun renderInternal() {
        val captionView = captionText ?: return
        val sb = SpannableStringBuilder()
        val hasPartial = state.partial.isNotBlank()
        val lines = state.lines.takeLast(MAX_VISIBLE_LINES)
        lines.forEachIndexed { index, line ->
            val latest = !hasPartial && index == lines.lastIndex
            appendLine(sb, line.text, latest = latest, italic = false)
        }
        if (hasPartial) appendLine(sb, state.partial, latest = true, italic = true)

        val status: String? = when {
            state.phase == SessionPhase.ERROR -> state.message
            state.phase == SessionPhase.AWAITING_START && lines.isEmpty() -> state.message ?: "Tap Start to begin captions."
            state.phase == SessionPhase.PAUSED && lines.isEmpty() -> "Paused."
            else -> null
        }
        if (status != null) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            val start = sb.length
            sb.append(status)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#FFC857")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        captionView.text = sb

        // Title with recording indicator.
        val listening = state.phase == SessionPhase.LISTENING || state.phase == SessionPhase.STARTING
        val title = SpannableStringBuilder()
        title.append("\u25CF ")
        title.setSpan(
            ForegroundColorSpan(if (listening) Color.parseColor("#FF4D4D") else Color.parseColor("#6B7F75")),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title.append("SPEAKSENSE AI | LIVE CAPTIONS")
        titleView?.text = title

        actionButton?.apply {
            when (state.phase) {
                SessionPhase.LISTENING -> { text = "Pause"; visibility = View.VISIBLE }
                SessionPhase.PAUSED -> { text = "Resume"; visibility = View.VISIBLE }
                SessionPhase.AWAITING_START -> { text = "Start"; visibility = View.VISIBLE }
                SessionPhase.ERROR -> { text = "Retry"; visibility = View.VISIBLE }
                else -> visibility = View.GONE
            }
        }
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLine(sb: SpannableStringBuilder, line: String, latest: Boolean, italic: Boolean) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        val start = sb.length
        sb.append(line)
        val end = sb.length
        if (latest) {
            sb.setSpan(
                StyleSpan(if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(Color.argb(110, 46, 204, 113)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.setSpan(ForegroundColorSpan(Color.argb(200, 255, 255, 255)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun attachDrag(target: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        target.setOnTouchListener { _, event ->
            val p = params
            val view = root
            if (p == null || view == null) {
                false
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = startX + (event.rawX - touchX).toInt()
                        p.y = startY + (event.rawY - touchY).toInt()
                        clamp(p)
                        updateLayout(view, p)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val x = p.x
                        val y = p.y
                        settingsRepository.update { it.copy(overlayX = x, overlayY = y) }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateLayout(view: View, p: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, p)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val maxX = (screenWidth() - p.width).coerceAtLeast(0)
        val maxY = (screenHeight() - dp(80)).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
    }

    private fun computeWidth(): Int =
        (screenWidth() * settings.captionWidthPercent / 100f).toInt().coerceAtLeast(dp(220))

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx: Int = 600

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST),
            )
        }
    }

    private companion object {
        const val TAG = "FloatingCaption"
        const val MAX_VISIBLE_LINES = 12
        val GREEN: Int = Color.parseColor("#2ECC71")
    }
}
