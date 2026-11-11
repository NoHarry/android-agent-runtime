package cc.noharry.agent.appserver.tools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsTest {
    @Test
    fun ordersAndPaymentsAreToldFromPreviewsAndReadings() {
        assertTrue(isTransaction("mcp__luckin__createOrder"))
        assertTrue(isTransaction("mcp__shop__submit_order"))
        assertTrue(isTransaction("mcp__shop__payOrder"))
        assertFalse(isTransaction("mcp__luckin__previewOrder"))
        assertFalse(isTransaction("mcp__luckin__queryOrderList"))
        assertFalse(isTransaction("mcp__device__home_window_position_set"))
    }
}
