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
import io.agents.pokeclaw.utils.KVUtils
import com.blankj.utilcode.util.BarUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils

/**
 * 圆形悬浮窗管理器
 * 使用 EasyFloat 实现可拖动、记录位置的圆形悬浮窗
 * 支持多种状态：等待任务(IDLE)、任务执行中(RUNNING)、任务成功(SUCCESS)、任务失败(ERROR)
 */
object FloatingCircleManager {

    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"
    private const val AUTO_RESET_DELAY_MS = 5000L // 5秒后自动重置

    /**
     * 悬浮窗状态
     */
    enum class State {
        IDLE,           // 等待任务（默认）
        TASK_NOTIFY,    // 收到任务通知（胶囊展开）
        RUNNING,        // 任务执行中
        SUCCESS,        // 任务完成
        ERROR           // 任务失败
    }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var currentChannel: Channel? = null

    private const val TASK_NOTIFY_DURATION_MS = 3000L // 任务通知显示 3 秒后收回

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoResetRunnable: Runnable? = null
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""

    private var appRef: Application? = null

    /**
     * 显示悬浮窗
     * @param application Application 实例
     * @param x 初始位置 X（可选，默认屏幕右边偏中心）
     * @param y 初始位置 Y（可选，默认屏幕中心）
     */
    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        if (isShowing) {
            return
        }
        appRef = application

        // 计算默认位置：屏幕中心的右边
        val screenWidth = DisplayUtils.getScreenWidth(application)
        val screenHeight = DisplayUtils.getScreenHeight(application)
        val defaultX = 0
        val defaultY = screenHeight / 2

        // 从本地读取保存的位置
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
                    // 缓存圆形原始宽度（必须在任何 setFloatRootWidth 之前）
                    view?.findViewById<View>(R.id.floatRoot)?.let { root ->
                        if (circleWidthPx <= 0) {
                            circleWidthPx = root.layoutParams?.width ?: -1
                        }
                    }
                    // 点击事件
                    view?.setOnClickListener {
                        onFloatClick()
                    }
                    // 初始化状态
                    updateStateView(view, currentState)
                    // 布局完成后检测位置，防止圆球卡在屏幕外
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
                    // 拖动结束，修正位置并保存
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
     * 隐藏悬浮窗
     */
    fun hide() {
        if (isShowing) {
            EasyFloat.dismiss(FLOAT_TAG)
            isShowing = false
        }
    }

    /**
     * 判断是否显示中
     */
    fun isShowing(): Boolean = isShowing

    /**
     * 切换到等待任务状态（默认）
     */
    fun setIdleState() {
        ThreadUtils.runOnUiThread {
            setState(State.IDLE)
        }
    }

    /**
     * 显示任务通知：悬浮窗展开为胶囊，显示任务内容，3 秒后自动收回进入 RUNNING 状态。
     * @param taskText 任务文本（会截断显示）
     * @param channel 消息来源渠道
     */
    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            // 3 秒后自动收回为 RUNNING（直接 setState，绕过 TASK_NOTIFY 守卫）
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
     * 切换到任务执行中状态
     * @param round 当前轮数
     * @param channel 消息来源渠道
     */
    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentChannel = channel
            // 如果正在显示任务通知胶囊，只更新数据，不切换 UI（等定时器到期自动切）
            if (currentState == State.TASK_NOTIFY) {
                return@runOnUiThread
            }
            setState(State.RUNNING)
        }
    }

    /**
     * 切换到任务完成状态（5秒后自动回到 IDLE）
     */
    fun setSuccessState() {
        ThreadUtils.runOnUiThread {
            setState(State.SUCCESS)
            scheduleAutoReset()
        }
    }

    /**
     * 切换到任务失败状态（5秒后自动回到 IDLE）
     */
    fun setErrorState() {
        ThreadUtils.runOnUiThread {
            setState(State.ERROR)
            scheduleAutoReset()
        }

    }

    /**
     * 设置状态
     */
    private fun setState(state: State) {
        currentState = state
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        view?.let { updateStateView(it, state) }
    }

    /**
     * 更新视图状态
     */
    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardTaskNotify = view.findViewById<View>(R.id.cardTaskNotify)
        val cardRunning = view.findViewById<View>(R.id.cardRunning)
        val cardSuccess = view.findViewById<View>(R.id.cardSuccess)
        val cardError = view.findViewById<View>(R.id.cardError)

        // 隐藏所有状态
        cardIdle?.visibility = View.GONE
        cardTaskNotify?.visibility = View.GONE
        cardRunning?.visibility = View.GONE
        cardSuccess?.visibility = View.GONE
        cardError?.visibility = View.GONE

        // 取消之前的自动重置
        cancelAutoReset()

        // 显示对应状态
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
                // 展开为 wrap_content
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            State.RUNNING -> {
                cancelNotifyCollapse()
                // 收回为固定圆形
                setFloatRootWidth(view, getCircleWidth(view))
                cardRunning?.visibility = View.VISIBLE
                // 更新轮数显示
                val tvRound = view.findViewById<TextView>(R.id.tvRound)
                tvRound?.text = currentRound.toString()
                // 更新渠道 Logo
                val ivChannelLogo = view.findViewById<ImageView>(R.id.ivChannelLogo)
                ivChannelLogo?.setImageResource(getChannelIcon(currentChannel))
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
     * 获取渠道对应的图标
     */
    @DrawableRes
    private fun getChannelIcon(channel: Channel?): Int {
        return when (channel) {
            Channel.DINGTALK -> R.drawable.ic_channel_dingtalk
            Channel.FEISHU -> R.drawable.ic_channel_feishu
            Channel.QQ -> R.drawable.ic_channel_qq
            Channel.DISCORD -> R.drawable.ic_channel_discord
            Channel.TELEGRAM -> R.drawable.ic_channel_telegram
            Channel.WECHAT -> R.drawable.ic_channel_wechat
            else -> R.drawable.ic_launcher
        }
    }

    /**
     * 5秒后自动重置到 IDLE 状态
     */
    private fun scheduleAutoReset() {
        cancelAutoReset()
        autoResetRunnable = Runnable {
            setIdleState()
        }
        mainHandler.postDelayed(autoResetRunnable!!, AUTO_RESET_DELAY_MS)
    }

    /**
     * 取消自动重置
     */
    private fun cancelAutoReset() {
        autoResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoResetRunnable = null
        }
    }

    /**
     * 确保悬浮窗在屏幕可见范围内，超出则修正
     */
    private fun ensureFloatInBounds(view: View) {
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        // 获取导航栏高度，确保圆球不会被导航栏遮挡
        val navBarHeight = getNavigationBarHeight()

        // 方式1：尝试从 view 层级找到 WindowManager.LayoutParams
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
            // 减去导航栏高度和额外安全边距
            val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
            val clampedX = wmParams.x.coerceIn(0, maxX)
            val clampedY = wmParams.y.coerceIn(0, maxY)
            if (clampedX != wmParams.x || clampedY != wmParams.y) {
                EasyFloat.updateFloat(FLOAT_TAG, clampedX, clampedY)
            }
            savePosition(clampedX, clampedY)
            return
        }

        // 兜底：用 getLocationOnScreen 检测，updateFloat 修正
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

    /** 圆形状态的原始宽度（首次从 layout 读取并缓存） */
    private var circleWidthPx: Int = -1

    /** 动态修改悬浮窗根布局宽度（展开胶囊 / 收回圆形） */
    private fun setFloatRootWidth(view: View, widthPx: Int) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams
        if (lp != null && lp.width != widthPx) {
            lp.width = widthPx
            root.layoutParams = lp
        }
    }

    /** 获取圆形状态的宽度（createdResult 时缓存，确保与 XML 定义一致） */
    private fun getCircleWidth(@Suppress("UNUSED_PARAMETER") view: View): Int {
        return if (circleWidthPx > 0) circleWidthPx else WindowManager.LayoutParams.WRAP_CONTENT
    }


    /**
     * 保存位置
     */
    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    /**
     * 获取保存的 X 坐标
     */
    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    /**
     * 获取保存的 Y 坐标
     */
    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    /**
     * 点击回调，可以在外部设置
     */
    var onFloatClick: () -> Unit = {}
}
