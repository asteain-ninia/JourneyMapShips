package com.asteain.jmships.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** The world the client has loaded. A block counts as drawn when vanilla maps give it a colour, as a ship's own blocks do. */
public final class LevelColumns implements WorldColumns {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LevelColumns(ClientLevel level) {
        this.level = level;
    }

    @Override
    public int top(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    }

    @Override
    public boolean drawn(int x, int y, int z) {
        final BlockState state = level.getBlockState(pos.set(x, y, z));
        return !state.isAir() && state.getMapColor(level, pos) != MapColor.NONE;
    }
}
