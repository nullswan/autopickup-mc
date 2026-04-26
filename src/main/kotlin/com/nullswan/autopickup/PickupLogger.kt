package com.nullswan.autopickup

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PickupLogger(private val plugin: Plugin) {

    private data class Entry(var amount: Int, var cloud: Boolean, var tick: Long)

    private val stacks = ConcurrentHashMap<UUID, MutableMap<Material, Entry>>()
    private val xpEntries = ConcurrentHashMap<UUID, Entry>()

    fun onPickup(player: Player, material: Material, amount: Int, cloud: Boolean) {
        val playerStacks = stacks.getOrPut(player.uniqueId) { mutableMapOf() }
        val currentTick = plugin.server.currentTick.toLong()

        val entry = playerStacks[material]
        if (entry != null && (currentTick - entry.tick) < 100) {
            entry.amount += amount
            entry.cloud = entry.cloud || cloud
            entry.tick = currentTick
        } else {
            playerStacks[material] = Entry(amount, cloud, currentTick)
        }

        render(player)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val map = stacks[player.uniqueId] ?: return@Runnable
            val current = map[material] ?: return@Runnable
            if (plugin.server.currentTick.toLong() - current.tick >= 100) {
                map.remove(material)
                if (map.isEmpty() && xpEntries[player.uniqueId] == null) stacks.remove(player.uniqueId)
            }
        }, 100L)
    }

    fun onXp(player: Player, amount: Int) {
        val currentTick = plugin.server.currentTick.toLong()
        val entry = xpEntries[player.uniqueId]
        if (entry != null && (currentTick - entry.tick) < 100) {
            entry.amount += amount
            entry.tick = currentTick
        } else {
            xpEntries[player.uniqueId] = Entry(amount, false, currentTick)
        }

        render(player)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val current = xpEntries[player.uniqueId] ?: return@Runnable
            if (plugin.server.currentTick.toLong() - current.tick >= 100) {
                xpEntries.remove(player.uniqueId)
            }
        }, 100L)
    }

    private fun render(player: Player) {
        var msg = Component.empty()
        var first = true

        val xp = xpEntries[player.uniqueId]
        if (xp != null) {
            msg = msg.append(Component.text("+${xp.amount} XP", NamedTextColor.YELLOW))
            first = false
        }

        val playerStacks = stacks[player.uniqueId]
        if (playerStacks != null) {
            for ((mat, e) in playerStacks) {
                if (!first) msg = msg.append(Component.text("  "))
                val (prefix, color) = if (e.cloud) "☁ +" to NamedTextColor.AQUA else "+" to NamedTextColor.GREEN
                msg = msg.append(
                    Component.text("$prefix${e.amount} ", color)
                        .append(Component.translatable(mat.translationKey()).color(color))
                )
                first = false
            }
        }

        player.sendActionBar(msg)
    }
}
