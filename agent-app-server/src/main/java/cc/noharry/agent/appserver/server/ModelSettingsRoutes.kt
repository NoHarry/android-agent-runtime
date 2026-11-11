package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.model.FetchFallback
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.SearchBackend
import cc.noharry.agent.appserver.model.contextWindowOverride
import cc.noharry.agent.appserver.model.defaultContextWindow
import cc.noharry.agent.appserver.model.hasWebLookup
import cc.noharry.agent.appserver.model.loadModelImageInput
import cc.noharry.agent.appserver.model.saveContextWindow
import cc.noharry.agent.appserver.model.saveModelImageInput
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsResult
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.WebLookupView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Model and service settings beyond the profile: each model's context window and image input, and web search / the
 * fetch fallback through a service's lookup APIs ([ProviderAdapter][cc.noharry.agent.appserver.model.ProviderAdapter]);
 * a service's own requests are its adapter's.
 */
internal class ModelSettingsRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(ModelSettingsRequests.MODELS_READ) { params ->
            withContext(Dispatchers.IO) {
                ModelSettingsResult(
                    params.models.map { model ->
                        val profile = params.profile.copy(model = model)
                        ModelSettingsView(
                            model = model,
                            windowOverride = contextWindowOverride(app, profile),
                            defaultWindow = defaultContextWindow(model),
                            imageInput = loadModelImageInput(app, profile),
                        )
                    },
                )
            }
        }
        on(ModelSettingsRequests.MODELS_SAVE) { params ->
            params.models.forEach { settings ->
                val profile = params.profile.copy(model = settings.model)
                saveContextWindow(app, profile, settings.windowOverride)
                saveModelImageInput(app, profile, settings.imageInput)
            }
            Empty
        }
        lookup()
    }

    private fun RouteTable.lookup() {
        on(ModelSettingsRequests.LOOKUP_READ) { withContext(Dispatchers.IO) { lookupView() } }
        on(ModelSettingsRequests.LOOKUP_SET_SEARCH_API) { params ->
            withContext(Dispatchers.IO) {
                SearchBackend.set(app, ProviderConfig.load(app).activeProfile.id, params.on)
                lookupView()
            }
        }
        on(ModelSettingsRequests.LOOKUP_SET_FETCH_FALLBACK) { params ->
            withContext(Dispatchers.IO) {
                FetchFallback.set(app, params.on)
                lookupView()
            }
        }
    }

    private fun lookupView(): WebLookupView {
        val config = ProviderConfig.load(app)
        val profile = config.activeProfile
        return WebLookupView(
            profileId = profile.id,
            profileLabel = profile.displayLabel,
            searchApi = SearchBackend.usesApi(app, profile.id),
            // The on-device model and the No web_search profile have no search of their own.
            ownSearch = profile.serverSideSearch && profile.searchCapability != "none",
            fetchFallback = FetchFallback.enabled(app),
            lookupKey = config.hasWebLookup(app),
        )
    }
}
