package com.asteain.jmships.jm;

import com.asteain.jmships.JmShips;
import com.asteain.jmships.map.Projector;
import com.asteain.jmships.map.ShipCanvas;
import com.asteain.jmships.map.ShipPose;
import com.asteain.jmships.map.ShipScanner;
import com.asteain.jmships.map.ShipVoxels;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.ImageOverlay;
import journeymap.api.v2.client.model.MapImage;
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
import java.util.Set;
import java.util.UUID;

/**
 * Keeps one picture on JourneyMap for every ship the client knows about: reads the ship's blocks now and then, redraws the
 * picture when the ship has moved or turned enough to show, and moves the picture's place on the map when the ship leaves it.
 */
final class ShipOverlays {
    /** Ticks between two readings of a ship's blocks (a changed size is noticed at once). */
    private static final int SCAN_INTERVAL = 40;
    private static final int PIXELS_PER_BLOCK = 4;
    /** A picture is redrawn when something on it would have moved by more than this part of a pixel. */
    private static final double REDRAW_AT_PIXELS = 0.25;
    private static final String GROUP = "Ships";
    private static final boolean DEBUG = Boolean.getBoolean("jmships.debug");

    private final IClientAPI api;
    private final Map<UUID, Ship> ships = new HashMap<>();
    private ResourceKey<Level> dimension;
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
        final Set<UUID> present = new HashSet<>();
        final SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container != null) {
            for (final SubLevel subLevel : container.getAllSubLevels()) {
                if (!(subLevel instanceof ClientSubLevel client) || client.isRemoved() || client.getUniqueId() == null) continue;
                present.add(client.getUniqueId());
                update(ships.computeIfAbsent(client.getUniqueId(), Ship::new), client);
            }
        }
        for (final Iterator<Ship> it = ships.values().iterator(); it.hasNext(); ) {
            final Ship ship = it.next();
            if (present.contains(ship.id)) continue;
            retire(ship);
            it.remove();
        }
    }

    private void update(Ship ship, ClientSubLevel client) {
        final BoundingBox3ic box = client.getPlot().getBoundingBox();
        if (!ShipScanner.hasBlocks(box)) {
            hide(ship);
            return;
        }
        final boolean resized = ship.voxels == null || !ship.voxels.hasBounds(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        if (resized || Math.floorMod(ticks + ship.id.hashCode(), SCAN_INTERVAL) == 0) {
            final ShipVoxels scanned = ShipScanner.scan(client);
            if (scanned != null && !scanned.sameContent(ship.voxels)) {
                ship.voxels = scanned;
                ship.stale = true;
            }
        }
        if (ship.voxels == null) return;
        if (ship.voxels.surface.length == 0) {
            hide(ship);
            return;
        }

        final ShipPose pose = ShipScanner.pose(client);
        final double reach = pose.reach(ship.voxels);
        boolean moved = false;
        final int detail = detail(ship.voxels);
        if (ship.canvas == null || ship.detail != detail || !ship.canvas.holds(pose, reach)) {
            ship.canvas = ShipCanvas.around(pose, reach, detail);
            ship.detail = detail;
            ship.stale = true;
            moved = true;
            if (DEBUG) {
                JmShips.LOGGER.info("[JourneyMap Ships][debug] {} '{}': {} x {} x {} blocks, {} seen cells, at ({}, {}, {}), canvas {}",
                        ship.id, name(client), ship.voxels.sizeX, ship.voxels.sizeY, ship.voxels.sizeZ, ship.voxels.surface.length,
                        String.format(Locale.ROOT, "%.2f", pose.px()), String.format(Locale.ROOT, "%.2f", pose.py()), String.format(Locale.ROOT, "%.2f", pose.pz()), ship.canvas);
            }
        }
        final ShipCanvas canvas = ship.canvas;
        final boolean replaced = ship.texture.resize(canvas.pixels());
        if (ship.stale || replaced || !pose.looksLike(ship.drawn, reach, REDRAW_AT_PIXELS / canvas.pixelsPerBlock())) {
            final int pixels = canvas.pixels() * canvas.pixels();
            if (ship.colors == null || ship.colors.length != pixels) {
                ship.colors = new byte[pixels];
                ship.heights = new float[pixels];
            }
            Projector.project(ship.voxels, pose, canvas, ship.colors, ship.heights);
            ship.texture.paint(ship.colors, ship.heights, canvas.pixelsPerBlock());
            ship.drawn = pose;
            ship.stale = false;
        }

        final String name = name(client);
        final int y = Mth.floor(pose.py());
        final BlockPos northWest = new BlockPos(canvas.anchorX(), y, canvas.anchorZ());
        final BlockPos southEast = new BlockPos(canvas.anchorX() + canvas.blocks(), y, canvas.anchorZ() + canvas.blocks());
        if (ship.overlay == null) {
            ship.overlay = new ImageOverlay(JmShips.MODID, northWest, southEast, image(ship));
            ship.overlay.setDimension(dimension).setOverlayGroupName(GROUP).setTitle(name).setActiveUIs(Context.UI.Minimap, Context.UI.Fullscreen);
            show(ship);
        } else if (moved || replaced || !name.equals(ship.overlay.getTitle())) {
            ship.overlay.setNorthWestPoint(northWest).setSouthEastPoint(southEast);
            if (replaced) ship.overlay.setImage(image(ship));
            ship.overlay.setTitle(name);
            ship.overlay.flagForRerender();
            show(ship);
        }
    }

    /**
     * Pixels to a block for a ship: a moving ship is redrawn every tick, and the work grows with the cells that can be seen
     * times the pixels each covers, so large ships are drawn coarser (measured: about 1.5 ms a picture at each step).
     */
    private static int detail(ShipVoxels voxels) {
        final int cells = voxels.surface.length;
        return cells < 6000 ? PIXELS_PER_BLOCK : cells < 25000 ? PIXELS_PER_BLOCK / 2 : 1;
    }

    private static MapImage image(Ship ship) {
        final int side = ship.texture.side();
        return new MapImage(ship.texture.location, 0, 0, side, side, 0xFFFFFF, 1.0F).setBlur(false);
    }

    private static String name(ClientSubLevel client) {
        final String name = client.getName();
        return name == null || name.isBlank() ? "" : name;
    }

    private void show(Ship ship) {
        try {
            api.show(ship.overlay);
        } catch (Exception e) {
            JmShips.LOGGER.warn("[JourneyMap Ships] JourneyMap did not take a ship's picture: {}", e.toString());
        }
    }

    /** Takes the picture off the map but keeps what is known about the ship. */
    private void hide(Ship ship) {
        if (ship.overlay == null) return;
        api.remove(ship.overlay);
        ship.overlay = null;
    }

    private void retire(Ship ship) {
        // The picture goes first: JourneyMap must not be left drawing a texture that is gone.
        hide(ship);
        ship.texture.close();
    }

    private void clear() {
        try {
            api.removeAll(JmShips.MODID);
        } catch (Throwable t) {
            JmShips.LOGGER.warn("[JourneyMap Ships] Could not take the ships off the map: {}", t.toString());
        }
        for (final Ship ship : ships.values()) {
            ship.overlay = null;
            ship.texture.close();
        }
        ships.clear();
    }

    private static final class Ship {
        final UUID id;
        final ShipTexture texture;
        ShipVoxels voxels;
        ShipCanvas canvas;
        /** Pixels to a block the canvas was made for. */
        int detail;
        /** The pose the picture was last drawn for. */
        ShipPose drawn;
        /** The blocks or the canvas changed since the picture was drawn. */
        boolean stale = true;
        byte[] colors;
        float[] heights;
        ImageOverlay overlay;

        Ship(UUID id) {
            this.id = id;
            this.texture = new ShipTexture(JmShips.id("ship/" + id.toString().toLowerCase(Locale.ROOT)));
        }
    }
}
