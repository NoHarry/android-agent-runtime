package cc.noharry.agent.appserver.models

import cc.noharry.agent.appserver.model.ModelService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host guide's table of the services (`agent-app-server/README.md`, between its `services:start` and `services:end`
 * markers) is the data file's, row for row, and the guide has one line saying which services were run against the
 * service itself (between `real-services:start` and `real-services:end`).
 */
class GuideServicesTableTest {
    private val guide = File("../agent-app-server/README.md").readLines()

    @Test
    fun theGuidesTableIsTheData() {
        assertEquals(table(ServiceCatalog.services), between("<!-- services:start -->", "<!-- services:end -->"))
    }

    @Test
    fun theGuideSaysInOneLineWhichServicesRanAgainstTheServiceItself() {
        val line = between("<!-- real-services:start -->", "<!-- real-services:end -->")
        assertEquals(1, line.size)
        assertTrue(line.single(), line.single().startsWith("**Run against the services themselves:**"))
    }

    private fun between(start: String, end: String): List<String> {
        val from = guide.indexOf(start)
        val to = guide.indexOf(end)
        assertTrue("the guide has no $start … $end", from in 0 until to)
        return guide.subList(from + 1, to)
    }

    /** The table as the data gives it: one row per service, in the file's order. */
    private fun table(services: List<ModelService>): List<String> = listOf(
        "| Service | `id` | Endpoints, the default first | Models in the data, with facts and prices | Model list |",
        "|---|---|---|---|---|",
    ) + services.map { service ->
        val endpoints = service.endpoints.joinToString("<br>") { endpoint ->
            "${endpoint.endpoint.wireName} `${endpoint.baseUrl}`" + if (endpoint.region.isEmpty()) "" else " (${endpoint.region})"
        }
        val currency = ServiceCatalog.load().services.single { it.id == service.id }.currency
        val models = if (service.models.isEmpty()) "none: its own list has them" else service.models.joinToString(", ") { "`${it.id}`" } + " ($currency)"
        val list = when {
            !service.listsModels -> "none documented: the models in the data"
            service.modelsUrl != null -> "`${service.modelsUrl}`"
            else -> "`<base URL>/models`"
        }
        "| ${service.label} | `${service.id}` | $endpoints | $models | $list |"
    }
}
