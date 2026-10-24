package com.nirzor.voicebubble.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nirzor.voicebubble.MainActivity
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.data.AppPreferences
import com.nirzor.voicebubble.data.BubbleTheme
import com.nirzor.voicebubble.data.BubbleThemes
import com.nirzor.voicebubble.data.UndoRedoManager
import com.nirzor.voicebubble.data.VoiceHistoryEntity
import com.nirzor.voicebubble.speech.BubbleState
import com.nirzor.voicebubble.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

class FloatingBubbleService : Service() {

    private val TAG = "FloatingBubbleService"
    private val NOTIFICATION_ID = 2026
    private val CHANNEL_ID = "voice_bubble_service_channel"

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingRootView: FrameLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var bubbleCircle: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var progressSpinner: ProgressBar? = null
    private var pulseRing: View? = null
    private var previewPill: LinearLayout? = null
    private var previewPillText: TextView? = null
    private var resultActionBar: LinearLayout? = null
    private var inlineEditLayout: LinearLayout? = null
    private var inlineEditText: EditText? = null
    private var inlineEditDoneBtn: TextView? = null
    private var btnRaw: TextView? = null
    private var btnPolish: TextView? = null
    private var btnDiscard: TextView? = null
    private var btnEdit: TextView? = null

    private var previousClip: ClipData? = null
    private var activeRawTranscript: String = ""
    private var activePolishedTranscript: String? = null
    private var isInlineEditing: Boolean = false
    private val actionPillDismissRunnable = Runnable {
        hideResultActionPill()
    }
    private var copiedBadge: LinearLayout? = null
    private var copiedBadgeText: TextView? = null
    private var removeTargetView: FrameLayout? = null
    private var removeTargetIcon: ImageView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var removeTargetParams: WindowManager.LayoutParams
    private lateinit var speechEngine: SpeechEngine
    private lateinit var appPreferences: AppPreferences
    private lateinit var clipboardManager: ClipboardManager

    private var isViewAttached = false
    private var isRemoveTargetAttached = false
    private var autoDismissJob: Job? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchSlop = 16
    private var isDragging = false
    private var isOverRemoveTarget = false
    private var latestPartialTranscript = ""
    private var latestTranscript: String? = null
    private var quickMenuCard: LinearLayout? = null
    private var geminiStatusBadge: TextView? = null
    private var isQuickMenuOpen = false
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered = false
    private var touchStartTime = 0L
    private var hideBadgeJob: Job? = null
    private var dockingAnimator: ValueAnimator? = null

    companion object {
        const val ACTION_START = "com.nirzor.voicebubble.ACTION_START"
        const val ACTION_STOP = "com.nirzor.voicebubble.ACTION_STOP"
        const val EXTRA_START_LISTENING = "extra_start_listening"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context, startListeningImmediately: Boolean = false) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_START_LISTENING, startListeningImmediately)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appPreferences = VoiceBubbleApp.instance.preferences
        speechEngine = SpeechEngine(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        updateScreenDimensions()
        createNotificationChannel()

        // The foreground-service promotion must happen in onStartCommand before
        // attempting the overlay attachment. Android can terminate a service that
        // fails to become foreground within the required startup window.
        observeBubbleState()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasOverlayPermission || !hasMicPermission) {
            Log.w(
                TAG,
                "Cannot start bubble: overlayPermission=$hasOverlayPermission, microphonePermission=$hasMicPermission"
            )
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = createNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote voice bubble to foreground service", e)
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            if (floatingRootView == null) {
                initBubbleLayout()
            }
            if (!attachBubbleView()) {
                throw IllegalStateException("Floating bubble view could not be attached")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bubble service started but overlay attachment failed", e)
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Publish the running state only after both foreground promotion and overlay
        // attachment have succeeded. This keeps the Home switch and QS tile truthful.
        _isServiceRunning.value = true

        val shouldStartListening = intent?.getBooleanExtra(EXTRA_START_LISTENING, false) ?: false
        if (shouldStartListening) {
            startListeningSession()
        } else {
            scheduleAutoDismiss()
        }

        return START_NOT_STICKY
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        clampPositionToScreen()
    }

    private fun clampPositionToScreen() {
        if (!isViewAttached || floatingRootView == null) return
        val bubbleSizePx = dpToPx(appPreferences.bubbleSizeDp.value)
        val maxX = (screenWidth - bubbleSizePx).coerceAtLeast(0)
        val maxY = (screenHeight - bubbleSizePx).coerceAtLeast(0)

        windowParams.x = windowParams.x.coerceIn(0, maxX)
        windowParams.y = windowParams.y.coerceIn(0, maxY)

        try {
            windowManager?.updateViewLayout(floatingRootView, windowParams)
        } catch (e: Exception) {
            Log.w(TAG, "clampPosition error", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initBubbleLayout() {
        val bubbleSizeDp = appPreferences.bubbleSizeDp.value.coerceIn(48, 100)
        val bubbleSizePx = dpToPx(bubbleSizeDp)
        val theme = appPreferences.getSelectedColorTheme()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (appPreferences.bubbleX.value >= 0) appPreferences.bubbleX.value else screenWidth - bubbleSizePx - dpToPx(16)
            y = if (appPreferences.bubbleY.value >= 0) appPreferences.bubbleY.value else screenHeight / 3
        }

        // Root container
        floatingRootView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Bubble container (includes pulse ring and circle)
        bubbleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(R.string.bubble_content_desc)
            isClickable = true
            isFocusable = true
        }

        // Pulse Ring (visual indicator during listening)
        val ringSize = (bubbleSizePx * 1.4f).toInt()
        pulseRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(2), theme.accentColorInt)
            }
            visibility = View.GONE
        }

        // Bubble Circle
        bubbleCircle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                gravity = Gravity.CENTER
            }
            background = createBubbleBackground(theme.primaryColorInt)
            elevation = dpToPx(6).toFloat()
            alpha = appPreferences.bubbleOpacity.value
        }

        // Mic icon
        micIcon = ImageView(this).apply {
            val iconSize = (bubbleSizePx * 0.52f).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }

        // Processing ProgressBar spinner
        progressSpinner = ProgressBar(this).apply {
            val spinnerSize = (bubbleSizePx * 0.55f).toInt()
            layoutParams = FrameLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        bubbleCircle?.addView(micIcon)
        bubbleCircle?.addView(progressSpinner)
        bubbleContainer?.addView(pulseRing)
        bubbleContainer?.addView(bubbleCircle)

        // Partial text preview pill & inline editor container
        previewPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#E0111827"))
            }
            visibility = View.GONE
        }
        previewPillText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        previewPill?.addView(previewPillText)

        // Inline edit layout
        inlineEditLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        inlineEditText = EditText(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = dpToPx(120)
            maxWidth = dpToPx(220)
            maxLines = 3
        }
        inlineEditDoneBtn = TextView(this).apply {
            text = "✓"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            setOnClickListener {
                finishInlineEditing(save = true)
            }
        }
        inlineEditLayout?.addView(inlineEditText)
        inlineEditLayout?.addView(inlineEditDoneBtn)
        previewPill?.addView(inlineEditLayout)

        // Result action bar
        resultActionBar = initResultActionBar()

        // Copied badge pill
        copiedBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#E6059669"))
            }
            visibility = View.GONE
        }
        copiedBadgeText = TextView(this).apply {
            text = getString(R.string.bubble_state_copied)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        copiedBadge?.addView(copiedBadgeText)

        val quickMenuParams = LinearLayout.LayoutParams(
            dpToPx(240),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dpToPx(8), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        quickMenuCard = initQuickMenuCard()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(copiedBadge)
            addView(previewPill)
            addView(resultActionBar)
            addView(bubbleContainer)
            addView(quickMenuCard, quickMenuParams)
        }
        floatingRootView?.addView(column)

        floatingRootView?.setOnClickListener {
            if (isQuickMenuOpen) {
                hideQuickMenu()
            }
        }

        setupTouchListener()
        setupRemoveTarget()
    }

    private fun setupRemoveTarget() {
        val targetSize = dpToPx(72)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        removeTargetParams = WindowManager.LayoutParams(
            targetSize, targetSize, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48)
        }

        removeTargetView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99EF4444"))
                setStroke(dpToPx(2), Color.WHITE)
            }
            visibility = View.GONE
        }
        removeTargetIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.WHITE)
        }
        removeTargetView?.addView(removeTargetIcon)
    }

    private fun showRemoveTarget(show: Boolean) {
        if (show) {
            if (!isRemoveTargetAttached && removeTargetView != null) {
                try {
                    windowManager?.addView(removeTargetView, removeTargetParams)
                    isRemoveTargetAttached = true
                } catch (e: Exception) {
                    Log.w(TAG, "Error adding remove target", e)
                }
            }
            removeTargetView?.visibility = View.VISIBLE
        } else {
            removeTargetView?.visibility = View.GONE
            if (isRemoveTargetAttached && removeTargetView != null) {
                try {
                    windowManager?.removeView(removeTargetView)
                    isRemoveTargetAttached = false
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing remove target", e)
                }
            }
        }
    }

    private fun createBubbleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        bubbleContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoDismiss()
                    dockingAnimator?.cancel()
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false
                    isOverRemoveTarget = false
                    isLongPressTriggered = false

                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        if (!isDragging && speechEngine.speechState.value !is BubbleState.Listening && speechEngine.speechState.value !is BubbleState.Processing) {
                            isLongPressTriggered = true
                            triggerHapticClick()
                            toggleQuickMenu()
                        }
                    }
                    mainHandler.postDelayed(longPressRunnable!!, 450L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        isDragging = true
                        if (isQuickMenuOpen) {
                            hideQuickMenu()
                        }
                        showRemoveTarget(true)
                    }

                    if (isDragging) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(floatingRootView, windowParams)
                        } catch (e: Exception) {
                            Log.w(TAG, "Update layout error", e)
                        }

                        // Check collision with remove target
                        val removeTargetCenterY = screenHeight - dpToPx(48) - dpToPx(36)
                        val removeTargetCenterX = screenWidth / 2
                        val bubbleCenterX = windowParams.x + dpToPx(appPreferences.bubbleSizeDp.value) / 2
                        val bubbleCenterY = windowParams.y + dpToPx(appPreferences.bubbleSizeDp.value) / 2
                        val distance = hypot((bubbleCenterX - removeTargetCenterX).toDouble(), (bubbleCenterY - removeTargetCenterY).toDouble())

                        val isColliding = distance < dpToPx(60)
                        if (isColliding != isOverRemoveTarget) {
                            isOverRemoveTarget = isColliding
                            removeTargetView?.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isColliding) Color.parseColor("#E5DC2626") else Color.parseColor("#99EF4444"))
                                setStroke(dpToPx(if (isColliding) 3 else 2), Color.WHITE)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    showRemoveTarget(false)
                    if (isLongPressTriggered) {
                        // Handled by long-press gesture
                    } else if (isDragging) {
                        if (isOverRemoveTarget) {
                            // Phase E.1: Drag to remove target stops service
                            stopSelf()
                            return@setOnTouchListener true
                        }

                        // Dock to edge
                        if (appPreferences.dockToEdge.value) {
                            dockBubbleToScreenEdge()
                        } else {
                            appPreferences.setBubblePosition(windowParams.x, windowParams.y)
                        }
                    } else {
                        // Single tap detected!
                        if (isQuickMenuOpen) {
                            hideQuickMenu()
                        } else {
                            handleBubbleTap()
                        }
                    }
                    isDragging = false
                    isLongPressTriggered = false
                    scheduleAutoDismiss()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    showRemoveTarget(false)
                    isDragging = false
                    isLongPressTriggered = false
                    scheduleAutoDismiss()
                    false
                }
                else -> false
            }
        }
    }

    private fun handleBubbleTap() {
        when (val currentState = speechEngine.speechState.value) {
            is BubbleState.Idle -> startListeningSession()
            is BubbleState.Listening -> speechEngine.stopSession()
            is BubbleState.Error, is BubbleState.Done -> {
                // Let SpeechEngine check for update/busy rejection before it resets the old result.
                startListeningSession()
            }
            else -> Log.d(TAG, "Tap ignored while recognition is active: $currentState")
        }
    }

    private fun dockBubbleToScreenEdge() {
        dockingAnimator?.cancel()
        val bubbleSizePx = dpToPx(appPreferences.bubbleSizeDp.value)
        val currentCenterX = windowParams.x + bubbleSizePx / 2
        val targetX = if (currentCenterX < screenWidth / 2) {
            dpToPx(8)
        } else {
            screenWidth - bubbleSizePx - dpToPx(8)
        }
        val startX = windowParams.x
        if (startX == targetX) {
            clampPositionToScreen()
            appPreferences.setBubblePosition(windowParams.x, windowParams.y)
            return
        }

        dockingAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val animatedVal = animator.animatedValue as Int
                windowParams.x = animatedVal
                clampPositionToScreen()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clampPositionToScreen()
                    appPreferences.setBubblePosition(windowParams.x, windowParams.y)
                }
            })
            start()
        }
    }

    private fun deliverText(text: String, isPolished: Boolean = false): Boolean {
        if (text.isBlank()) return false
        var injected = false
        if (appPreferences.directInject.value && VoiceBubbleAccessibilityService.isServiceEnabled.value) {
            injected = VoiceBubbleAccessibilityService.injectText(text)
        }
        if (!injected && appPreferences.autoCopy.value) {
            val clip = ClipData.newPlainText("Voice Transcription", text)
            clipboardManager.setPrimaryClip(clip)
        }
        latestTranscript = text
        UndoRedoManager.recordInput(this, text)
        return injected
    }

    private fun startListeningSession() {
        cancelAutoDismiss()
        hideResultActionPill()
        copiedBadge?.visibility = View.GONE
        try {
            previousClip = clipboardManager.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture clipboard snapshot", e)
        }
        speechEngine.startSession(
            languageCode = appPreferences.selectedLanguage.value,
            playTone = appPreferences.soundFeedback.value,
            enableHaptic = appPreferences.hapticFeedback.value,
            continuousMode = appPreferences.continuousMode.value,
            onPartialResult = { partial ->
                latestPartialTranscript = partial
                mainHandler.post {
                    previewPill?.visibility = View.VISIBLE
                    resultActionBar?.visibility = View.GONE
                    previewPillText?.visibility = View.VISIBLE
                    inlineEditLayout?.visibility = View.GONE
                    previewPillText?.text = partial
                }
            },
            onFinalResult = { rawText, lang, durationMs ->
                handleFinalSpeechResult(rawText, lang, durationMs)
            },
            onErrorOccurred = { errorMsg, _ ->
                mainHandler.post {
                    previewPill?.visibility = View.VISIBLE
                    resultActionBar?.visibility = View.GONE
                    previewPillText?.visibility = View.VISIBLE
                    inlineEditLayout?.visibility = View.GONE
                    previewPillText?.maxLines = 5
                    previewPillText?.text = if (latestPartialTranscript.isBlank()) {
                        errorMsg
                    } else {
                        "${latestPartialTranscript.lineSequence().first().take(80)}\n$errorMsg"
                    }
                }
            }
        )
        if (speechEngine.speechState.value is BubbleState.Listening) {
            latestPartialTranscript = ""
            previewPill?.visibility = View.GONE
            previewPillText?.maxLines = 4
        }
    }

    private fun handleFinalSpeechResult(rawText: String, lang: String, durationMs: Long) {
        cancelAutoDismiss()
        val rawTrimmed = rawText.trim()
        if (rawTrimmed.isBlank()) return

        // 1. Deliver raw immediately
        val injected = deliverText(rawTrimmed)
        val initialBadgeMsg = if (injected) "সরাসরি ইনসার্ট ✓" else getString(R.string.bubble_state_copied)
        speechEngine.stateMachine.moveToDone(rawTrimmed, null, true)

        var historyEntity = VoiceHistoryEntity(
            text = rawTrimmed,
            language = lang,
            durationMs = durationMs,
            rawText = rawTrimmed,
            polishedText = null,
            createdAt = System.currentTimeMillis()
        )
        serviceScope.launch(Dispatchers.IO) {
            try {
                VoiceBubbleApp.instance.repository.insert(historyEntity)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving history", e)
            }
        }

        mainHandler.post {
            showTemporaryBadge(initialBadgeMsg, isAi = false)
            showResultActionPill(rawText = rawTrimmed, initialPolished = null)
        }

        // 2. Background polish upgrade
        val aiEnabled = appPreferences.aiPolishEnabled.value
        val style = appPreferences.polishStyle.value
        if (aiEnabled && rawTrimmed.length > 15) {
            serviceScope.launch {
                val polished = withTimeoutOrNull(8_000L) {
                    GeminiApiClient.polishText(
                        this@FloatingBubbleService,
                        rawTrimmed,
                        style,
                        appPreferences.customPolishPrompt.value
                    )
                }?.trim()

                if (!polished.isNullOrBlank() && polished != rawTrimmed) {
                    val polInjected = deliverText(polished, isPolished = true)
                    val polBadge = if (polInjected) "✨ পলিশ ইনসার্ট ✓" else "✨ পলিশ ✓"
                    mainHandler.post {
                        showTemporaryBadge(polBadge, isAi = true)
                        updateResultActionPillPolished(polished)
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            historyEntity = historyEntity.copy(text = polished, polishedText = polished)
                            VoiceBubbleApp.instance.repository.update(historyEntity)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating history with polished text", e)
                        }
                    }
                }
            }
        }
    }

    private fun createActionButton(label: String, textColorHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#374151"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun initResultActionBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#E0111827"))
            }
            visibility = View.GONE

            btnRaw = createActionButton(getString(R.string.action_raw), "#9CA3AF") {
                if (activeRawTranscript.isNotBlank()) {
                    deliverText(activeRawTranscript)
                    showTemporaryBadge("কাঁচা কপি ✓", isAi = false)
                    resetActionPillTimeout(2000L)
                }
            }

            btnPolish = createActionButton(getString(R.string.action_polish), "#C084FC") {
                val polished = activePolishedTranscript
                if (!polished.isNullOrBlank()) {
                    deliverText(polished, isPolished = true)
                    showTemporaryBadge("✨ পলিশ কপি ✓", isAi = true)
                    resetActionPillTimeout(2000L)
                } else if (activeRawTranscript.isNotBlank()) {
                    btnPolish?.text = "..."
                    serviceScope.launch {
                        val newPolished = withTimeoutOrNull(8_000L) {
                            GeminiApiClient.polishText(
                                this@FloatingBubbleService,
                                activeRawTranscript,
                                appPreferences.polishStyle.value,
                                appPreferences.customPolishPrompt.value
                            )
                        }?.trim()
                        mainHandler.post {
                            btnPolish?.text = getString(R.string.action_polish)
                            if (!newPolished.isNullOrBlank() && newPolished != activeRawTranscript) {
                                updateResultActionPillPolished(newPolished)
                                deliverText(newPolished, isPolished = true)
                                showTemporaryBadge("✨ পলিশ ✓", isAi = true)
                            }
                            resetActionPillTimeout(2000L)
                        }
                    }
                }
            }

            btnDiscard = createActionButton(getString(R.string.action_discard), "#F87171") {
                try {
                    previousClip?.let { clipboardManager.setPrimaryClip(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring previous clip", e)
                }
                showTemporaryBadge("বাতিল ✓", isAi = false)
                hideResultActionPill()
            }

            btnEdit = createActionButton(getString(R.string.action_edit), "#60A5FA") {
                startInlineEditing()
            }

            val marginParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }

            addView(btnRaw, marginParams)
            addView(btnPolish, marginParams)
            addView(btnDiscard, marginParams)
            addView(btnEdit, marginParams)
        }
    }

    private fun showResultActionPill(rawText: String, initialPolished: String?) {
        activeRawTranscript = rawText
        activePolishedTranscript = initialPolished
        previewPillText?.text = initialPolished ?: rawText
        previewPill?.visibility = View.VISIBLE
        resultActionBar?.visibility = View.VISIBLE
        btnPolish?.visibility = if (appPreferences.aiPolishEnabled.value) View.VISIBLE else View.GONE

        mainHandler.removeCallbacks(actionPillDismissRunnable)
        mainHandler.postDelayed(actionPillDismissRunnable, 5000L)
    }

    private fun updateResultActionPillPolished(polished: String) {
        activePolishedTranscript = polished
        if (!isInlineEditing) {
            previewPillText?.text = polished
        }
    }

    private fun hideResultActionPill() {
        if (isInlineEditing) {
            finishInlineEditing(save = false)
        }
        mainHandler.removeCallbacks(actionPillDismissRunnable)
        resultActionBar?.visibility = View.GONE
        previewPill?.visibility = View.GONE
        if (speechEngine.speechState.value is BubbleState.Done) {
            speechEngine.stateMachine.resetToIdle()
        }
        scheduleAutoDismiss()
    }

    private fun startInlineEditing() {
        isInlineEditing = true
        mainHandler.removeCallbacks(actionPillDismissRunnable)
        previewPillText?.visibility = View.GONE
        inlineEditLayout?.visibility = View.VISIBLE
        val textToEdit = activePolishedTranscript ?: activeRawTranscript
        inlineEditText?.setText(textToEdit)
        inlineEditText?.setSelection(textToEdit.length)

        windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try {
            windowManager.updateViewLayout(floatingRootView, windowParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to make window focusable for editing", e)
        }

        inlineEditText?.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(inlineEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishInlineEditing(save: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inlineEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }

        windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            windowManager.updateViewLayout(floatingRootView, windowParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore window flags after editing", e)
        }

        if (save) {
            val edited = inlineEditText?.text?.toString()?.trim().orEmpty()
            if (edited.isNotBlank()) {
                activePolishedTranscript = edited
                previewPillText?.text = edited
                deliverText(edited)
                showTemporaryBadge("এডিট কপি ✓", isAi = false)
            }
        }

        inlineEditLayout?.visibility = View.GONE
        previewPillText?.visibility = View.VISIBLE
        isInlineEditing = false
        resetActionPillTimeout(3000L)
    }

    private fun resetActionPillTimeout(timeoutMs: Long) {
        mainHandler.removeCallbacks(actionPillDismissRunnable)
        mainHandler.postDelayed(actionPillDismissRunnable, timeoutMs)
    }

    private fun observeBubbleState() {
        serviceScope.launch {
            speechEngine.speechState.collect { state ->
                mainHandler.post {
                    updateBubbleVisualState(state)
                }
            }
        }
    }

    private fun updateBubbleVisualState(state: BubbleState) {
        val theme = appPreferences.getSelectedColorTheme()
        when (state) {
            is BubbleState.Idle -> {
                pulseRing?.visibility = View.GONE
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                micIcon?.setImageResource(android.R.drawable.ic_btn_speak_now)
                bubbleCircle?.background = createBubbleBackground(theme.primaryColorInt)
            }

            is BubbleState.Listening -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.VISIBLE
                bubbleCircle?.background = createBubbleBackground(theme.accentColorInt)
                animatePulseRing(state.rmsDb)
            }

            is BubbleState.Processing -> {
                cancelAutoDismiss()
                pulseRing?.visibility = View.GONE
                micIcon?.visibility = View.GONE
                progressSpinner?.visibility = View.VISIBLE
                bubbleCircle?.background = createBubbleBackground(theme.secondaryColorInt)
            }

            is BubbleState.Done -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.GONE
                micIcon?.setImageResource(android.R.drawable.checkbox_on_background)
                bubbleCircle?.background = createBubbleBackground(Color.parseColor("#059669"))
            }

            is BubbleState.Error -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.GONE
                micIcon?.setImageResource(android.R.drawable.stat_notify_error)
                bubbleCircle?.background = createBubbleBackground(Color.parseColor("#DC2626"))
                // Schedule only after Error has become the displayed state; retain any draft for retry.
                scheduleAutoDismiss()
            }
        }
    }

    private fun animatePulseRing(rms: Float) {
        val scale = 1.0f + (rms * 0.4f).coerceIn(0f, 0.4f)
        pulseRing?.animate()
            ?.scaleX(scale)
            ?.scaleY(scale)
            ?.setDuration(120)
            ?.start()
    }

    private fun observeSettings() {
        serviceScope.launch {
            appPreferences.bubbleOpacity.collect { opacity ->
                mainHandler.post {
                    bubbleCircle?.alpha = opacity
                }
            }
        }
        serviceScope.launch {
            appPreferences.bubbleColorTheme.collect {
                mainHandler.post {
                    val theme = appPreferences.getSelectedColorTheme()
                    bubbleCircle?.background = createBubbleBackground(theme.primaryColorInt)
                }
            }
        }
        serviceScope.launch {
            appPreferences.keepBubbleAlwaysOn.collect { keepAlwaysOn ->
                mainHandler.post {
                    if (keepAlwaysOn) {
                        cancelAutoDismiss()
                    } else {
                        scheduleAutoDismiss()
                    }
                }
            }
        }
        serviceScope.launch {
            appPreferences.aiPolishEnabled.collect { isEnabled ->
                mainHandler.post {
                    geminiStatusBadge?.let {
                        updateGeminiBadge(it, isEnabled)
                    }
                }
            }
        }
    }

    private fun initQuickMenuCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(12)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#F50F172A")) // Solid dark slate
                setStroke(dpToPx(1), Color.parseColor("#38BDF8"))
            }
            elevation = dpToPx(24).toFloat()
            visibility = View.GONE

            // Header Layout
            val headerLayout = LinearLayout(this@FloatingBubbleService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }
            val headerTitle = TextView(this@FloatingBubbleService).apply {
                text = "⚡ কুইক মেনু (Quick Menu)"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val closeBtn = TextView(this@FloatingBubbleService).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                val p = dpToPx(4)
                setPadding(p * 2, p, p * 2, p)
                setOnClickListener {
                    hideQuickMenu()
                }
            }
            headerLayout.addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            headerLayout.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(headerLayout)

            // Divider
            addView(createDivider())

            val copyLatestRow = quickMenuRow(
                title = "📋 সর্বশেষ লেখা কপি করুন",
                subtitle = "শুধু আপনার ট্যাপে ক্লিপবোর্ডে কপি হবে"
            ) {
                copyLatestTranscript()
            }
            addView(copyLatestRow)
            addView(createDivider())

            val shareLatestRow = quickMenuRow(
                title = "↗ লেখা পাঠান / শেয়ার করুন",
                subtitle = "Clipboard অ্যাপসহ একটি গন্তব্য বেছে নিন"
            ) {
                shareLatestTranscript()
            }
            addView(shareLatestRow)
            addView(createDivider())

            // Option 1: Gemini AI Paraphrasing Toggle
            val geminiRow = LinearLayout(this@FloatingBubbleService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pV = dpToPx(8)
                val pH = dpToPx(4)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true

                val rowTextLayout = LinearLayout(this@FloatingBubbleService).apply {
                    orientation = LinearLayout.VERTICAL
                    val textTitle = TextView(this@FloatingBubbleService).apply {
                        text = "✨ Gemini AI প্যারাফ্রেজ"
                        setTextColor(Color.parseColor("#F8FAFC"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val textSub = TextView(this@FloatingBubbleService).apply {
                        text = "ব্যাকরণ ও ফিলার অটো-ক্লিন"
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    }
                    addView(textTitle)
                    addView(textSub)
                }

                geminiStatusBadge = TextView(this@FloatingBubbleService).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    val bPadH = dpToPx(8)
                    val bPadV = dpToPx(3)
                    setPadding(bPadH, bPadV, bPadH, bPadV)
                    updateGeminiBadge(this, appPreferences.aiPolishEnabled.value)
                }

                addView(rowTextLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(geminiStatusBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                setOnClickListener {
                    val newState = !appPreferences.aiPolishEnabled.value
                    appPreferences.setAiPolishEnabled(newState)
                    geminiStatusBadge?.let { badge -> updateGeminiBadge(badge, newState) }
                    triggerHapticClick()
                }
            }
            addView(geminiRow)
            addView(createDivider())

            // Option 2: History Log
            val historyRow = LinearLayout(this@FloatingBubbleService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pV = dpToPx(8)
                val pH = dpToPx(4)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true

                val rowTextLayout = LinearLayout(this@FloatingBubbleService).apply {
                    orientation = LinearLayout.VERTICAL
                    val textTitle = TextView(this@FloatingBubbleService).apply {
                        text = "📜 হিস্ট্রি লগ দেখুন"
                        setTextColor(Color.parseColor("#F8FAFC"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val textSub = TextView(this@FloatingBubbleService).apply {
                        text = "আগের সব ট্রান্সক্রিপশন তালিকা"
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    }
                    addView(textTitle)
                    addView(textSub)
                }

                val arrowIcon = TextView(this@FloatingBubbleService).apply {
                    text = "➔"
                    setTextColor(Color.parseColor("#38BDF8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dpToPx(6), 0, 0, 0)
                }

                addView(rowTextLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrowIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                setOnClickListener {
                    hideQuickMenu()
                    triggerHapticClick()
                    val historyIntent = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_NAV_TARGET, MainActivity.TARGET_HISTORY)
                    }
                    startActivity(historyIntent)
                }
            }
            addView(historyRow)
        }
        return card
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                setMargins(0, dpToPx(4), 0, dpToPx(4))
            }
            setBackgroundColor(Color.parseColor("#334155"))
        }
    }

    private fun quickMenuRow(
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
        isClickable = true
        isFocusable = true
        val labels = LinearLayout(this@FloatingBubbleService).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FloatingBubbleService).apply {
                text = title
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@FloatingBubbleService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
        }
        val arrowIcon = TextView(this@FloatingBubbleService).apply {
            text = "➔"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(6), 0, 0, 0)
        }
        addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(arrowIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            onClick()
            triggerHapticClick()
        }
    }

    private fun updateGeminiBadge(badge: TextView, isEnabled: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(10).toFloat()
            if (isEnabled) {
                setColor(Color.parseColor("#065F46"))
                setStroke(dpToPx(1), Color.parseColor("#34D399"))
            } else {
                setColor(Color.parseColor("#334155"))
                setStroke(dpToPx(1), Color.parseColor("#64748B"))
            }
        }
        badge.background = bg
        badge.text = if (isEnabled) "চালু (ON)" else "বন্ধ (OFF)"
        badge.setTextColor(if (isEnabled) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
    }

    private fun showQuickMenu() {
        if (speechEngine.speechState.value is BubbleState.Listening || speechEngine.speechState.value is BubbleState.Processing) return
        isQuickMenuOpen = true
        geminiStatusBadge?.let {
            updateGeminiBadge(it, appPreferences.aiPolishEnabled.value)
        }

        updateScreenDimensions()
        val menuWidthPx = dpToPx(240)
        if (windowParams.x + menuWidthPx > screenWidth) {
            windowParams.x = maxOf(dpToPx(8), screenWidth - menuWidthPx - dpToPx(8))
            try {
                windowManager?.updateViewLayout(floatingRootView, windowParams)
            } catch (e: Exception) {
                Log.e(TAG, "Update layout on quick menu show", e)
            }
        }

        quickMenuCard?.visibility = View.VISIBLE
        val anim = AlphaAnimation(0f, 1f).apply {
            duration = 200
            fillAfter = true
        }
        quickMenuCard?.startAnimation(anim)
        previewPill?.visibility = View.GONE
        copiedBadge?.visibility = View.GONE
        cancelAutoDismiss()
    }

    private fun hideQuickMenu() {
        if (!isQuickMenuOpen && quickMenuCard?.visibility != View.VISIBLE) return
        isQuickMenuOpen = false
        val anim = AlphaAnimation(1f, 0f).apply {
            duration = 150
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    quickMenuCard?.visibility = View.GONE
                }
                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }
        quickMenuCard?.startAnimation(anim)
        scheduleAutoDismiss()
    }

    private fun toggleQuickMenu() {
        if (isQuickMenuOpen) {
            hideQuickMenu()
        } else {
            showQuickMenu()
        }
    }

    private fun copyLatestTranscript() {
        val transcript = latestTranscript
        if (transcript.isNullOrBlank()) {
            showTemporaryBadge("কপি করার মতো কোনো লেখা নেই", isAi = false)
            return
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Voice Transcription", transcript))
        showTemporaryBadge("ক্লিপবোর্ডে কপি হয়েছে ✓", isAi = false)
        hideQuickMenu()
    }

    private fun shareLatestTranscript() {
        val transcript = latestTranscript
        if (transcript.isNullOrBlank()) {
            showTemporaryBadge("পাঠানোর মতো কোনো লেখা নেই", isAi = false)
            return
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        val hasHandler = packageManager.resolveActivity(sendIntent, 0) != null ||
            packageManager.queryIntentActivities(sendIntent, 0).isNotEmpty()
        if (!hasHandler) {
            showTemporaryBadge("কোনো শেয়ার অ্যাপ পাওয়া যায়নি", isAi = false)
            return
        }
        try {
            val chooser = Intent.createChooser(sendIntent, "Transcript পাঠান").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
            hideQuickMenu()
        } catch (e: Exception) {
            Log.w(TAG, "No application can receive shared transcript", e)
            showTemporaryBadge("কোনো শেয়ার অ্যাপ পাওয়া যায়নি", isAi = false)
        }
    }

    private fun showTemporaryBadge(message: String, isAi: Boolean) {
        hideBadgeJob?.cancel()
        copiedBadgeText?.text = message
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(14).toFloat()
            setColor(if (isAi) Color.parseColor("#E67C3AED") else Color.parseColor("#E6059669"))
        }
        copiedBadge?.background = bgDrawable
        copiedBadge?.visibility = View.VISIBLE
        val anim = AlphaAnimation(0f, 1f).apply { duration = 200 }
        copiedBadge?.startAnimation(anim)

        hideBadgeJob = serviceScope.launch {
            delay(2500L)
            mainHandler.post {
                val fadeOut = AlphaAnimation(1f, 0f).apply {
                    duration = 300
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            copiedBadge?.visibility = View.GONE
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                copiedBadge?.startAnimation(fadeOut)
            }
        }
    }

    private fun triggerHapticClick() {
        if (!appPreferences.hapticFeedback.value) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(40)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic trigger failed", e)
        }
    }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        if (!shouldAutoDismissBubble(
                keepAlwaysOn = appPreferences.keepBubbleAlwaysOn.value,
                state = speechEngine.speechState.value,
                retainedPartialDraft = latestPartialTranscript
            )
        ) return
        autoDismissJob = serviceScope.launch {
            delay(15_000L) // 15-second timeout for dismissible idle/empty-error states
            if (shouldAutoDismissBubble(
                    keepAlwaysOn = appPreferences.keepBubbleAlwaysOn.value,
                    state = speechEngine.speechState.value,
                    retainedPartialDraft = latestPartialTranscript
                )
            ) {
                Log.d(TAG, "Auto-dismissing bubble after 15s in a dismissible state")
                stopSelf()
            }
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    private fun attachBubbleView(): Boolean {
        if (isViewAttached) return true

        val rootView = floatingRootView ?: return false
        val manager = windowManager ?: return false

        return try {
            manager.addView(rootView, windowParams)
            isViewAttached = true
            true
        } catch (e: Exception) {
            isViewAttached = false
            Log.e(TAG, "Failed to attach floating bubble view", e)
            false
        }
    }

    private fun detachBubbleView() {
        if (floatingRootView != null && isViewAttached) {
            try {
                windowManager?.removeView(floatingRootView)
                isViewAttached = false
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating bubble view", e)
            }
        }
        showRemoveTarget(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoDismiss()
        mainHandler.removeCallbacks(actionPillDismissRunnable)
        dockingAnimator?.cancel()
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        hideBadgeJob?.cancel()
        _isServiceRunning.value = false
        speechEngine.destroy()
        detachBubbleView()
        serviceScope.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
