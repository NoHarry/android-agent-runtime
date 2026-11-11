package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import org.junit.Assert.assertEquals
import org.junit.Test
class ConfirmationDomainsTest {
    @Test fun hostModesMapToSdkPolicies() {
        assertEquals(ApprovalPolicy.ASK, ConfirmationMode.ASK_EVERY_TIME.approvalPolicy())
        assertEquals(ApprovalPolicy.REMEMBER, ConfirmationMode.ALLOW_CONFIRMED_DOMAINS.approvalPolicy())
        assertEquals(ApprovalPolicy.APPROVE_ALL, ConfirmationMode.ALLOW_ALL_SESSION.approvalPolicy())
        assertEquals(ApprovalPolicy.DENY_ALL, ConfirmationMode.DENY_ALL.approvalPolicy())
    }
}
