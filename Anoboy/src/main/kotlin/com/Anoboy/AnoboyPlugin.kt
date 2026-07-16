package com.anoboy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class AnoboyPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anoboy())
		registerExtractorAPI(Gofile())
    }
}