package com.google.antigravity.googlepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteLedgerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun ledger() = RouteLedger(folder.newFile("routes.json"))

    @Test
    fun `default order is kept until something is measured`() {
        val ledger = ledger()
        ledger.load("wifi")
        assertEquals(GoogleProConfig.DEFAULT_ROUTE_ORDER, ledger.order("wifi"))
    }

    @Test
    fun `a route that answered beats a faster route that refused`() {
        val ledger = ledger()
        ledger.load("wifi")
        ledger.success(GateRoute.UDP_GEOHIDE, 900)
        ledger.refusal(GateRoute.DOH_DNS_AI)
        val order = ledger.order("wifi")
        assertEquals(GateRoute.UDP_GEOHIDE, order.first())
        assertEquals(GateRoute.DOH_DNS_AI, order.last())
    }

    @Test
    fun `a route that answered is not punished by an older refusal`() {
        val ledger = ledger()
        ledger.load("wifi")
        ledger.refusal(GateRoute.DIRECT)
        ledger.success(GateRoute.DIRECT, 300)
        assertEquals(GateRoute.DIRECT, ledger.order("wifi").first())
        assertEquals(emptyList<GateRoute>(), ledger.refusedRoutes())
    }

    @Test
    fun `a new network keeps timings but forgets the verdicts`() {
        val ledger = ledger()
        ledger.load("wifi")
        ledger.success(GateRoute.DOH_DNS_AI, 120)
        ledger.refusal(GateRoute.DIRECT)
        val order = ledger.order("cell")
        assertEquals(GoogleProConfig.DEFAULT_ROUTE_ORDER, order)
    }

    @Test
    fun `evidence survives a reload on the same network`() {
        val file = folder.newFile("routes.json")
        RouteLedger(file).apply { load("wifi"); success(GateRoute.UDP_GEOHIDE, 500) }
        val reloaded = RouteLedger(file)
        reloaded.load("wifi")
        assertEquals(GateRoute.UDP_GEOHIDE, reloaded.order("wifi").first())
    }

    @Test
    fun `repeated refusals push a route further back`() {
        val ledger = ledger()
        ledger.load("wifi")
        repeat(3) { ledger.refusal(GateRoute.DIRECT) }
        ledger.refusal(GateRoute.UDP_GEOHIDE)
        val order = ledger.order("wifi")
        assertTrue(order.indexOf(GateRoute.UDP_GEOHIDE) < order.indexOf(GateRoute.DIRECT))
        assertEquals(GateRoute.DIRECT, order.last())
    }
}
