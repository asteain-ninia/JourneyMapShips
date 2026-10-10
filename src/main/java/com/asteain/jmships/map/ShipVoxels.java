package com.asteain.jmships.map;

import java.util.Arrays;

/**
 * The blocks of one ship as map colours on a dense grid, in the ship's own (plot) block coordinates. Colour 0 is "nothing
 * to draw": air, and blocks that vanilla maps look through (glass and the like).
 */
public final class ShipVoxels {
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final byte[] colors;
    /** Cells that have a colour and at least one neighbour without one: only these can ever be seen. */
    public final int[] surface;

    public ShipVoxels(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] colors) {
        if (colors.length != sizeX * sizeY * sizeZ) throw new IllegalArgumentException("grid size does not match the colours");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.colors = colors;
        this.surface = findSurface();
    }

    /** Index of a cell given by its offset from the grid's minimum corner. */
    public static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public byte color(int cell) {
        return colors[cell];
    }

    public boolean hasBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return this.minX == minX && this.minY == minY && this.minZ == minZ
                && this.minX + sizeX - 1 == maxX && this.minY + sizeY - 1 == maxY && this.minZ + sizeZ - 1 == maxZ;
    }

    public boolean sameContent(ShipVoxels other) {
        return other != null && minX == other.minX && minY == other.minY && minZ == other.minZ
                && sizeX == other.sizeX && sizeY == other.sizeY && sizeZ == other.sizeZ && Arrays.equals(colors, other.colors);
    }

    private boolean colored(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ && colors[index(x, y, z, sizeX, sizeZ)] != 0;
    }

    private int[] findSurface() {
        int[] found = new int[Math.min(colors.length, 1024)];
        int count = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    final int cell = index(x, y, z, sizeX, sizeZ);
                    if (colors[cell] == 0) continue;
                    if (colored(x - 1, y, z) && colored(x + 1, y, z) && colored(x, y - 1, z) && colored(x, y + 1, z)
                            && colored(x, y, z - 1) && colored(x, y, z + 1)) continue;
                    if (count == found.length) found = Arrays.copyOf(found, count * 2);
                    found[count++] = cell;
                }
            }
        }
        return Arrays.copyOf(found, count);
    }
}
