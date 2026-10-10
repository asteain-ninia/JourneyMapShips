package com.asteain.jmships.map;

/**
 * The square patch of the map a ship is drawn on: whole world blocks, {@code blocks} on a side, starting at
 * ({@code anchorX}, {@code anchorZ}), with {@code pixelsPerBlock} pixels to a block. JourneyMap can only place a picture on
 * whole blocks, so the patch stays put while the ship moves inside it, and is moved only when the ship nears its edge.
 */
public record ShipCanvas(int anchorX, int anchorZ, int blocks, int pixelsPerBlock) {
    /** Blocks kept free around the ship's reach: the ship may drift this far minus one before the patch is moved. */
    private static final int MARGIN = 2;
    private static final double DRIFT = 1.0;
    /** Largest picture, in pixels on a side. A ship that reaches further than this many blocks is cut off at the edge. */
    public static final int MAX_PIXELS = 1024;

    public static ShipCanvas around(ShipPose pose, double reach, int wantedPixelsPerBlock) {
        final int half = half(reach);
        final int blocks = 2 * half + 1;
        final int pixelsPerBlock = Math.max(1, Math.min(wantedPixelsPerBlock, MAX_PIXELS / blocks));
        return new ShipCanvas((int) Math.floor(pose.px()) - half, (int) Math.floor(pose.pz()) - half, blocks, pixelsPerBlock);
    }

    private static int half(double reach) {
        return Math.min((int) Math.ceil(reach) + MARGIN, (MAX_PIXELS - 1) / 2);
    }

    public int pixels() {
        return blocks * pixelsPerBlock;
    }

    /** Whether a ship with this pose and reach is still wholly inside the patch. */
    public boolean holds(ShipPose pose, double reach) {
        if (2 * half(reach) + 1 != blocks) return false;
        final double centre = blocks / 2.0;
        return Math.abs(pose.px() - (anchorX + centre)) <= DRIFT && Math.abs(pose.pz() - (anchorZ + centre)) <= DRIFT;
    }
}
