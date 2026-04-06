// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.widget.CommonToolbar

/**
 * Web 页面 - 通用浏览器
 */
class WebActivity : BaseActivity() {

    private lateinit var toolbar: CommonToolbar
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"

        /**
         * 打开 Web 页面
         * @param context 上下文
         * @param url 网页地址
         * @param title 默认标题（可选，如果不传则显示网页标题）
         */
        fun start(context: Context, url: String, title: String? = null) {
            val intent = Intent(context, WebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }

        val defaultTitle = intent.getStringExtra(EXTRA_TITLE)

        initToolbar(defaultTitle)
        initProgressBar()
        initWebView(url)
    }

    private fun initToolbar(defaultTitle: String?) {
        toolbar = findViewById(R.id.toolbar)
        toolbar.apply {
            // 设置默认标题（如果有）
            if (!defaultTitle.isNullOrEmpty()) {
                setTitle(defaultTitle)
            }
            // 显示返回按钮
            showBackButton(true) { finish() }
        }
    }

    private fun initProgressBar() {
        progressBar = findViewById(R.id.progressBar)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String) {
        webView = findViewById(R.id.webView)
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    url?.let { view?.loadUrl(it) }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    // 网页加载完成后，回显网页标题
                    if (!title.isNullOrEmpty()) {
                        toolbar.setTitle(title)
                    }
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.progress = newProgress
                    // 加载完成时隐藏进度条
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }

            loadUrl(url)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
