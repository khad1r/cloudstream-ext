package com.moviebox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class MovieboxPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(Moviebox())
    }
}
