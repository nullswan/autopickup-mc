package com.nullswan.autopickup

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import org.bukkit.GameMode
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import kotlin.math.sqrt

class AutoPickupListener(
    private val plugin: Plugin,
    private val logger: PickupLogger,
    private val debug: Boolean = false
) : Listener {

    private fun dbg(msg: String) {
        if (debug) plugin.logger.info("[XP] $msg")
    }

    private fun heldDamage(player: Player): Pair<ItemStack, Int>? {
        val held = player.inventory.itemInMainHand
        val meta = held.itemMeta as? Damageable ?: return null
        return held to meta.damage
    }

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

    // XP handoff: teleport the orb to the player's feet so vanilla pickup fires
    // and runs ExperienceOrb#playerTouch, which is the only place Mending
    // consumes XP into damaged enchanted items. Player#giveExp bypasses it.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onXpOrbSpawn(event: EntitySpawnEvent) {
        val orb = event.entity as? ExperienceOrb ?: return
        if (orb.experience <= 0) return

        val nearest = orb.location.getNearbyPlayers(16.0)
            .filter { it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
            .minByOrNull { it.location.distanceSquared(orb.location) }

        if (nearest == null) {
            dbg("spawn id=${orb.entityId} value=${orb.experience} no-player-in-16b → drop")
            return
        }

        val dSq = orb.location.distanceSquared(nearest.location)

        // Already inside vanilla's 8-block magnet? Let vanilla handle it.
        if (dSq < MAGNET_RANGE_SQ) {
            dbg("spawn id=${orb.entityId} value=${orb.experience} in-magnet(${"%.2f".format(sqrt(dSq))}b of ${nearest.name}) → vanilla")
            return
        }

        // Defer to next tick so the orb is fully inserted into the world.
        val target = nearest.location
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!orb.isValid) {
                dbg("tp-skip value=${orb.experience} orb invalidated before handoff")
                return@Runnable
            }
            orb.teleport(target)
            dbg("tp value=${orb.experience} ${"%.2f".format(sqrt(dSq))}b → ${nearest.name}")
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onXpPickup(event: PlayerPickupExperienceEvent) {
        val amount = event.experienceOrb.experience
        if (amount <= 0) return

        if (debug) {
            val before = heldDamage(event.player)
            val player = event.player
            plugin.server.scheduler.runTask(plugin, Runnable {
                val after = heldDamage(player)
                if (before != null && after != null && before.first.isSimilar(after.first)) {
                    val repaired = before.second - after.second
                    if (repaired > 0) {
                        dbg("mending: ${player.name} held=${before.first.type} repaired=${repaired}dur orb=$amount")
                    } else {
                        dbg("absorbed: ${player.name} +$amount xp (no mending on held ${before.first.type})")
                    }
                } else {
                    dbg("absorbed: ${player.name} +$amount xp (held item changed or not damageable)")
                }
            })
        }

        logger.onXp(event.player, amount)
    }

    private fun fireOverflow(player: Player, material: org.bukkit.Material, amount: Int): Boolean {
        val overflowEvent = InventoryOverflowEvent(player, material, amount)
        plugin.server.pluginManager.callEvent(overflowEvent)
        return overflowEvent.isCancelled
    }

    companion object {
        // Vanilla XP orbs magnet to the nearest player within 8 blocks.
        private const val MAGNET_RANGE_SQ = 64.0
    }
}
