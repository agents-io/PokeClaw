package com.apk.claw.android.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.ui.chat.ChatActivity

/**
 * 启动页 - 始终进入首页，未配置 LLM 也可进入，可在设置中配置
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* 启动页不允许返回 */ }
        })

        val intent = Intent(this, ChatActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
