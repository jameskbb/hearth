package io.github.jameskbb.hearth

import android.app.Application

class HearthApp : Application() {
    /** The process-wide dependencies; see [DefaultAppContainer]. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        (container as? DefaultAppContainer)?.onTrimMemory(level)
    }
}

internal fun createContainer(app: HearthApp): AppContainer = DefaultAppContainer(app)
