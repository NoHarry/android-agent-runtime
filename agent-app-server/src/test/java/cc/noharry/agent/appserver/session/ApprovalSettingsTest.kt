package cc.noharry.agent.appserver.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The approval mode a person chose is kept across restarts (each start is an [ApprovalSettings.init] with the host's
 * mode); a new installation starts in the host's, and forgetting the choice goes back to it. (In "deny all" a plan's
 * actions do not run when it comes due: Bothy's agent-app-plans pins it.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApprovalSettingsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @After
    fun hostsAgain() = ApprovalSettings.forget()

    @Test
    fun thePersonsChoiceSurvivesARestartUntilTheHostForgetsIt() {
        ApprovalSettings.init(app, ConfirmationMode.ALLOW_ALL_SESSION)
        assertEquals(ConfirmationMode.ALLOW_ALL_SESSION, ApprovalSettings.mode)
        ApprovalSettings.choose(ConfirmationMode.ASK_EVERY_TIME)
        // The process starts again, the host still saying "allow all".
        ApprovalSettings.init(app, ConfirmationMode.ALLOW_ALL_SESSION)
        assertEquals(ConfirmationMode.ASK_EVERY_TIME, ApprovalSettings.mode)
        ApprovalSettings.forget()
        assertEquals(ConfirmationMode.ALLOW_ALL_SESSION, ApprovalSettings.mode)
        ApprovalSettings.init(app, ConfirmationMode.ALLOW_ALL_SESSION)
        assertEquals(ConfirmationMode.ALLOW_ALL_SESSION, ApprovalSettings.mode)
    }
}
