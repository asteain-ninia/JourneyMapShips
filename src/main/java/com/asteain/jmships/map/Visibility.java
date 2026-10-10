package com.asteain.jmships.map;

/**
 * Decides, pixel by pixel, whether a ship shows on the map. The rule is the user's: a part of a ship is drawn exactly where
 * a block of the world standing in the same place would be drawn. On the surface maps that is "nothing above it"; on the
 * cave map it follows what JourneyMap's cave renderer does with a column of blocks, with the ship taken as the one block
 * that is highest in its column.
 */
public final class Visibility {
    public static final byte HIDDEN = 0;
    public static final byte SHOWN = 1;
    /** Shown at half brightness: JourneyMap's cave map shows what lies under the open sky as a dimmed surface map. */
    public static final byte DIMMED = 2;

    /** How far below a cave slice an open shaft is followed down to a ship. */
    private static final int DEPTH = 64;
    private static final float EDGE = 1e-3F;

    private Visibility() {
    }

    /**
     * Fills {@code out} for a surface map (day, night, topography, biomes).
     *
     * @param py      the height the picture's heights are measured from
     * @param colors  what {@link Projector} saw: 0 where there is no ship
     * @param heights the top of what it saw, above {@code py}
     */
    public static void surface(ShipCanvas canvas, double py, byte[] colors, float[] heights, WorldColumns world, byte[] out) {
        final int side = canvas.pixels();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                final int at = z * side + x;
                out[at] = colors[at] != 0 && openSky(world, canvas.anchorX() + x, canvas.anchorZ() + z, level(py, heights[at])) ? SHOWN : HIDDEN;
            }
        }
    }

    /** Fills {@code out} for the cave map of the slice of levels {@code sliceMin} to {@code sliceMax}. */
    public static void cave(ShipCanvas canvas, double py, byte[] colors, float[] heights, WorldColumns world, int sliceMin, int sliceMax, byte[] out) {
        final int side = canvas.pixels();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                final int at = z * side + x;
                out[at] = colors[at] == 0 ? HIDDEN : inCave(world, canvas.anchorX() + x, canvas.anchorZ() + z, level(py, heights[at]), sliceMin, sliceMax);
            }
        }
    }

    /** The level of the block whose top is at the given height: a top exactly on a block boundary belongs to the block below. */
    static int level(double py, float height) {
        return (int) Math.floor(py + height - EDGE);
    }

    /** The level of the highest drawn block of the world above {@code level}; {@code level} itself when there is none. */
    private static int roof(WorldColumns world, int x, int z, int level) {
        for (int y = world.top(x, z); y > level; y--) {
            if (world.drawn(x, y, z)) return y;
        }
        return level;
    }

    static boolean openSky(WorldColumns world, int x, int z, int level) {
        return roof(world, x, z, level) == level;
    }

    static byte inCave(WorldColumns world, int x, int z, int level, int sliceMin, int sliceMax) {
        final int crust = roof(world, x, z, level);
        if (crust == level) return level <= sliceMax ? DIMMED : HIDDEN;
        // JourneyMap starts at the top of the slice, or just under the block that is open to the sky when that is lower,
        // and walks down to the first block with air on top of it.
        int y = Math.min(crust + 1, sliceMax) - 1;
        if (y + 1 < sliceMax) y = crust - 1;
        if (level > y) return HIDDEN;
        for (; y > level; y--) {
            if (world.drawn(x, y, z) && (y < sliceMin || !world.drawn(x, y + 1, z))) return HIDDEN;
            if (sliceMin - y > DEPTH) return HIDDEN;
        }
        return world.drawn(x, level + 1, z) ? HIDDEN : SHOWN;
    }
}
