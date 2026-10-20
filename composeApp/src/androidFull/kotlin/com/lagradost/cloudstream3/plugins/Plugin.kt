package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/** Android host contract used by compiled CloudStream extensions. */
abstract class Plugin : BasePlugin() {
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null
    var openSettings: ((Context) -> Unit)? = null
}
