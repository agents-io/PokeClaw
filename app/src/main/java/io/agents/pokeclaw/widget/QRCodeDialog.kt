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
         * Show QR code dialog
         *
         * @param context context
         * @param title title (required)
         * @param subtitle subtitle (optional)
         * @param qrBitmap QR code bitmap
         * @param onClose close callback (optional)
         * @return QRCodeDialog instance
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
         * Show QR code dialog (load image via URL)
         *
         * @param context context
         * @param title title (required)
         * @param subtitle subtitle (optional)
         * @param qrImageUrl QR code image URL
         * @param onClose close callback (optional)
         * @return QRCodeDialog instance
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

        // Configure dialog style
        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        // Callback on dismiss (back key, close button tap, code dismiss, etc.)
        setOnDismissListener { onCloseListener?.invoke() }

        // Initialize views
        initViews()
    }

    private fun initViews() {
        // Title
        findViewById<TextView>(R.id.tvTitle).apply {
            text = this@QRCodeDialog.title
        }

        // Subtitle
        findViewById<TextView>(R.id.tvSubtitle).apply {
            if (!this@QRCodeDialog.subtitle.isNullOrEmpty()) {
                text = this@QRCodeDialog.subtitle
                visibility = android.view.View.VISIBLE
            } else {
                visibility = android.view.View.GONE
            }
        }

        // QR code image
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

        // Close button
        findViewById<KButton>(R.id.btnClose).apply {
            setOnClickListener { dismiss() }
        }
    }

    /**
     * Update QR code bitmap
     */
    fun updateQrBitmap(bitmap: Bitmap) {
        this.qrBitmap = bitmap
        this.qrImageUrl = null
        findViewById<ImageView>(R.id.ivQrCode)?.setImageBitmap(bitmap)
    }

    /**
     * Update QR code image via URL
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
     * Set the close callback
     */
    fun setOnCloseListener(listener: () -> Unit) {
        this.onCloseListener = listener
    }

    /**
     * Show the status overlay (for displaying states like "Expired", "Scanned", etc.)
     *
     * @param text status text to display
     */
    fun showStatusOverlay(text: String) {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.apply {
            visibility = android.view.View.VISIBLE
            findViewById<TextView>(R.id.tvStatusText)?.text = text
        }
    }

    /**
     * Hide the status overlay
     */
    fun hideStatusOverlay() {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.visibility = android.view.View.GONE
    }

    /**
     * Update the status text (overlay stays visible)
     *
     * @param text new status text
     */
    fun updateStatusText(text: String) {
        findViewById<TextView>(R.id.tvStatusText)?.text = text
    }
}
