package com.moviebox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MovieboxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Moviebox())
    }
}
