package com.asteain.jmships.jm;

import com.asteain.jmships.JmShips;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The entry point JourneyMap looks for. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class ShipsPlugin implements IClientPlugin {
    private ShipOverlays overlays;

    @Override
    public String getModId() {
        return JmShips.MODID;
    }

    @Override
    public void initialize(IClientAPI api) {
        overlays = new ShipOverlays(api);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> overlays.onClientTick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> overlays.onLogout());
        JmShips.LOGGER.info("[JourneyMap Ships] JourneyMap plugin started.");
    }
}
