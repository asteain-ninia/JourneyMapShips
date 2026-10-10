package com.asteain.jmships.map;

/** The blocks of the world itself, as far as a map cares about them. */
public interface WorldColumns {
    /** The level of the highest block at (x, z) that is not air; below every block when there is none or the place is not loaded. */
    int top(int x, int z);

    /** Whether a map draws the block at (x, y, z): not air, and not looked through like glass. */
    boolean drawn(int x, int y, int z);
}
