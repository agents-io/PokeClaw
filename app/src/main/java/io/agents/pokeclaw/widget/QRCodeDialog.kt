// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import io.agents.pokeclaw.R
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import io.agents.pokeclaw.widget.KButton

class QRCodeDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var title: String = ""
    private var subtitle: String? = null
    private var qrBitmap: Bitmap? = null
    private var qrImageUrl: String? = null
    private var onCloseListener: (() -> Unit)? = null

    companion object {
        /**
         * 显示二维码对话框
         *
         * @param context 上下文
         * @param title 标题（必填）
         * @param subtitle 副标题（可选）
         * @param qrBitmap 二维码图片
         * @param onClose 关闭回调（可选）
         * @return QRCodeDialog 实例
         */
        @JvmStatic
        @JvmOverloads
        fun show(
            context: Context,
            title: String,
            subtitle: String? = null,
            qrBitmap: Bitmap,
            onClose: (() -> Unit)? = null
        ): QRCodeDialog {
            return QRCodeDialog(context).apply {
                this.title = title
                this.subtitle = subtitle
                this.qrBitmap = qrBitmap
                this.onCloseListener = onClose
                show()
            }
        }

        /**
         * 显示二维码对话框（通过 URL 加载图片）
         *
         * @param context 上下文
         * @param title 标题（必填）
         * @param subtitle 副标题（可选）
         * @param qrImageUrl 二维码图片 URL
         * @param onClose 关闭回调（可选）
         * @return QRCodeDialog 实例
         */
        @JvmStatic
        @JvmOverloads
        fun showWithUrl(
            context: Context,
            title: String,
            subtitle: String? = null,
            qrImageUrl: String,
            onClose: (() -> Unit)? = null
        ): QRCodeDialog {
            return QRCodeDialog(context).apply {
                this.title = title
                this.subtitle = subtitle
                this.qrImageUrl = qrImageUrl
                this.onCloseListener = onClose
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_qr_code)

        // 设置对话框样式
        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        // 关闭时回调（包括返回键、点击关闭、代码 dismiss 等）
        setOnDismissListener { onCloseListener?.invoke() }

        // 初始化视图
        initViews()
    }

    private fun initViews() {
        // 标题
        findViewById<TextView>(R.id.tvTitle).apply {
            text = this@QRCodeDialog.title
        }

        // 副标题
        findViewById<TextView>(R.id.tvSubtitle).apply {
            if (!this@QRCodeDialog.subtitle.isNullOrEmpty()) {
                text = this@QRCodeDialog.subtitle
                visibility = android.view.View.VISIBLE
            } else {
                visibility = android.view.View.GONE
            }
        }

        // 二维码图片
        findViewById<ImageView>(R.id.ivQrCode).apply {
            when {
                this@QRCodeDialog.qrBitmap != null -> {
                    setImageBitmap(this@QRCodeDialog.qrBitmap)
                }
                !this@QRCodeDialog.qrImageUrl.isNullOrEmpty() -> {
                    Glide.with(context)
                        .load(this@QRCodeDialog.qrImageUrl)
                        .into(this)
                }
            }
        }

        // 关闭按钮
        findViewById<KButton>(R.id.btnClose).apply {
            setOnClickListener { dismiss() }
        }
    }

    /**
     * 更新二维码图片
     */
    fun updateQrBitmap(bitmap: Bitmap) {
        this.qrBitmap = bitmap
        this.qrImageUrl = null
        findViewById<ImageView>(R.id.ivQrCode)?.setImageBitmap(bitmap)
    }

    /**
     * 通过 URL 更新二维码图片
     */
    fun updateQrImageUrl(url: String) {
        this.qrBitmap = null
        this.qrImageUrl = url
        findViewById<ImageView>(R.id.ivQrCode)?.let { imageView ->
            Glide.with(context)
                .load(url)
                .into(imageView)
        }
    }

    /**
     * 设置关闭回调
     */
    fun setOnCloseListener(listener: () -> Unit) {
        this.onCloseListener = listener
    }

    /**
     * 显示状态覆盖层（用于显示"已过期"、"已扫描"等状态）
     *
     * @param text 要显示的状态文本
     */
    fun showStatusOverlay(text: String) {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.apply {
            visibility = android.view.View.VISIBLE
            findViewById<TextView>(R.id.tvStatusText)?.text = text
        }
    }

    /**
     * 隐藏状态覆盖层
     */
    fun hideStatusOverlay() {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.visibility = android.view.View.GONE
    }

    /**
     * 更新状态文本（覆盖层保持显示）
     *
     * @param text 新的状态文本
     */
    fun updateStatusText(text: String) {
        findViewById<TextView>(R.id.tvStatusText)?.text = text
    }
}
