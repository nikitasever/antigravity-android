package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class RemoteSessionFilterTest {
    @Test fun cloudNeverPretendsPcOrPhoneSessionsAreCloudWorkspaces() {
        assertFalse(RemoteEnvFilter.CLOUD.matches(RemoteSessionItem("pc", "PC")))
        assertFalse(RemoteEnvFilter.CLOUD.matches(RemoteSessionItem("phone", "Phone", hostType = "PHONE")))
        assertTrue(RemoteEnvFilter.CLOUD.matches(RemoteSessionItem("cloud", "Cloud", hostType = "CLOUD")))
    }
    @Test fun workstationFilterMatchesItsActualHost() {
        assertTrue(RemoteEnvFilter.PC_WIN11.matches(RemoteSessionItem("win", "Work", host = "win-11")))
        assertFalse(RemoteEnvFilter.PC_WIN11.matches(RemoteSessionItem("other", "Other", host = "another-pc")))
        assertTrue(RemoteEnvFilter.PC_CR05318.matches(RemoteSessionItem("other", "Other", host = "another-pc")))
    }
}
