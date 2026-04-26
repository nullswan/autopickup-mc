package com.nullswan.autopickup

import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.plugin.Plugin

class AutoPickupListener(
    private val plugin: Plugin,
    private val logger: PickupLogger
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onBlockDrop(event: BlockDropItemEvent) {
        val player = event.player
        val items = event.items.toList()

        for (drop in items) {
            val itemStack = drop.itemStack
            if (itemStack.type.isAir) continue

            val leftover = player.inventory.addItem(itemStack)
            if (leftover.isEmpty()) {
                logger.onPickup(player, itemStack.type, itemStack.amount, cloud = false)
            } else {
                val added = itemStack.amount - leftover.values.sumOf { it.amount }
                if (added > 0) logger.onPickup(player, itemStack.type, added, cloud = false)

                val overflow = leftover.values.sumOf { it.amount }
                if (fireOverflow(player, itemStack.type, overflow)) {
                    logger.onPickup(player, itemStack.type, overflow, cloud = true)
                } else {
                    continue
                }
            }
            event.items.remove(drop)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onEntityPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val itemStack = event.item.itemStack
        if (itemStack.type.isAir) return

        val canFit = player.inventory.firstEmpty() != -1 ||
            player.inventory.contents.any { it != null && it.type == itemStack.type && it.amount < it.maxStackSize }

        if (!canFit) {
            if (fireOverflow(player, itemStack.type, itemStack.amount)) {
                event.isCancelled = true
                event.item.remove()
                logger.onPickup(player, itemStack.type, itemStack.amount, cloud = true)
            }
            return
        }

        if (!event.isCancelled) {
            logger.onPickup(player, itemStack.type, itemStack.amount, cloud = false)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onItemSpawn(event: ItemSpawnEvent) {
        val item = event.entity
        val itemStack = item.itemStack
        if (itemStack.type.isAir) return

        val nearbyPlayer = item.location.getNearbyPlayers(5.0).firstOrNull() ?: return

        val leftover = nearbyPlayer.inventory.addItem(itemStack)
        if (leftover.isEmpty()) {
            event.isCancelled = true
            logger.onPickup(nearbyPlayer, itemStack.type, itemStack.amount, cloud = false)
            return
        }

        val added = itemStack.amount - leftover.values.sumOf { it.amount }
        if (added > 0) logger.onPickup(nearbyPlayer, itemStack.type, added, cloud = false)

        val overflow = leftover.values.sumOf { it.amount }
        if (fireOverflow(nearbyPlayer, itemStack.type, overflow)) {
            event.isCancelled = true
            logger.onPickup(nearbyPlayer, itemStack.type, overflow, cloud = true)
        } else if (added > 0) {
            item.itemStack = itemStack.clone().apply { amount = overflow }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreakXp(event: BlockBreakEvent) {
        val xp = event.expToDrop
        if (xp <= 0) return
        val player = event.player
        if (player.gameMode == org.bukkit.GameMode.SPECTATOR || player.gameMode == org.bukkit.GameMode.CREATIVE) return
        event.expToDrop = 0
        player.giveExp(xp)
        logger.onXp(player, xp)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeathXp(event: EntityDeathEvent) {
        val xp = event.droppedExp
        if (xp <= 0) return
        val killer = event.entity.killer ?: return
        if (killer.gameMode == org.bukkit.GameMode.SPECTATOR || killer.gameMode == org.bukkit.GameMode.CREATIVE) return
        event.droppedExp = 0
        killer.giveExp(xp)
        logger.onXp(killer, xp)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onXpOrbSpawn(event: EntitySpawnEvent) {
        val orb = event.entity as? ExperienceOrb ?: return
        val player = orb.location.getNearbyPlayers(16.0)
            .filter { it.gameMode != org.bukkit.GameMode.SPECTATOR && it.gameMode != org.bukkit.GameMode.CREATIVE }
            .minByOrNull { it.location.distanceSquared(orb.location) } ?: return

        val xp = orb.experience
        if (xp <= 0) return

        player.giveExp(xp)
        event.isCancelled = true
        logger.onXp(player, xp)
    }

    private fun fireOverflow(player: Player, material: org.bukkit.Material, amount: Int): Boolean {
        val overflowEvent = InventoryOverflowEvent(player, material, amount)
        plugin.server.pluginManager.callEvent(overflowEvent)
        return overflowEvent.isCancelled
    }
}
