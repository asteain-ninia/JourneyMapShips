package com.asteain.jmships.map;

import java.util.Arrays;

/**
 * Looks straight down on a ship and writes what is seen into a picture: for every pixel, the colour and the height of the
 * highest block that the vertical line through the pixel's centre passes through. Each block is tested as the turned box it
 * really is, so a ship at any angle keeps its true outline at any picture resolution.
 *
 * <p>The idea of sampling a sub-level's blocks into a top-down picture with a height per pixel follows "Create - Xaero's map"
 * (sablexaeromaps, MIT, by Eve); the code here is written anew.</p>
 */
public final class Projector {
    private static final double FLAT = 1e-9;
    private static final double SLACK = 1e-9;

    private Projector() {
    }

    /**
     * @param colors  one map colour per pixel, row by row from north to south; 0 where no block is seen
     * @param heights the height of what is seen, in blocks above the ship's position; only meaningful where a colour is set
     */
    public static void project(ShipVoxels voxels, ShipPose pose, ShipCanvas canvas, byte[] colors, float[] heights) {
        final int side = canvas.pixels();
        if (colors.length != side * side || heights.length != side * side) throw new IllegalArgumentException("picture size does not match the canvas");
        Arrays.fill(colors, (byte) 0);
        Arrays.fill(heights, Float.NEGATIVE_INFINITY);

        final double[] a = pose.axes();
        final double h0 = 0.5 * pose.sx(), h1 = 0.5 * pose.sy(), h2 = 0.5 * pose.sz();
        // Half the width of a block's shadow on the ground, along world x and z.
        final double halfX = h0 * Math.abs(a[0]) + h1 * Math.abs(a[3]) + h2 * Math.abs(a[6]);
        final double halfZ = h0 * Math.abs(a[2]) + h1 * Math.abs(a[5]) + h2 * Math.abs(a[8]);
        final int ppb = canvas.pixelsPerBlock();
        final double pixel = 1.0 / ppb;
        final int layer = voxels.sizeX * voxels.sizeZ;

        for (final int cell : voxels.surface) {
            final int gx = cell % voxels.sizeX;
            final int gz = cell / voxels.sizeX % voxels.sizeZ;
            final int gy = cell / layer;
            final double lx = (voxels.minX + gx + 0.5 - pose.rx()) * pose.sx();
            final double ly = (voxels.minY + gy + 0.5 - pose.ry()) * pose.sy();
            final double lz = (voxels.minZ + gz + 0.5 - pose.rz()) * pose.sz();
            // Centre of the block: east and south of the canvas corner, and above the ship's position.
            final double cx = pose.px() + lx * a[0] + ly * a[3] + lz * a[6] - canvas.anchorX();
            final double cy = lx * a[1] + ly * a[4] + lz * a[7];
            final double cz = pose.pz() + lx * a[2] + ly * a[5] + lz * a[8] - canvas.anchorZ();

            final int x0 = Math.max((int) Math.ceil((cx - halfX) * ppb - 0.5), 0);
            final int x1 = Math.min((int) Math.floor((cx + halfX) * ppb - 0.5), side - 1);
            final int z0 = Math.max((int) Math.ceil((cz - halfZ) * ppb - 0.5), 0);
            final int z1 = Math.min((int) Math.floor((cz + halfZ) * ppb - 0.5), side - 1);
            final byte color = voxels.color(cell);

            for (int iz = z0; iz <= z1; iz++) {
                final double dz = (iz + 0.5) * pixel - cz;
                for (int ix = x0; ix <= x1; ix++) {
                    final double dx = (ix + 0.5) * pixel - cx;
                    // The vertical line through the pixel, as a height t above the block's centre, cut by the box's three slabs.
                    double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
                    boolean inside = true;
                    for (int axis = 0; axis < 3 && inside; axis++) {
                        final double half = axis == 0 ? h0 : axis == 1 ? h1 : h2;
                        final double along = dx * a[axis * 3] + dz * a[axis * 3 + 2];
                        final double rise = a[axis * 3 + 1];
                        if (Math.abs(rise) < FLAT) {
                            inside = Math.abs(along) <= half + SLACK;
                        } else {
                            final double t0 = (-half - along) / rise, t1 = (half - along) / rise;
                            low = Math.max(low, Math.min(t0, t1));
                            high = Math.min(high, Math.max(t0, t1));
                        }
                    }
                    if (!inside || low > high + SLACK) continue;
                    final float top = (float) (cy + high);
                    final int at = iz * side + ix;
                    if (top > heights[at]) {
                        heights[at] = top;
                        colors[at] = color;
                    }
                }
            }
        }
    }
}
