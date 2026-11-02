package com.google.antigravity

import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.describeMcpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Этап 3 (EXTENSIONS_INTEGRATION_PLAN.md): статус MCP-сервера честен.
 * «connected» означает только реальное успешное MCP-рукопожатие
 * (initialize → initialized → tools/list); «configured» — настроен, но
 * не проверялся, и никогда не показывается как подключённый.
 */
class McpLiveStatusTest {
    @Test fun connectedStatusDescribesRealHandshake() {
        assertEquals("подключён (рукопожатие выполнено)", describeMcpStatus("connected"))
    }

    @Test fun configuredIsNeverShownAsConnected() {
        assertEquals("настроен (не проверен)", describeMcpStatus("configured"))
        assertNotEquals("подключён (рукопожатие выполнено)", describeMcpStatus("configured"))
    }

    @Test fun failedAndDisabledAreDistinct() {
        assertEquals("проверка не пройдена", describeMcpStatus("failed"))
        assertEquals("отключён", describeMcpStatus("disabled"))
    }

    @Test fun unknownStatusIsNeverInvented() {
        assertEquals("custom-state", describeMcpStatus("custom-state"))
    }

    @Test fun defaultItemIsNotConnectedBeforeProbe() {
        val item = PcMcpServerItem(id = "x", name = "X")
        assertEquals("до проверки времени рукопожатия нет", "", item.probedAt)
        assertNotEquals("по умолчанию сервер не «подключён»", "connected", item.status)
        assertEquals("инструменты не выдумываются до tools/list", emptyList<String>(), item.tools)
    }
}
