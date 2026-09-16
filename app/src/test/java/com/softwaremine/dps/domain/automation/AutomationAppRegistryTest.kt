package com.softwaremine.dps.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationAppRegistryTest {

    @Test
    fun `resolves the one known Phase 1 test app`() {
        assertEquals("com.softwaremine.dps.automationtarget", AutomationAppRegistry.resolve("test app"))
    }

    @Test
    fun `resolution is case-insensitive`() {
        assertEquals("com.softwaremine.dps.automationtarget", AutomationAppRegistry.resolve("Test App"))
    }

    @Test
    fun `resolution trims surrounding whitespace`() {
        assertEquals("com.softwaremine.dps.automationtarget", AutomationAppRegistry.resolve("  test app  "))
    }

    @Test
    fun `an unknown app name resolves to null, never a guess`() {
        assertNull(AutomationAppRegistry.resolve("whatsapp"))
        assertNull(AutomationAppRegistry.resolve("some random app"))
    }
}
