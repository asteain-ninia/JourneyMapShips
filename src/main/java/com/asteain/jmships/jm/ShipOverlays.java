package com.asteain.jmships.jm;

import com.asteain.jmships.JmShips;
import com.asteain.jmships.map.LevelColumns;
import com.asteain.jmships.map.Projector;
import com.asteain.jmships.map.ShipCanvas;
import com.asteain.jmships.map.ShipPose;
import com.asteain.jmships.map.ShipScanner;
import com.asteain.jmships.map.ShipVoxels;
import com.asteain.jmships.map.Visibility;
import com.asteain.jmships.map.WorldColumns;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.ImageOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps every ship the client knows about on JourneyMap: reads the ship's blocks now and then, redraws its picture when the
 * ship has moved or turned enough to show, and moves the picture's place on the map when the ship leaves it.
 *
 * <p>A ship has two pictures, because what a map shows of a place depends on the kind of map: one for the surface maps and
 * one for the cave map. JourneyMap picks the right one by the map types each is registered for. The cave picture is only
 * kept while a cave map is on screen.</p>
 */
final class ShipOverlays {
    /** Ticks between two readings of a ship's blocks and of the world around it (a changed size is noticed at once). */
    private static final int SCAN_INTERVAL = 40;
    /** As JourneyMap's own map: one pixel to a block, on the world's block grid. */
    private static final int PIXELS_PER_BLOCK = 1;
    /** A picture is redrawn when something on it would have moved by more than this part of a pixel. */
    private static final double REDRAW_AT_PIXELS = 0.1;
    /**
     * A picture is redrawn when the ship has risen or sunk by more than this many blocks: what is under a roof or in the
     * cave map's slice changes only at whole levels, and a hovering ship that bobs less than this is not redrawn for it.
     */
    private static final double REDRAW_AT_HEIGHT = 0.5;
    private static final String GROUP = "Ships";
    private static final Context.MapType[] SURFACE_MAPS = {Context.MapType.Day, Context.MapType.Night, Context.MapType.Topo, Context.MapType.Biome};
    private static final Context.MapType[] CAVE_MAPS = {Context.MapType.Underground};
    private static final boolean DEBUG = Boolean.getBoolean("jmships.debug");

    private final IClientAPI api;
    private final Map<UUID, Ship> ships = new HashMap<>();
    private ResourceKey<Level> dimension;
    /** The slice of the cave map on screen (16 levels each), or null when no cave map is. */
    private Integer caveSlice;
    private long ticks;
    private boolean failed;

    ShipOverlays(IClientAPI api) {
        this.api = api;
    }

    void onClientTick() {
        if (failed) return;
        try {
            tick();
        } catch (Throwable t) {
            failed = true;
            JmShips.LOGGER.error("[JourneyMap Ships] Switched off until the next login after an unexpected error.", t);
            clear();
        }
    }

    void onLogout() {
        clear();
        dimension = null;
        caveSlice = null;
        failed = false;
    }

    private void tick() {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            if (!ships.isEmpty()) clear();
            return;
        }
        if (!level.dimension().equals(dimension)) {
            clear();
            dimension = level.dimension();
        }
        ticks++;
        final Integer slice = caveSliceOnScreen();
        final boolean viewChanged = !Objects.equals(slice, caveSlice);
        caveSlice = slice;
        if (viewChanged && DEBUG) JmShips.LOGGER.info("[JourneyMap Ships][debug] cave slice on screen: {}", slice);

        final WorldColumns world = new LevelColumns(level);
        final Set<UUID> present = new HashSet<>();
        final SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container != null) {
            for (final SubLevel subLevel : container.getAllSubLevels()) {
                if (!(subLevel instanceof ClientSubLevel client) || client.isRemoved() || client.getUniqueId() == null) continue;
                present.add(client.getUniqueId());
                final Ship ship = ships.computeIfAbsent(client.getUniqueId(), Ship::new);
                if (viewChanged) ship.stale = true;
                update(ship, client, world);
            }
        }
        for (final Iterator<Ship> it = ships.values().iterator(); it.hasNext(); ) {
            final Ship ship = it.next();
            if (present.contains(ship.id)) continue;
            retire(ship);
            it.remove();
        }
    }

    /** The fullscreen map counts first: while it is open the minimap is not on screen. */
    private Integer caveSliceOnScreen() {
        for (final Context.UI ui : new Context.UI[]{Context.UI.Fullscreen, Context.UI.Minimap}) {
            final UIState state = api.getUIState(ui);
            if (state == null || !state.active) continue;
            return state.mapType == Context.MapType.Underground ? state.chunkY : null;
        }
        return caveSlice;
    }

    private void update(Ship ship, ClientSubLevel client, WorldColumns world) {
        final BoundingBox3ic box = client.getPlot().getBoundingBox();
        if (!ShipScanner.hasBlocks(box)) {
            hide(ship.surface);
            hide(ship.cave);
            return;
        }
        final boolean resized = ship.voxels == null || !ship.voxels.hasBounds(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        if (resized || Math.floorMod(ticks + ship.id.hashCode(), SCAN_INTERVAL) == 0) {
            final ShipVoxels scanned = ShipScanner.scan(client);
            if (scanned != null && !scanned.sameContent(ship.voxels)) ship.voxels = scanned;
            // The world around the ship may have changed too (a roof built over it): look again in any case.
            ship.stale = true;
        }
        if (ship.voxels == null) return;
        if (ship.voxels.surface.length == 0) {
            hide(ship.surface);
            hide(ship.cave);
            return;
        }

        final ShipPose pose = ShipScanner.pose(client);
        final double reach = pose.reach(ship.voxels);
        boolean moved = false;
        if (ship.canvas == null || !ship.canvas.holds(pose, reach)) {
            ship.canvas = ShipCanvas.around(pose, reach, PIXELS_PER_BLOCK);
            ship.stale = true;
            moved = true;
            if (DEBUG) {
                JmShips.LOGGER.info("[JourneyMap Ships][debug] {} '{}': {} x {} x {} blocks, {} seen cells, at ({}, {}, {}), canvas {}",
                        ship.id, name(client), ship.voxels.sizeX, ship.voxels.sizeY, ship.voxels.sizeZ, ship.voxels.surface.length,
                        String.format(Locale.ROOT, "%.2f", pose.px()), String.format(Locale.ROOT, "%.2f", pose.py()), String.format(Locale.ROOT, "%.2f", pose.pz()), ship.canvas);
            }
        }
        final ShipCanvas canvas = ship.canvas;
        final boolean caveWanted = caveSlice != null;
        if (!caveWanted) hide(ship.cave);
        final boolean surfaceReplaced = ship.surface.texture.resize(canvas.pixels());
        final boolean caveReplaced = caveWanted && ship.cave.texture.resize(canvas.pixels());
        final boolean caveNew = caveWanted && ship.cave.overlay == null;
        if (ship.stale || surfaceReplaced || caveReplaced || caveNew || !pose.looksLike(ship.drawn, reach, REDRAW_AT_PIXELS / canvas.pixelsPerBlock(), REDRAW_AT_HEIGHT)) {
            final int pixels = canvas.pixels() * canvas.pixels();
            if (ship.colors == null || ship.colors.length != pixels) {
                ship.colors = new byte[pixels];
                ship.heights = new float[pixels];
                ship.shown = new byte[pixels];
            }
            Projector.project(ship.voxels, pose, canvas, ship.colors, ship.heights);
            Visibility.surface(canvas, pose.py(), ship.colors, ship.heights, world, ship.shown);
            ship.surface.texture.paint(ship.colors, ship.heights, canvas.pixelsPerBlock(), ship.shown);
            if (caveWanted) {
                // JourneyMap's slices are 16 levels each, counted from level 0.
                Visibility.cave(canvas, pose.py(), ship.colors, ship.heights, world, caveSlice << 4, (caveSlice << 4) + 15, ship.shown);
                ship.cave.texture.paint(ship.colors, ship.heights, canvas.pixelsPerBlock(), ship.shown);
            }
            ship.drawn = pose;
            ship.stale = false;
        }

        final String name = name(client);
        final int y = Mth.floor(pose.py());
        final BlockPos northWest = new BlockPos(canvas.anchorX(), y, canvas.anchorZ());
        final BlockPos southEast = new BlockPos(canvas.anchorX() + canvas.blocks(), y, canvas.anchorZ() + canvas.blocks());
        place(ship.surface, northWest, southEast, name, moved, surfaceReplaced);
        if (caveWanted) place(ship.cave, northWest, southEast, name, moved, caveReplaced);
    }

    /** Puts a picture on the map, or moves it, or tells JourneyMap that its texture or name is a new one. */
    private void place(Layer layer, BlockPos northWest, BlockPos southEast, String name, boolean moved, boolean replaced) {
        if (layer.overlay == null) {
            layer.overlay = new ImageOverlay(JmShips.MODID, northWest, southEast, layer.image());
            layer.overlay.setDimension(dimension).setOverlayGroupName(GROUP).setTitle(name)
                    .setActiveUIs(Context.UI.Minimap, Context.UI.Fullscreen).setActiveMapTypes(layer.maps);
        } else if (moved || replaced || !name.equals(layer.overlay.getTitle())) {
            layer.overlay.setNorthWestPoint(northWest).setSouthEastPoint(southEast);
            if (replaced) layer.overlay.setImage(layer.image());
            layer.overlay.setTitle(name);
            layer.overlay.flagForRerender();
        } else {
            return;
        }
        try {
            api.show(layer.overlay);
        } catch (Exception e) {
            JmShips.LOGGER.warn("[JourneyMap Ships] JourneyMap did not take a ship's picture: {}", e.toString());
        }
    }

    private static String name(ClientSubLevel client) {
        final String name = client.getName();
        return name == null || name.isBlank() ? "" : name;
    }

    /** Takes a picture off the map but keeps its texture. */
    private void hide(Layer layer) {
        if (layer.overlay == null) return;
        api.remove(layer.overlay);
        layer.overlay = null;
    }

    private void retire(Ship ship) {
        // The pictures go first: JourneyMap must not be left drawing a texture that is gone.
        hide(ship.surface);
        hide(ship.cave);
        ship.surface.texture.close();
        ship.cave.texture.close();
    }

    private void clear() {
        try {
            api.removeAll(JmShips.MODID);
        } catch (Throwable t) {
            JmShips.LOGGER.warn("[JourneyMap Ships] Could not take the ships off the map: {}", t.toString());
        }
        for (final Ship ship : ships.values()) {
            ship.surface.overlay = null;
            ship.cave.overlay = null;
            ship.surface.texture.close();
            ship.cave.texture.close();
        }
        ships.clear();
    }

    /** One picture of a ship on the map, for some kinds of map. */
    private static final class Layer {
        final ShipTexture texture;
        final Context.MapType[] maps;
        ImageOverlay overlay;

        Layer(String path, Context.MapType[] maps) {
            this.texture = new ShipTexture(JmShips.id(path));
            this.maps = maps;
        }

        MapImage image() {
            final int side = texture.side();
            return new MapImage(texture.location, 0, 0, side, side, 0xFFFFFF, 1.0F).setBlur(false);
        }
    }

    private static final class Ship {
        final UUID id;
        final Layer surface;
        final Layer cave;
        ShipVoxels voxels;
        ShipCanvas canvas;
        /** The pose the pictures were last drawn for. */
        ShipPose drawn;
        /** Something the pictures depend on, other than the pose, may have changed since they were drawn. */
        boolean stale = true;
        byte[] colors;
        float[] heights;
        byte[] shown;

        Ship(UUID id) {
            this.id = id;
            final String path = "ship/" + id.toString().toLowerCase(Locale.ROOT);
            this.surface = new Layer(path, SURFACE_MAPS);
            this.cave = new Layer(path + "/cave", CAVE_MAPS);
        }
    }
}
