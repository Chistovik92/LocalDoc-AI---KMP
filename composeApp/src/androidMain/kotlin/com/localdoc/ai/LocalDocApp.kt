package com.localdoc.ai

import android.app.Application
import com.localdoc.ai.platform.AppContextHolder

/**
 * Контекст приложения доступен до Activity: виджет и сервис плеера могут
 * обратиться к AppGraph, когда процесс поднят системой без открытия экрана.
 */
class LocalDocApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
