package com.nullswan.autopickup

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPickupListenerXpTest {

    // ---- fixtures ----

    private class Fixture(
        val plugin: Plugin,
        val scheduler: BukkitScheduler,
        val scheduledTasks: CapturingSlot<Runnable>
    )

    private fun newFixture(): Fixture {
        val server = mockk<Server>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        val pluginManager = mockk<PluginManager>(relaxed = true)
        val plugin = mockk<Plugin>(relaxed = true)
        every { server.currentTick } returns 0
        every { server.scheduler } returns scheduler
        every { server.pluginManager } returns pluginManager
        every { plugin.server } returns server

        val taskSlot = slot<Runnable>()
        every { scheduler.runTask(any<Plugin>(), capture(taskSlot)) } returns mockk(relaxed = true)
        return Fixture(plugin, scheduler, taskSlot)
    }

    private fun mockPlayer(mode: GameMode = GameMode.SURVIVAL, id: UUID = UUID.randomUUID()): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns id
        every { p.gameMode } returns mode
        return p
    }

    // Holds the orb mock plus the per-player Location mocks so tests can
    // verify teleport targets without re-invoking Player#getLocation at
    // verify-time (which would inflate mockk's call counts). Uses
    // IdentityHashMap because mockk's relaxed Object#equals returns false
    // even for self — a regular HashMap lookup by mock key always misses.
    private class OrbScene(val orb: ExperienceOrb, private val locs: IdentityHashMap<Player, Location>) {
        fun locOf(player: Player): Location =
            locs[player] ?: error("no location mocked for player $player")
    }

    // Builds an ExperienceOrb whose location returns the given players via
    // getNearbyPlayers(16.0), with distanceSquared wired in both directions.
    private fun mockOrb(experience: Int, nearby: List<Pair<Player, Double>>): OrbScene {
        val orbLoc = mockk<Location>(relaxed = true)
        every { orbLoc.getNearbyPlayers(16.0) } returns nearby.map { it.first }

        val locs = IdentityHashMap<Player, Location>()
        for ((player, distSq) in nearby) {
            val pLoc = mockk<Location>(relaxed = true)
            every { pLoc.distanceSquared(orbLoc) } returns distSq
            every { orbLoc.distanceSquared(pLoc) } returns distSq
            every { player.location } returns pLoc
            locs[player] = pLoc
        }

        val orb = mockk<ExperienceOrb>(relaxed = true)
        every { orb.location } returns orbLoc
        every { orb.experience } returns experience
        every { orb.isValid } returns true
        return OrbScene(orb, locs)
    }

    private fun spawnEvent(entity: Entity, cancelled: Boolean = false): EntitySpawnEvent {
        val event = mockk<EntitySpawnEvent>(relaxed = true)
        every { event.entity } returns entity
        every { event.isCancelled } returns cancelled
        return event
    }

    // ---- tests ----

    @Test
    fun `teleports orb to nearest survival player on next tick`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val close = mockPlayer()
        val far = mockPlayer()
        // 10 blocks away (distSq=100) -> outside 8-block magnet, inside 16-block range
        val scene = mockOrb(5, listOf(close to 100.0, far to 400.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))

        verify(exactly = 1) { fx.scheduler.runTask(fx.plugin, any<Runnable>()) }
        fx.scheduledTasks.captured.run()
        verify(exactly = 1) { scene.orb.teleport(scene.locOf(close)) }
        verify(exactly = 0) { scene.orb.teleport(scene.locOf(far)) }
    }

    @Test
    fun `skips creative players even when closer`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val creativeCloser = mockPlayer(mode = GameMode.CREATIVE)
        val survivalFarther = mockPlayer(mode = GameMode.SURVIVAL)
        val scene = mockOrb(5, listOf(creativeCloser to 100.0, survivalFarther to 200.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))
        fx.scheduledTasks.captured.run()

        verify(exactly = 1) { scene.orb.teleport(scene.locOf(survivalFarther)) }
        verify(exactly = 0) { scene.orb.teleport(scene.locOf(creativeCloser)) }
    }

    @Test
    fun `skips spectator players even when closer`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val spectator = mockPlayer(mode = GameMode.SPECTATOR)
        val survival = mockPlayer(mode = GameMode.SURVIVAL)
        val scene = mockOrb(5, listOf(spectator to 100.0, survival to 200.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))
        fx.scheduledTasks.captured.run()

        verify(exactly = 1) { scene.orb.teleport(scene.locOf(survival)) }
        verify(exactly = 0) { scene.orb.teleport(scene.locOf(spectator)) }
    }

    @Test
    fun `no teleport scheduled when only creative or spectator players are nearby`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val creative = mockPlayer(mode = GameMode.CREATIVE)
        val spectator = mockPlayer(mode = GameMode.SPECTATOR)
        val scene = mockOrb(5, listOf(creative to 100.0, spectator to 150.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))

        verify(exactly = 0) { fx.scheduler.runTask(any(), any<Runnable>()) }
    }

    @Test
    fun `no teleport scheduled when no players are nearby`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val scene = mockOrb(5, emptyList())

        listener.onXpOrbSpawn(spawnEvent(scene.orb))

        verify(exactly = 0) { fx.scheduler.runTask(any(), any<Runnable>()) }
    }

    @Test
    fun `no teleport when orb is already inside vanilla's 8-block magnet range`() {
        // 7 blocks away (distSq=49) -> vanilla's own 64-distSq magnet will
        // pick it up. No reason for us to relocate; doing so would cause
        // visible orb jitter.
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val player = mockPlayer()
        val scene = mockOrb(5, listOf(player to 49.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))

        verify(exactly = 0) { fx.scheduler.runTask(any(), any<Runnable>()) }
    }

    @Test
    fun `no-op for non-ExperienceOrb spawns`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val zombie = mockk<Zombie>(relaxed = true)
        listener.onXpOrbSpawn(spawnEvent(zombie))

        verify(exactly = 0) { fx.scheduler.runTask(any(), any<Runnable>()) }
    }

    @Test
    fun `no teleport for orb with zero experience`() {
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val player = mockPlayer()
        val scene = mockOrb(experience = 0, nearby = listOf(player to 100.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))

        verify(exactly = 0) { fx.scheduler.runTask(any(), any<Runnable>()) }
    }

    @Test
    fun `no teleport when orb becomes invalid before the tick fires`() {
        // Guards against NPEs / ghost-entity issues if the orb is removed
        // (picked up by another player, despawned, world unloaded) between
        // spawn and the scheduled teleport.
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val player = mockPlayer()
        val scene = mockOrb(5, listOf(player to 100.0))

        listener.onXpOrbSpawn(spawnEvent(scene.orb))
        every { scene.orb.isValid } returns false
        fx.scheduledTasks.captured.run()

        verify(exactly = 0) { scene.orb.teleport(any<Location>()) }
    }

    @Test
    fun `distance tie picks the first player from the nearby list`() {
        // minByOrNull keeps the first on ties — documented Kotlin behavior.
        // Test guards against a future refactor that might swap the comparator.
        val fx = newFixture()
        val listener = AutoPickupListener(fx.plugin, PickupLogger(fx.plugin))

        val first = mockPlayer()
        val second = mockPlayer()
        val orbLoc = mockk<Location>(relaxed = true)
        every { orbLoc.getNearbyPlayers(16.0) } returns listOf(first, second)

        val firstLoc = mockk<Location>(relaxed = true)
        val secondLoc = mockk<Location>(relaxed = true)
        every { firstLoc.distanceSquared(orbLoc) } returns 100.0
        every { secondLoc.distanceSquared(orbLoc) } returns 100.0
        every { orbLoc.distanceSquared(firstLoc) } returns 100.0
        every { orbLoc.distanceSquared(secondLoc) } returns 100.0
        every { first.location } returns firstLoc
        every { second.location } returns secondLoc

        val orb = mockk<ExperienceOrb>(relaxed = true)
        every { orb.location } returns orbLoc
        every { orb.experience } returns 3
        every { orb.isValid } returns true

        listener.onXpOrbSpawn(spawnEvent(orb))
        fx.scheduledTasks.captured.run()

        verify(exactly = 1) { orb.teleport(firstLoc) }
        verify(exactly = 0) { orb.teleport(secondLoc) }
    }

    @Test
    fun `onXpPickup logs the orb's experience value for the picking-up player`() {
        val fx = newFixture()
        val logger = mockk<PickupLogger>(relaxed = true)
        val listener = AutoPickupListener(fx.plugin, logger)

        val player = mockPlayer()
        val orb = mockk<ExperienceOrb>(relaxed = true)
        every { orb.experience } returns 7

        val event = mockk<PlayerPickupExperienceEvent>(relaxed = true)
        every { event.player } returns player
        every { event.experienceOrb } returns orb

        listener.onXpPickup(event)

        verify(exactly = 1) { logger.onXp(player, 7) }
    }

    @Test
    fun `onXpPickup ignores zero-experience orbs`() {
        val fx = newFixture()
        val logger = mockk<PickupLogger>(relaxed = true)
        val listener = AutoPickupListener(fx.plugin, logger)

        val player = mockPlayer()
        val orb = mockk<ExperienceOrb>(relaxed = true)
        every { orb.experience } returns 0

        val event = mockk<PlayerPickupExperienceEvent>(relaxed = true)
        every { event.player } returns player
        every { event.experienceOrb } returns orb

        listener.onXpPickup(event)

        verify(exactly = 0) { logger.onXp(any(), any()) }
    }

    @Test
    fun `old XP handlers no longer exist on the listener`() {
        // Regression lock: if someone re-adds onBlockBreakXp or onEntityDeathXp
        // they'll re-break Mending (Player#giveExp bypasses the orb pickup
        // path where vanilla consumes XP into damaged enchanted items).
        val methods = AutoPickupListener::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse(
            "onBlockBreakXp" in methods,
            "onBlockBreakXp was deleted to preserve vanilla Mending — do not re-add. " +
                "See comment on onXpOrbSpawn for why."
        )
        assertFalse(
            "onEntityDeathXp" in methods,
            "onEntityDeathXp was deleted to preserve vanilla Mending — do not re-add. " +
                "See comment on onXpOrbSpawn for why."
        )
        assertTrue("onXpOrbSpawn" in methods)
        assertTrue("onXpPickup" in methods)
    }
}
