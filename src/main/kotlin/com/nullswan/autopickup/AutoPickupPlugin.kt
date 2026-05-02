package com.nullswan.autopickup

import org.bukkit.plugin.java.JavaPlugin

class AutoPickupPlugin : JavaPlugin() {

    override fun onEnable() {
        saveDefaultConfig()
        val debug = config.getBoolean("debug", false)
        val pickupLogger = PickupLogger(this)
        server.pluginManager.registerEvents(AutoPickupListener(this, pickupLogger, debug), this)
        logger.info("AutoPickup enabled — drops go straight to inventory (debug=$debug)")
    }
}
