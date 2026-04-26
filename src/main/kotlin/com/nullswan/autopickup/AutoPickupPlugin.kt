package com.nullswan.autopickup

import org.bukkit.plugin.java.JavaPlugin

class AutoPickupPlugin : JavaPlugin() {

    override fun onEnable() {
        val logger = PickupLogger(this)
        server.pluginManager.registerEvents(AutoPickupListener(this, logger), this)
        this.logger.info("AutoPickup enabled — drops go straight to inventory")
    }
}
