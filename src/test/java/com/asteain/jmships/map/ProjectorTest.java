package com.asteain.jmships.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectorTest {
    private static final byte RED = 1, BLUE = 2;

    /** A pose at (x, y, z) turned by the given quaternion, with the rotation point at the ship's block-coordinate origin. */
    private static ShipPose pose(double x, double y, double z, double qx, double qy, double qz, double qw) {
        return new ShipPose(x, y, z, qx, qy, qz, qw, 0, 0, 0, 1, 1, 1);
    }

    private static ShipPose level(double x, double y, double z) {
        return pose(x, y, z, 0, 0, 0, 1);
    }

    private static ShipPose yaw(double x, double y, double z, double degrees) {
        final double half = Math.toRadians(degrees) / 2;
        return pose(x, y, z, 0, Math.sin(half), 0, Math.cos(half));
    }

    private static ShipPose pitch(double x, double y, double z, double degrees) {
        final double half = Math.toRadians(degrees) / 2;
        return pose(x, y, z, Math.sin(half), 0, 0, Math.cos(half));
    }

    private static ShipVoxels grid(int sizeX, int sizeY, int sizeZ, int... cellsAndColors) {
        final byte[] colors = new byte[sizeX * sizeY * sizeZ];
        for (int i = 0; i < cellsAndColors.length; i += 4) {
            colors[ShipVoxels.index(cellsAndColors[i], cellsAndColors[i + 1], cellsAndColors[i + 2], sizeX, sizeZ)] = (byte) cellsAndColors[i + 3];
        }
        return new ShipVoxels(0, 0, 0, sizeX, sizeY, sizeZ, colors);
    }

    private static int count(byte[] colors, byte color) {
        int n = 0;
        for (final byte c : colors) if (c == color) n++;
        return n;
    }

    @Test
    void aLevelBlockFillsExactlyItsOwnSquare() {
        final ShipVoxels one = grid(1, 1, 1, 0, 0, 0, RED);
        // The block's corner is the rotation point, so it covers world x 10..11, z 20..21.
        final ShipCanvas canvas = new ShipCanvas(8, 18, 5, 4);
        final byte[] colors = new byte[20 * 20];
        final float[] heights = new float[20 * 20];
        Projector.project(one, level(10, 64, 20), canvas, colors, heights);
        assertEquals(16, count(colors, RED));
        for (int z = 8; z < 12; z++) {
            for (int x = 8; x < 12; x++) {
                assertEquals(RED, colors[z * 20 + x]);
                assertEquals(1.0F, heights[z * 20 + x], 1e-6);
            }
        }
    }

    @Test
    void aPartOfABlockOfDriftMovesThePictureByPixels() {
        final ShipVoxels one = grid(1, 1, 1, 0, 0, 0, RED);
        final ShipCanvas canvas = new ShipCanvas(8, 18, 5, 4);
        final byte[] colors = new byte[20 * 20];
        final float[] heights = new float[20 * 20];
        Projector.project(one, level(10.5, 64, 20.25), canvas, colors, heights);
        assertEquals(16, count(colors, RED));
        assertEquals(RED, colors[9 * 20 + 10]);
        assertEquals(RED, colors[12 * 20 + 13]);
        assertEquals(0, colors[8 * 20 + 10]);
        assertEquals(0, colors[9 * 20 + 9]);
    }

    @Test
    void aTurnedBlockIsADiamondNotItsBoundingSquare() {
        final byte[] solid = {RED};
        final ShipVoxels one = new ShipVoxels(0, 0, 0, 1, 1, 1, solid);
        // Rotation point in the middle of the block, block centred on the canvas centre.
        final ShipPose turned = new ShipPose(10.5, 64, 10.5, 0, Math.sin(Math.PI / 8), 0, Math.cos(Math.PI / 8), 0.5, 0.5, 0.5, 1, 1, 1);
        final ShipCanvas canvas = new ShipCanvas(8, 8, 5, 8);
        final int side = canvas.pixels();
        final byte[] colors = new byte[side * side];
        final float[] heights = new float[side * side];
        Projector.project(one, turned, canvas, colors, heights);
        // Same area as the level block (64 pixels), give or take the jagged edge.
        assertTrue(Math.abs(count(colors, RED) - 64) <= 8, "area " + count(colors, RED));
        // The middle is covered, the corners of the bounding square are not, the tips of the diamond are.
        assertEquals(RED, colors[20 * side + 20]);
        assertEquals(0, colors[15 * side + 15]);
        assertEquals(RED, colors[20 * side + 15]);
    }

    @Test
    void theHigherBlockWinsAndTippingOverChangesWhichOneThatIs() {
        // Red below, blue on top of it.
        final ShipVoxels stack = grid(1, 2, 1, 0, 0, 0, RED, 0, 1, 0, BLUE);
        final ShipCanvas canvas = new ShipCanvas(6, 6, 9, 2);
        final int side = canvas.pixels();
        final byte[] colors = new byte[side * side];
        final float[] heights = new float[side * side];

        Projector.project(stack, level(10, 64, 10), canvas, colors, heights);
        assertEquals(4, count(colors, BLUE));
        assertEquals(0, count(colors, RED));

        // Tipped a quarter turn about the east-west axis the two lie side by side: both are seen.
        Projector.project(stack, pitch(10, 64, 10, 90), canvas, colors, heights);
        assertEquals(4, count(colors, BLUE));
        assertEquals(4, count(colors, RED));
    }

    @Test
    void aQuarterTurnPutsTheShipsNorthToTheWest() {
        // Two blocks in a row along the ship's z axis: red at z 0, blue at z 1.
        final ShipVoxels row = grid(1, 1, 2, 0, 0, 0, RED, 0, 0, 1, BLUE);
        final ShipCanvas canvas = new ShipCanvas(6, 6, 9, 1);
        final byte[] colors = new byte[81];
        final float[] heights = new float[81];
        Projector.project(row, level(10, 64, 10), canvas, colors, heights);
        assertEquals(RED, colors[4 * 9 + 4]);
        assertEquals(BLUE, colors[5 * 9 + 4]);

        // A positive turn about the up axis carries +z towards +x.
        Projector.project(row, yaw(10, 64, 10, 90), canvas, colors, heights);
        assertEquals(RED, colors[3 * 9 + 4]);
        assertEquals(BLUE, colors[3 * 9 + 5]);
    }

    @Test
    void onlyCellsThatCanBeSeenAreKept() {
        final byte[] solid = new byte[27];
        java.util.Arrays.fill(solid, RED);
        assertEquals(26, new ShipVoxels(0, 0, 0, 3, 3, 3, solid).surface.length);
    }

    @Test
    void theCanvasStaysPutUntilTheShipNearsItsEdge() {
        final ShipCanvas canvas = ShipCanvas.around(level(100.5, 64, 200.5), 7.3, 4);
        assertEquals(21, canvas.blocks());
        assertEquals(90, canvas.anchorX());
        assertEquals(190, canvas.anchorZ());
        assertEquals(84, canvas.pixels());
        assertTrue(canvas.holds(level(101.4, 64, 200.5), 7.3));
        assertFalse(canvas.holds(level(101.6, 64, 200.5), 7.3));
        assertFalse(canvas.holds(level(100.5, 64, 200.5), 9.3));
    }

    @Test
    void aPictureIsKeptWhileNothingOnItWouldMoveVisibly() {
        final ShipPose drawn = yaw(10, 64, 10, 30);
        assertTrue(yaw(10.01, 64.01, 10, 30).looksLike(drawn, 8, 0.0625));
        assertFalse(yaw(10.1, 64, 10, 30).looksLike(drawn, 8, 0.0625));
        // Rising or sinking alone changes what is under a roof or in the cave map's slice, so it is drawn again.
        assertFalse(yaw(10, 64.1, 10, 30).looksLike(drawn, 8, 0.0625));
        assertFalse(yaw(10, 70, 10, 30).looksLike(drawn, 8, 0.0625));
        assertTrue(yaw(10, 64, 10, 30.2).looksLike(drawn, 8, 0.0625));
        assertFalse(yaw(10, 64, 10, 31).looksLike(drawn, 8, 0.0625));
    }
}
