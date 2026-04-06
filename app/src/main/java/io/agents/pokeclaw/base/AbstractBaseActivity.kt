package io.agents.pokeclaw.base

import android.os.Bundle
import android.view.View

/**
 * Activity基类
 */
abstract class AbstractBaseActivity : BaseActivity() {
    protected open var TAG: String? = this::class.java.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(setContentLayout())
        initView(savedInstanceState)
        initData()
        setListener()
        loadData()
    }


    /**
     * 设置布局
     */
    open abstract fun setContentLayout(): View

    /**
     * 初始化布局
     * @param savedInstanceState Bundle?
     */
    abstract fun initView(savedInstanceState: Bundle?)

    /**
     * 初始化数据
     */
    open fun initData() {

    }

    open fun setListener() {

    }

    open fun loadData() {


    }

}