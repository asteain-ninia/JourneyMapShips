package com.asteain.jmships.map;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityTest {
    /** JourneyMap's slice -3. */
    private static final int MIN = -48, MAX = -33;
    /** The level of the ship's top block in most cases below. */
    private static final int SHIP = -46;

    /** One column of the world at (0, 0): some stone, some glass. */
    private static final class Column implements WorldColumns {
        private final Set<Integer> stone = new HashSet<>();
        private final Set<Integer> glass = new HashSet<>();

        Column stone(int from, int to) {
            for (int y = from; y <= to; y++) stone.add(y);
            return this;
        }

        Column glass(int y) {
            glass.add(y);
            return this;
        }

        @Override
        public int top(int x, int z) {
            int top = Integer.MIN_VALUE;
            if (x == 0 && z == 0) {
                for (final int y : stone) top = Math.max(top, y);
                for (final int y : glass) top = Math.max(top, y);
            }
            return top;
        }

        @Override
        public boolean drawn(int x, int y, int z) {
            return x == 0 && z == 0 && stone.contains(y);
        }
    }

    @Test
    void aTopOnABlockBoundaryBelongsToTheBlockBelow() {
        assertEquals(69, Visibility.level(60, 10.0F));
        assertEquals(70, Visibility.level(60, 10.4F));
    }

    @Test
    void onTheSurfaceMapOnlyWhatIsAboveHides() {
        assertTrue(Visibility.openSky(new Column(), 0, 0, SHIP));
        assertTrue(Visibility.openSky(new Column().stone(-61, -50), 0, 0, SHIP));
        assertTrue(Visibility.openSky(new Column().stone(SHIP, SHIP), 0, 0, SHIP));
        assertFalse(Visibility.openSky(new Column().stone(-38, -38), 0, 0, SHIP));
        assertTrue(Visibility.openSky(new Column().glass(-38), 0, 0, SHIP));
        assertFalse(Visibility.openSky(new Column().stone(-40, -40).glass(-38), 0, 0, SHIP));
    }

    @Test
    void underTheOpenSkyTheCaveMapShowsADimmedSurface() {
        assertEquals(Visibility.DIMMED, Visibility.inCave(new Column(), 0, 0, SHIP, MIN, MAX));
        assertEquals(Visibility.DIMMED, Visibility.inCave(new Column().stone(-61, -61), 0, 0, -60, MIN, MAX));
        assertEquals(Visibility.HIDDEN, Visibility.inCave(new Column(), 0, 0, -30, MIN, MAX));
    }

    @Test
    void aShipInAHangarShowsOnTheCaveMapOfItsSlice() {
        // A roof inside the slice.
        assertEquals(Visibility.SHOWN, Visibility.inCave(new Column().stone(-38, -38), 0, 0, SHIP, MIN, MAX));
        // An island above the slice.
        assertEquals(Visibility.SHOWN, Visibility.inCave(new Column().stone(-30, -20), 0, 0, SHIP, MIN, MAX));
        // Looked at from the slice above, the ship is below the slice at the bottom of an open shaft.
        assertEquals(Visibility.SHOWN, Visibility.inCave(new Column().stone(-10, -10), 0, 0, SHIP, -32, -17));
    }

    @Test
    void whatTheCaveMapWouldNotDrawIsHidden() {
        // Above the slice.
        assertEquals(Visibility.HIDDEN, Visibility.inCave(new Column().stone(-20, -20), 0, 0, -32, MIN, MAX));
        // A walkway between the roof and the ship: the map shows the walkway.
        assertEquals(Visibility.HIDDEN, Visibility.inCave(new Column().stone(-38, -38).stone(-42, -42), 0, 0, SHIP, MIN, MAX));
        // Buried.
        assertEquals(Visibility.HIDDEN, Visibility.inCave(new Column().stone(-45, -38), 0, 0, SHIP, MIN, MAX));
        // Below the slice, under rock.
        assertEquals(Visibility.HIDDEN, Visibility.inCave(new Column().stone(-38, -38).stone(-55, -52), 0, 0, -60, MIN, MAX));
    }

    /**
     * Far from the player the world under a ship is often not loaded on the client. {@link LevelColumns} then gets the
     * bottom of the world from Minecraft as the top of the column and air for every block: that must read as open sky, not
     * as a roof.
     */
    @Test
    void overGroundTheClientHasNotLoadedAShipIsUnderTheOpenSky() {
        final WorldColumns unloaded = new WorldColumns() {
            @Override
            public int top(int x, int z) {
                return -65;
            }

            @Override
            public boolean drawn(int x, int y, int z) {
                return false;
            }
        };
        assertTrue(Visibility.openSky(unloaded, 1000, -2000, 150));
        // The cave map of the player's slice (64 to 79) leaves out a ship that flies above it, as it would a block there.
        assertEquals(Visibility.HIDDEN, Visibility.inCave(unloaded, 1000, -2000, 150, 64, 79));
        assertEquals(Visibility.DIMMED, Visibility.inCave(unloaded, 1000, -2000, 70, 64, 79));
    }

    @Test
    void aPictureIsMaskedPixelByPixel() {
        // Two pixels side by side at world x 0 and 1: the first under a roof, the second in the open; a third without a ship.
        final ShipCanvas canvas = new ShipCanvas(0, 0, 3, 1);
        final byte[] colors = new byte[9];
        final float[] heights = new float[9];
        colors[0] = 5;
        colors[1] = 5;
        heights[0] = heights[1] = 1.0F;
        final byte[] shown = new byte[9];
        final Column roofed = new Column().stone(-38, -38);

        Visibility.surface(canvas, SHIP, colors, heights, roofed, shown);
        assertArrayEquals(new byte[]{Visibility.HIDDEN, Visibility.SHOWN, 0, 0, 0, 0, 0, 0, 0}, shown);

        Visibility.cave(canvas, SHIP, colors, heights, roofed, MIN, MAX, shown);
        assertArrayEquals(new byte[]{Visibility.SHOWN, Visibility.DIMMED, 0, 0, 0, 0, 0, 0, 0}, shown);
    }
}
