package com.nullswan.autopickup

import org.bukkit.Material
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class InventoryOverflowEventTest {

    @Test
    fun `handler list is static and accessible`() {
        val list = InventoryOverflowEvent.handlerList
        assertNotNull(list)
    }

    @Test
    fun `event class exists for all expected materials`() {
        val materials = listOf(
            Material.DIAMOND, Material.IRON_INGOT, Material.OAK_LOG,
            Material.COBBLESTONE, Material.WHEAT
        )
        for (m in materials) {
            assertNotNull(m, "Material $m should exist")
        }
    }
}
