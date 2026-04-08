// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.floating

import android.app.Application
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.blankj.utilcode.util.ThreadUtils
import io.agents.pokeclaw.R
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog
import io.agents.pokeclaw.utils.KVUtils
import com.blankj.utilcode.util.BarUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils

/**
 * Circular floating window manager
 * Uses EasyFloat to implement a draggable floating window that remembers its position
 * Supports multiple states: waiting for task (IDLE), task running (RUNNING), task succeeded (SUCCESS), task failed (ERROR)
 */
object FloatingCircleManager {

    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"
    private const val AUTO_RESET_DELAY_MS = 5000L // auto-reset after 5 seconds

    /**
     * Floating window state
     */
    enum class State {
        IDLE,           // waiting for task (default)
        TASK_NOTIFY,    // task notification received (pill expanded)
        RUNNING,        // task running
        SUCCESS,        // task completed
        ERROR           // task failed
    }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var currentChannel: Channel? = null
    private var currentTokenState: io.agents.pokeclaw.agent.TokenMonitor.State = io.agents.pokeclaw.agent.TokenMonitor.State.NORMAL

    private const val TASK_NOTIFY_DURATION_MS = 3000L // task notification shown for 3 seconds before collapsing

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoResetRunnable: Runnable? = null
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""

    private var appRef: Application? = null

    /**
     * Show the floating window
     * @param application Application instance
     * @param x initial X position (optional, defaults to right-center of screen)
     * @param y initial Y position (optional, defaults to screen center)
     */
    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        android.util.Log.e("POKECLAW_FLOAT", "show() called, isShowing=$isShowing, canDrawOverlays=${android.provider.Settings.canDrawOverlays(application)}")
        if (isShowing) {
            android.util.Log.e("POKECLAW_FLOAT", "show() skipped — already showing")
            return
        }
        appRef = application

        // Calculate default position: right side of screen center
        val screenWidth = DisplayUtils.getScreenWidth(application)
        val screenHeight = DisplayUtils.getScreenHeight(application)
        val defaultX = 0
        val defaultY = screenHeight / 2

        // Read saved position from local storage
        val savedX = getSavedX() ?: x ?: defaultX
        val savedY = getSavedY() ?: y ?: defaultY

        EasyFloat.with(application)
            .setLayout(R.layout.layout_floating_circle)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.DEFAULT)
            .setGravity(android.view.Gravity.START or android.view.Gravity.TOP, savedX, savedY)
            .setDragEnable(true)
            .hasEditText(false)
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {

                override fun createdResult(
                    isCreated: Boolean,
                    msg: String?,
                    view: View?
                ) {
                    android.util.Log.e("POKECLAW_FLOAT", "createdResult: isCreated=$isCreated, msg=$msg, view=${view != null}")
                    // Cache the original circle width (must be before any setFloatRootWidth call)
                    view?.findViewById<View>(R.id.floatRoot)?.let { root ->
                        if (circleWidthPx <= 0) {
                            circleWidthPx = root.layoutParams?.width ?: -1
                        }
                    }
                    // Click events: tap to bring app to foreground, or stop if over budget
                    view?.setOnClickListener {
                        if (currentState == State.RUNNING &&
                            (currentTokenState == io.agents.pokeclaw.agent.TokenMonitor.State.WARNING ||
                             currentTokenState == io.agents.pokeclaw.agent.TokenMonitor.State.CRITICAL)) {
                            onStopTask()
                        } else {
                            onFloatClick()
                        }
                    }
                    // Initialize state
                    updateStateView(view, currentState)
                    // Detect position after layout to prevent the bubble from getting stuck off-screen
                    view?.post {
                        ensureFloatInBounds(view)
                    }
                }

                override fun dismiss() {
                    isShowing = false
                }

                override fun drag(view: View, event: MotionEvent) {
                }

                override fun dragEnd(view: View) {
                    // Drag ended; correct position and save
                    ensureFloatInBounds(view)
                }

                override fun hide(view: View) {
                    isShowing = false
                }

                override fun show(view: View) {
                    isShowing = true
                }

                override fun touchEvent(view: View, event: MotionEvent) {

                }
            })
            .show()
    }

    /**
     * Hide the floating window
     */
    fun hide() {
        if (isShowing) {
            EasyFloat.dismiss(FLOAT_TAG)
            isShowing = false
        }
    }

    /**
     * Check whether the floating window is currently visible
     */
    fun isShowing(): Boolean = isShowing

    /**
     * Switch to idle state (waiting for task)
     */
    fun setIdleState() {
        ThreadUtils.runOnUiThread {
            setState(State.IDLE)
        }
    }

    /**
     * Show task notification: expand the floating window to pill shape, display task content, auto-collapse to RUNNING after 3 seconds.
     * @param taskText task text (will be truncated for display)
     * @param channel source channel of the message
     */
    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            // Auto-collapse to RUNNING after 3 seconds (call setState directly, bypassing TASK_NOTIFY guard)
            notifyCollapseRunnable = Runnable {
                setState(State.RUNNING)
            }
            mainHandler.postDelayed(notifyCollapseRunnable!!, TASK_NOTIFY_DURATION_MS)
        }
    }

    private fun cancelNotifyCollapse() {
        notifyCollapseRunnable?.let {
            mainHandler.removeCallbacks(it)
            notifyCollapseRunnable = null
        }
    }

    /**
     * Switch to task running state
     * @param round current round number
     * @param channel source channel of the message
     */
    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentChannel = channel
            // If the task notification pill is still showing, only update data; do not switch UI (wait for timer to expire)
            if (currentState == State.TASK_NOTIFY) {
                return@runOnUiThread
            }
            setState(State.RUNNING)
        }
    }

    /**
     * Update token monitor display on the running pill.
     * Call this after each agent loop iteration.
     *
     * @param step current step number
     * @param formattedTokens e.g. "33K"
     * @param formattedCost e.g. "$0.01"
     * @param tokenState NORMAL/CAUTION/WARNING/CRITICAL
     */
    fun updateTokenStatus(step: Int, formattedTokens: String, formattedCost: String, tokenState: io.agents.pokeclaw.agent.TokenMonitor.State) {
        ThreadUtils.runOnUiThread {
            currentRound = step
            currentTokenState = tokenState
            val view = EasyFloat.getFloatView(FLOAT_TAG) ?: return@runOnUiThread
            val tvStatus = view.findViewById<TextView>(R.id.tvTokenStatus) ?: return@runOnUiThread
            tvStatus.text = "Step $step | $formattedTokens | $formattedCost"

            // Update pill color based on token state
            val cardRunning = view.findViewById<androidx.cardview.widget.CardView>(R.id.cardRunning) ?: return@runOnUiThread
            val colorRes = when (tokenState) {
                io.agents.pokeclaw.agent.TokenMonitor.State.NORMAL -> R.color.colorBrandPrimary
                io.agents.pokeclaw.agent.TokenMonitor.State.CAUTION -> R.color.colorWarningPrimary
                io.agents.pokeclaw.agent.TokenMonitor.State.WARNING -> R.color.colorWarningPrimary
                io.agents.pokeclaw.agent.TokenMonitor.State.CRITICAL -> R.color.colorErrorPrimary
            }
            try {
                val app = appRef ?: return@runOnUiThread
                cardRunning.setCardBackgroundColor(app.getColor(colorRes))
            } catch (_: Exception) {}
        }
    }

    /**
     * Switch to task completed state (auto-resets to IDLE after 5 seconds)
     */
    fun setSuccessState() {
        ThreadUtils.runOnUiThread {
            setState(State.SUCCESS)
            scheduleAutoReset()
        }
    }

    /**
     * Switch to task failed state (auto-resets to IDLE after 5 seconds)
     */
    fun setErrorState() {
        ThreadUtils.runOnUiThread {
            setState(State.ERROR)
            scheduleAutoReset()
        }

    }

    /**
     * Set state
     */
    private fun setState(state: State) {
        currentState = state
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        view?.let { updateStateView(it, state) }
    }

    /**
     * Update view state
     */
    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardTaskNotify = view.findViewById<View>(R.id.cardTaskNotify)
        val cardRunning = view.findViewById<View>(R.id.cardRunning)
        val cardSuccess = view.findViewById<View>(R.id.cardSuccess)
        val cardError = view.findViewById<View>(R.id.cardError)

        // Hide all state views
        cardIdle?.visibility = View.GONE
        cardTaskNotify?.visibility = View.GONE
        cardRunning?.visibility = View.GONE
        cardSuccess?.visibility = View.GONE
        cardError?.visibility = View.GONE

        // Cancel any previous auto-reset
        cancelAutoReset()

        // Show corresponding state
        when (state) {
            State.IDLE -> {
                cardIdle?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
            State.TASK_NOTIFY -> {
                cardTaskNotify?.visibility = View.VISIBLE
                val tvNotify = view.findViewById<TextView>(R.id.tvTaskNotify)
                val app = appRef ?: return
                val displayText = if (pendingTaskText.length > 40) {
                    pendingTaskText.substring(0, 40) + "…"
                } else {
                    pendingTaskText
                }
                tvNotify?.text = app.getString(R.string.floating_task_received, displayText)
                val ivLogo = view.findViewById<ImageView>(R.id.ivNotifyChannelLogo)
                ivLogo?.setImageResource(getChannelIcon(currentChannel))
                // Expand to wrap_content
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            State.RUNNING -> {
                cancelNotifyCollapse()
                // Expand to pill shape for token display
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
                cardRunning?.visibility = View.VISIBLE
                // Update token status text
                val tvStatus = view.findViewById<TextView>(R.id.tvTokenStatus)
                tvStatus?.text = "Step ${currentRound}"
            }
            State.SUCCESS -> {
                cancelNotifyCollapse()
                cardSuccess?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
            State.ERROR -> {
                cancelNotifyCollapse()
                cardError?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
        }
    }

    /**
     * Get the icon for the corresponding channel
     */
    @DrawableRes
    private fun getChannelIcon(channel: Channel?): Int {
        return when (channel) {
            Channel.DISCORD -> R.drawable.ic_channel_discord
            Channel.TELEGRAM -> R.drawable.ic_channel_telegram
            Channel.WECHAT -> R.drawable.ic_channel_wechat
            else -> R.drawable.ic_launcher
        }
    }

    /**
     * Auto-reset to IDLE state after 5 seconds
     */
    private fun scheduleAutoReset() {
        cancelAutoReset()
        autoResetRunnable = Runnable {
            setIdleState()
        }
        mainHandler.postDelayed(autoResetRunnable!!, AUTO_RESET_DELAY_MS)
    }

    /**
     * Cancel the auto-reset
     */
    private fun cancelAutoReset() {
        autoResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoResetRunnable = null
        }
    }

    /**
     * Ensure the floating window stays within the visible screen area; correct if out of bounds
     */
    private fun ensureFloatInBounds(view: View) {
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        // Get navigation bar height to ensure the bubble is not covered by the nav bar
        val navBarHeight = getNavigationBarHeight()

        // Method 1: try to find WindowManager.LayoutParams from the view hierarchy
        var wmParams: WindowManager.LayoutParams? = null
        var wmView: View? = view
        while (wmView != null) {
            val lp = wmView.layoutParams
            if (lp is WindowManager.LayoutParams) {
                wmParams = lp
                break
            }
            wmView = wmView.parent as? View
        }

        if (wmParams != null) {
            val floatHeight = (wmView ?: view).height
            val floatWidth = (wmView ?: view).width
            val maxX = (screenWidth - floatWidth).coerceAtLeast(0)
            // Subtract navigation bar height and extra safety margin
            val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
            val clampedX = wmParams.x.coerceIn(0, maxX)
            val clampedY = wmParams.y.coerceIn(0, maxY)
            if (clampedX != wmParams.x || clampedY != wmParams.y) {
                EasyFloat.updateFloat(FLOAT_TAG, clampedX, clampedY)
            }
            savePosition(clampedX, clampedY)
            return
        }

        // Fallback: detect with getLocationOnScreen, correct with updateFloat
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewBottom = location[1] + view.height
        if (viewBottom > screenHeight - navBarHeight || location[1] < 0) {
            val safeY = screenHeight / 3
            EasyFloat.updateFloat(FLOAT_TAG, location[0].coerceIn(0, screenWidth), safeY)
            savePosition(location[0].coerceIn(0, screenWidth), safeY)
        } else {
            savePosition(location[0], location[1])
        }
    }

    private fun getNavigationBarHeight(): Int = BarUtils.getNavBarHeight()

    /** Original width of the circle state (read from layout on first use and cached) */
    private var circleWidthPx: Int = -1

    /** Dynamically change the floating window root layout width (expand to pill / collapse to circle) */
    private fun setFloatRootWidth(view: View, widthPx: Int) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams
        if (lp != null && lp.width != widthPx) {
            lp.width = widthPx
            root.layoutParams = lp
        }
    }

    /** Get the width of the circle state (cached from createdResult to match the XML definition) */
    private fun getCircleWidth(@Suppress("UNUSED_PARAMETER") view: View): Int {
        return if (circleWidthPx > 0) circleWidthPx else WindowManager.LayoutParams.WRAP_CONTENT
    }


    /**
     * Save position
     */
    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    /**
     * Get saved X coordinate
     */
    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    /**
     * Get saved Y coordinate
     */
    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    /**
     * Click callback, can be set externally
     */
    var onFloatClick: () -> Unit = {}
    var onStopTask: () -> Unit = {}
}
