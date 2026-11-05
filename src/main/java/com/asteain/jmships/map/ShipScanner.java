package com.asteain.jmships.map;

import com.asteain.jmships.JmShips;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * Reads a ship from Sable on the client: its blocks (kept in a far-away "plot" of the same level) and its pose.
 *
 * <p>How to reach a sub-level's plot chunks and pose follows "Airship Charts" (MIT, by Sumi) and "Create - Xaero's map"
 * (sablexaeromaps, MIT, by Eve).</p>
 */
public final class ShipScanner {
    /** Ships whose box holds more cells than this are not drawn. */
    private static final long MAX_CELLS = 48_000_000L;
    private static boolean warnedTooLarge;

    private ShipScanner() {
    }

    public static boolean hasBlocks(BoundingBox3ic box) {
        return box != null && box.maxX() >= box.minX() && box.maxY() >= box.minY() && box.maxZ() >= box.minZ();
    }

    /**
     * The ship's blocks, or null when there is nothing to read yet (keep what was read before).
     *
     * <p>Sable lists only the chunks of the plot that have arrived (seen in Sable 2.0.3: a chunk is added to the list
     * together with its holder), so a reading made while a chunk is still on its way leaves its blocks out instead of
     * returning null. Sable sends a ship's chunks together with the ship itself, so this matters only when a ship grows;
     * {@code ShipOverlays} reads again shortly after every change of size for that reason.</p>
     */
    @Nullable
    public static ShipVoxels scan(ClientSubLevel ship) {
        final BoundingBox3ic box = ship.getPlot().getBoundingBox();
        if (!hasBlocks(box)) return null;
        final int minX = box.minX(), minY = box.minY(), minZ = box.minZ();
        final int sizeX = box.maxX() - minX + 1, sizeY = box.maxY() - minY + 1, sizeZ = box.maxZ() - minZ + 1;
        if ((long) sizeX * sizeY * sizeZ > MAX_CELLS) {
            if (!warnedTooLarge) {
                warnedTooLarge = true;
                JmShips.LOGGER.warn("[JourneyMap Ships] A ship of {} x {} x {} blocks is too large to draw.", sizeX, sizeY, sizeZ);
            }
            return null;
        }

        // Looked over before the grid is made: this may be asked every tick until there is something to read.
        int chunks = 0;
        for (final PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            if (holder.getChunk() == null) return null;
            chunks++;
        }
        if (chunks == 0) return null;

        final ClientLevel level = ship.getLevel();
        final byte[] colors = new byte[sizeX * sizeY * sizeZ];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) return null;
            final int x0 = Math.max(chunk.getPos().getMinBlockX(), minX), x1 = Math.min(chunk.getPos().getMaxBlockX(), box.maxX());
            final int z0 = Math.max(chunk.getPos().getMinBlockZ(), minZ), z1 = Math.min(chunk.getPos().getMaxBlockZ(), box.maxZ());
            if (x0 > x1 || z0 > z1) continue;
            final LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                final LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                final int bottom = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                final int y0 = Math.max(bottom, minY), y1 = Math.min(bottom + 15, box.maxY());
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            final BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                            if (state.isAir()) continue;
                            final int color = state.getMapColor(level, pos.set(x, y, z)).id;
                            if (color != 0) colors[ShipVoxels.index(x - minX, y - minY, z - minZ, sizeX, sizeZ)] = (byte) color;
                        }
                    }
                }
            }
        }
        return new ShipVoxels(minX, minY, minZ, sizeX, sizeY, sizeZ, colors);
    }

    /** The pose the ship is being drawn with in the world right now. */
    public static ShipPose pose(ClientSubLevel ship) {
        final Pose3dc pose = ship.renderPose();
        final Vector3dc p = pose.position(), r = pose.rotationPoint(), s = pose.scale();
        final Quaterniondc q = pose.orientation();
        return new ShipPose(p.x(), p.y(), p.z(), q.x(), q.y(), q.z(), q.w(), r.x(), r.y(), r.z(), s.x(), s.y(), s.z());
    }
}
