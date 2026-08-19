package com.fourkhdhub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FourKHDHubProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FourKHDHubProvider())
        registerExtractorAPI(HubCloudExtractor())
        registerExtractorAPI(HubDriveExtractor())
        registerExtractorAPI(HblinksExtractor())
        registerExtractorAPI(HubCdnExtractor())
        registerExtractorAPI(HdStream4uExtractor())
        registerExtractorAPI(HubstreamExtractor())
        registerExtractorAPI(PixelDrainDevExtractor())
    }
}
