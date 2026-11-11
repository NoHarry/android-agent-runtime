package cc.noharry.agent.appserver.model
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.saveSelectedModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh")
class ProviderModelCatalogTest {
    @Test fun catalogPersistsForComposerAndNeverSelectsAModelByItself() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ProviderConfig.saveNewProfile(context, "Catalog", ModelProvider.OPENAI,
            ProviderEndpoint.CHAT_COMPLETIONS, "", "", "http://127.0.0.1:9/v1")
        val editor = config.editState(config.activeProfile)
        assertEquals("", editor.model)
        assertFalse(config.isReady(config.activeProfile))
        assertTrue(config.missingMessage(context, config.activeProfile).contains("选择模型"))
        saveProviderModelList(context, editor, listOf(" fetched ", "manual", "fetched", ""))
        val restored = ProviderConfig.load(context)
        val controller = ModelOptionsController {}
        controller.refresh(context, restored.activeProfile, restored)
        assertEquals(listOf("fetched", "manual"), controller.options(restored.activeProfile, restored.activeProfile.model))
        assertEquals("", restored.activeProfile.model)
        val selected = requireNotNull(saveSelectedModel(context, restored.activeProfile, restored, "manual"))
        assertEquals("manual", selected.activeProfile.model)
        assertTrue(selected.isReady(selected.activeProfile))
        assertEquals(listOf("fetched", "manual"), loadProviderModels(context, editor))
        ProviderConfig.deleteSavedProfile(context, editor.profileId)
        deleteProviderModels(context, editor.profileId)
    }

    @Test fun catalogIsScopedByProviderConnectionAndAliasDeletionRemovesOnlyItsCatalogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = ProviderEditState("", "", "https://example.test", profileId = "manual-one")
        val b = a.copy(profileId = "manual-two")
        saveProviderModelList(context, a, listOf("a"))
        saveProviderModelList(context, b, listOf("b"))
        assertEquals(listOf("a"), loadProviderModels(context, a.copy(profileId = "manual_one")))
        assertTrue(loadProviderModels(context, a.copy(baseUrl = "https://other.test")).isEmpty())
        assertTrue(loadProviderModels(context, a.copy(endpoint = ProviderEndpoint.RESPONSES)).isEmpty())
        deleteProviderModels(context, "manual_one")
        assertTrue(loadProviderModels(context, a).isEmpty())
        assertEquals(listOf("b"), loadProviderModels(context, b))
    }
}
