package com.asteain.jmshipstest;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starts a real client with JourneyMap and Sable, makes a flat world, builds three ships (level and half under a roof,
 * turned and tipped, moving) and takes screenshots of the world, the minimap and the fullscreen map, on the surface map and
 * on the cave map. The pictures are judged by eye. Never shipped.
 */
@EventBusSubscriber(modid = "jmshipstest", value = Dist.CLIENT)
public final class Visual {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD = "JMShipsVisual";
    /** Height of the ships' decks: the flat world's ground is at -61. The whole ship lies in JourneyMap's slice -3 (-48 to -33). */
    private static final int DECK = -46;
    /** A stone roof over the west three fifths of the first ship, inside the same slice. */
    private static final int ROOF = DECK + 8;

    private static boolean creating;
    private static boolean prepared;
    private static int ticks;
    /** Ships held where the check wants them, by id: {position, orientation, orientation as built}. Written and read on the server thread. */
    private static final Map<UUID, Object[]> HELD = new LinkedHashMap<>();
    /**
     * Server ticks a new ship is kept as it was built before it is turned. A ship turned in the tick it was made, and then
     * held still, has shown up unturned on the client (run 3 of 2026-10-11): the one change of pose seems to be able to
     * arrive before the ship itself does.
     */
    private static final int SETTLE = 40;
    private static long heldSince = Long.MIN_VALUE;
    private static UUID moving;
    private static final List<String> NOTES = new ArrayList<>();

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("jmshipstest.visual")) return;
        final Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level == null) {
                if (mc.screen instanceof TitleScreen && !creating) {
                    creating = true;
                    createWorld(mc);
                }
                return;
            }
            if (mc.player == null || mc.getSingleplayerServer() == null) return;
            script(mc);
        } catch (Throwable t) {
            LOGGER.error("[JMSHIPSVISUAL] failed", t);
            finish(mc, "FAIL " + t);
        }
    }

    private static void createWorld(Minecraft mc) {
        final LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261011L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
    }

    private static void script(Minecraft mc) {
        if (!prepared) {
            prepared = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.fov().set(70);
            final UUID player = mc.player.getUUID();
            final MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> prepare(server, player));
            minimap();
        }
        ticks++;
        if (ticks == 120) capture(mc, "jmships-01-world-and-minimap.png");
        if (ticks == 140) fullscreen(mc, 4);
        // The first opening of a session shows JourneyMap's "about" page instead of the map: close it and open again.
        if (ticks == 150) fullscreen(mc, 0);
        if (ticks == 200) capture(mc, "jmships-02-fullscreen-day.png");
        if (ticks == 210) {
            mc.setScreen(null);
            // Under the roof, beside the first ship: JourneyMap takes this for underground and turns the minimap to its cave map.
            final UUID player = mc.player.getUUID();
            final MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayer(player).connection.teleport(-23.5, DECK + 2, 0.5, -90f, 10f));
        }
        if (ticks == 320) capture(mc, "jmships-03-under-roof-and-minimap.png");
        if (ticks == 340) mapType(fullscreen(mc, 0), Context.MapType.Underground, DECK >> 4, mc);
        if (ticks == 400) capture(mc, "jmships-04-fullscreen-cave.png");
        if (ticks == 410) mapType(fullscreen(mc, 0), Context.MapType.Day, null, mc);
        if (ticks == 460) capture(mc, "jmships-05-fullscreen-day-again.png");
        if (ticks == 480) {
            mc.setScreen(null);
            finish(mc, "PASS five screenshots taken; the pictures need a look. " + String.join(" | ", NOTES));
        }
    }

    // ---- server side ------------------------------------------------------------------------------------------

    private static void prepare(MinecraftServer server, UUID playerId) {
        final ServerLevel level = server.overworld();
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        // Something on the ground to judge places by: a stone cross through the origin, an arm every 8 blocks.
        for (int i = -40; i <= 40; i++) {
            level.setBlock(new BlockPos(i, -61, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(0, -61, i), Blocks.STONE.defaultBlockState(), 3);
            if (i % 8 == 0) for (int j = -2; j <= 2; j++) level.setBlock(new BlockPos(i, -61, j), Blocks.STONE.defaultBlockState(), 3);
        }

        for (int x = -24; x <= -20; x++) for (int z = -6; z <= 6; z++) level.setBlock(new BlockPos(x, ROOF, z), Blocks.STONE.defaultBlockState(), 3);

        final ServerSubLevel plain = ship(level, new BlockPos(-22, DECK, -4));
        hold(plain, new Vector3d(plain.logicalPose().position()), new Quaterniond(plain.logicalPose().orientation()));

        final ServerSubLevel tipped = ship(level, new BlockPos(-2, DECK, -4));
        tipped.setName("Albatross");
        hold(tipped, new Vector3d(tipped.logicalPose().position()),
                new Quaterniond(tipped.logicalPose().orientation()).rotateY(Math.toRadians(37)).rotateX(Math.toRadians(20)));

        final ServerSubLevel mover = ship(level, new BlockPos(18, DECK, -4));
        hold(mover, new Vector3d(mover.logicalPose().position()), new Quaterniond(mover.logicalPose().orientation()).rotateY(Math.toRadians(-15)));
        moving = mover.getUniqueId();

        final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        player.setGameMode(GameType.SPECTATOR);
        player.connection.teleport(0.5, DECK + 34, 0.5, 180f, 90f);
    }

    /**
     * A small ship that tells its own directions apart from above: 5 wide (x), 9 long (z), bow to the north. Red bow, blue
     * port side (west), lime starboard side (east), a white cabin two blocks high at the stern, a gold mast, a glass block.
     */
    private static ServerSubLevel ship(ServerLevel level, BlockPos corner) {
        final Map<BlockPos, BlockState> parts = new LinkedHashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 9; z++) {
                final BlockState state = z == 0 ? Blocks.RED_WOOL.defaultBlockState()
                        : x == 0 ? Blocks.BLUE_WOOL.defaultBlockState()
                        : x == 4 ? Blocks.LIME_WOOL.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
                parts.put(corner.offset(x, 0, z), state);
            }
        }
        for (int x = 1; x <= 3; x++) for (int z = 5; z <= 7; z++) for (int y = 1; y <= 2; y++) parts.put(corner.offset(x, y, z), Blocks.QUARTZ_BLOCK.defaultBlockState());
        for (int y = 1; y <= 4; y++) parts.put(corner.offset(2, y, 2), Blocks.GOLD_BLOCK.defaultBlockState());
        parts.put(corner.offset(2, 1, 4), Blocks.GLASS.defaultBlockState());
        parts.forEach((pos, state) -> level.setBlock(pos, state, 3));
        return (ServerSubLevel) (Object) SubLevelAssemblyHelper.assembleBlocks(level, corner, new ArrayList<>(parts.keySet()),
                new BoundingBox3i(corner, corner.offset(4, 4, 8)));
    }

    private static void hold(ServerSubLevel ship, Vector3d position, Quaterniond orientation) {
        HELD.put(ship.getUniqueId(), new Object[]{position, orientation, new Quaterniond(ship.logicalPose().orientation())});
    }

    /** Ships are physical bodies and would fall: every tick each one is put back where the check wants it. */
    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("jmshipstest.visual") || HELD.isEmpty()) return;
        final ServerLevel level = event.getServer().overworld();
        final SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null) return;
        if (heldSince == Long.MIN_VALUE) heldSince = level.getGameTime();
        final boolean settled = level.getGameTime() - heldSince >= SETTLE;
        HELD.forEach((id, place) -> {
            if (!(container.getSubLevel(id) instanceof ServerSubLevel ship) || ship.isRemoved()) return;
            final Vector3d position = (Vector3d) place[0];
            // The moving ship goes east and west, about 1.8 blocks a second, 12 blocks each way.
            if (id.equals(moving)) position.x += Math.floorMod(level.getGameTime() / 134, 2) == 0 ? 0.09 : -0.09;
            final RigidBodyHandle body = RigidBodyHandle.of(ship);
            if (body == null || !body.isValid()) return;
            body.teleport(position, (Quaterniond) place[settled ? 1 : 2]);
            body.addLinearAndAngularVelocity(new Vector3d(body.getLinearVelocity()).negate(), new Vector3d(body.getAngularVelocity()).negate());
        });
    }

    // ---- JourneyMap, by name (its own screens are not part of its API) ----------------------------------------------

    /** The minimap as large as it goes and 8 pixels to a block, so that a 5 x 9 ship can be judged on it. */
    private static void minimap() {
        try {
            final Object client = Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
            final Object properties = client.getClass().getMethod("getActiveMiniMapProperties").invoke(client);
            final Object zoom = properties.getClass().getField("zoomLevel").get(properties);
            final Object max = 4096;
            zoom.getClass().getMethod("set", Object.class).invoke(zoom, max);
            final Object size = properties.getClass().getField("sizePercent").get(properties);
            size.getClass().getMethod("set", Object.class).invoke(size, size.getClass().getMethod("getMaxValue").invoke(size));
            final Object ui = uiManager();
            final Object miniMap = ui.getClass().getMethod("getMiniMap").invoke(ui);
            miniMap.getClass().getMethod("reset").invoke(miniMap);
            NOTES.add("minimap zoom " + max);
        } catch (Throwable t) {
            LOGGER.warn("[JMSHIPSVISUAL] could not set the minimap up", t);
            NOTES.add("minimap left as it was: " + t);
        }
    }

    /** Opens the fullscreen map (or leaves it open) centred on the origin. Null when that did not work. */
    private static IFullscreen fullscreen(Minecraft mc, int zoomIns) {
        try {
            if (mc.screen instanceof IFullscreen open) return open;
            if (mc.screen != null) mc.setScreen(null);
            final Object ui = uiManager();
            final IFullscreen screen = (IFullscreen) ui.getClass().getMethod("getOrOpenFullscreenMap").invoke(ui);
            for (int i = 0; i < zoomIns; i++) screen.zoomIn();
            screen.centerOn(0.5, 0.5);
            NOTES.add("fullscreen zoom " + screen.getUiState().zoom);
            return screen;
        } catch (Throwable t) {
            LOGGER.warn("[JMSHIPSVISUAL] could not open the fullscreen map", t);
            NOTES.add("fullscreen map not opened: " + t);
            return null;
        }
    }

    private static void mapType(IFullscreen screen, Context.MapType type, Integer slice, Minecraft mc) {
        if (screen == null) return;
        screen.updateMapType(type, slice, mc.level.dimension());
        screen.centerOn(0.5, 0.5);
        NOTES.add("fullscreen map " + type + (slice == null ? "" : " slice " + slice));
    }

    private static Object uiManager() throws ReflectiveOperationException {
        return Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
    }

    // ---- results ----------------------------------------------------------------------------------------------

    private static void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), result -> LOGGER.info("[JMSHIPSVISUAL] {}", result.getString()));
    }

    private static boolean finished;

    private static void finish(Minecraft mc, String result) {
        if (finished) return;
        finished = true;
        try {
            Files.writeString(Path.of(mc.gameDirectory.toString(), "jmships-result.txt"), result);
        } catch (Exception e) {
            LOGGER.error("[JMSHIPSVISUAL] could not write the result", e);
        }
        mc.stop();
    }
}
