package io.github.jameskbb.hearth.system

/**
 * Entry points into the platform's own settings. Each route is only offered when it
 * resolves on this device, so the UI never shows a control that does nothing.
 */
enum class SystemRoute {
    /** The device's main settings screen. */
    Settings,
}

interface SystemRoutes {
    fun isAvailable(route: SystemRoute): Boolean
    /** Returns false if the route could not be opened (the UI then says so). */
    fun open(route: SystemRoute): Boolean
}
