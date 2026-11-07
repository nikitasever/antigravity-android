package com.google.antigravity

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.network.BridgeCredentials
import org.junit.rules.ExternalResource

/** Trust only the owned, unprotected protocol fixtures; never a production bypass. */
class LocalBridgeFixtureRule(private vararg val ports: Int) : ExternalResource() {
    private val previous = linkedMapOf<String, String?>()
    private val credentials by lazy { BridgeCredentials(InstrumentationRegistry.getInstrumentation().targetContext) }
    override fun before() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(ports.all { it in setOf(8810, 8811, 8814, 8815) })
        for (port in ports) for (host in listOf("127.0.0.1", "10.0.2.2")) {
            val origin = "http://$host:$port"
            previous[origin] = credentials.tokenFor(origin)
            credentials.save(origin, "b".repeat(64))
        }
    }
    override fun after() {
        for ((origin, token) in previous) {
            if (token == null) credentials.forget(origin) else credentials.save(origin, token)
        }
    }
}
