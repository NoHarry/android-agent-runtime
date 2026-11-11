package cc.noharry.agent.appserver.model
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.saveProviderOverrideChoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderSaveFlowTest {
    @Test fun keylessManualProfileIsSelectedAfterSaveReloadAndCanBeDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = ProviderConfig.load(context)
        val created = ProviderConfig.saveNewProfile(
            context, "Manual smoke", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS,
            "", "private-model", "http://127.0.0.1:9/v1",
        )
        val profile = created.activeProfile
        assertEquals("Manual smoke", profile.label)
        assertEquals("private-model", profile.model)
        assertTrue(profile.id.isManualProfileId())
        assertEquals(profile.id, ProviderConfig.load(context).activeProfile.id)
        assertEquals(before.profiles.size + 1, created.profiles.size)
        assertEquals(before.profiles.size, ProviderConfig.deleteSavedProfile(context, profile.id).profiles.size)
    }

    @Test fun savedPreferenceAliasDoesNotDuplicatePackagedProfile() {
        val profile = ProviderProfile("deepseek-api", "DeepSeek", ModelProvider.OPENAI, "", "model", "https://example.test")
        val profiles = listOf(profile).withSavedProfiles(mapOf(
            "deepseek_api" to ProviderProfileOverride(provider = ModelProvider.OPENAI, apiKey = "placeholder", baseUrl = "https://example.test"),
        ))
        assertEquals(1, profiles.size)
        assertEquals("deepseek-api", profiles.single().id)
        assertEquals("placeholder", profiles.single().apiKey)
        val selection = resolveProviderSelection(emptyMap(), profiles, true, "deepseek_api")
        assertEquals("deepseek-api", selection.activeProfile.id)
    }
    @Test fun savedProviderTypeAndEndpointRoundTripWithoutChangingItsIdentityOrModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = ProviderConfig.saveNewProfile(context, "Switch protocol", ModelProvider.OPENAI,
            ProviderEndpoint.CHAT_COMPLETIONS, "placeholder", "existing-model", "https://example.test")
        val original = created.activeProfile
        val anthropic = saveProviderOverrideChoice(context, original, created,
            endpoint = ProviderEndpoint.MESSAGES, selectedProvider = ModelProvider.ANTHROPIC)
        assertEquals(original.id, anthropic.activeProfile.id)
        assertEquals(ModelProvider.ANTHROPIC, anthropic.activeProfile.provider)
        assertEquals(ProviderEndpoint.MESSAGES, anthropic.activeProfile.endpoint)
        assertEquals("anthropic-server-tool", anthropic.activeProfile.searchCapability)
        assertEquals(original.apiKey, anthropic.activeProfile.apiKey)
        assertEquals(original.baseUrl, anthropic.activeProfile.baseUrl)
        assertEquals(original.model, anthropic.activeProfile.model)
        saveProviderOverrideChoice(context, anthropic.activeProfile, anthropic,
            endpoint = ProviderEndpoint.RESPONSES, selectedProvider = ModelProvider.OPENAI)
        val restored = ProviderConfig.load(context).activeProfile
        assertEquals(original.id, restored.id)
        assertEquals(ModelProvider.OPENAI, restored.provider)
        assertEquals(ProviderEndpoint.RESPONSES, restored.endpoint)
        assertEquals("openai-responses-tool", restored.searchCapability)
        assertEquals(original.model, restored.model)
        ProviderConfig.deleteSavedProfile(context, original.id)
    }

}
