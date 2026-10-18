package com.liteagent.app

import android.app.Application

/**
 * 应用入口（Android 的 Application 类，进程启动时第一个被创建）。
 *
 * 【它做什么】
 * 只做一件事：把自己存进 [instance]。
 *
 * 【为什么需要这个单例】
 * 部分系统组件（如文件选择器的回调、通知的点击事件）拿不到 ViewModel，
 * 却需要访问应用级的对象（例如 ModelStore 所在的 filesDir）。
 * 通过 `LiteAgentApp.instance` 可以在任何地方拿到 Context，
 * 而不用把这些组件的构造函数全部改造成传 Context 的形式。
 *
 * 注意：不要在这里做耗时初始化（模型加载、磁盘扫描都放在
 * ChatViewModel 的 bootstrap 里，带「加载中」的 UI 反馈）。
 * Application.onCreate 阻塞会直接拖慢冷启动。
 */
class LiteAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        /** 全局唯一的 Application 引用，App 进程存活期间始终有效 */
        lateinit var instance: LiteAgentApp
            private set
    }
}
