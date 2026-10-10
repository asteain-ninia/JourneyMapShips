package com.asteain.jmships;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Draws Sable sub-levels on JourneyMap as their blocks seen from above. Everything happens in the JourneyMap plugin
 * ({@link com.asteain.jmships.jm.ShipsPlugin}), which JourneyMap finds and starts by itself.
 */
@Mod(value = JmShips.MODID, dist = Dist.CLIENT)
public final class JmShips {
    public static final String MODID = "jmships";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
