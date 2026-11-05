package com.asteain.jmships.jm;

import com.asteain.jmships.map.Visibility;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;

/**
 * One ship's picture as a texture of our own, registered under our own name. JourneyMap draws a picture given by name
 * straight from the texture manager, so repainting this texture changes the map without telling JourneyMap anything.
 */
final class ShipTexture implements AutoCloseable {
    /** Rise per block towards the north beyond which a pixel is drawn lighter or darker, as vanilla maps do for steps. */
    private static final float SLOPE = 0.08F;
    private static final MapColor.Brightness[] SHADES = {MapColor.Brightness.LOW, MapColor.Brightness.NORMAL, MapColor.Brightness.HIGH};
    private static final int[][] PALETTE = new int[64][];

    final ResourceLocation location;
    private DynamicTexture texture;
    private int side;

    ShipTexture(ResourceLocation location) {
        this.location = location;
    }

    int side() {
        return side;
    }

    /** Makes the texture {@code side} pixels square. True when a new texture replaced the old one: JourneyMap must look it up again. */
    boolean resize(int side) {
        if (texture != null && this.side == side) return false;
        texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, side, side, true));
        // Registering under the same name closes the texture that was there before.
        Minecraft.getInstance().getTextureManager().register(location, texture);
        this.side = side;
        return true;
    }

    /**
     * Paints what {@link com.asteain.jmships.map.Projector} saw, shaded by slope like a vanilla map, and sends it to the GPU.
     *
     * @param shown one of {@link Visibility}'s answers per pixel
     */
    void paint(byte[] colors, float[] heights, int pixelsPerBlock, byte[] shown) {
        final NativeImage image = texture.getPixels();
        if (image == null) return;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                final int at = z * side + x;
                final int color = colors[at] & 0xFF;
                if (color == 0 || shown[at] == Visibility.HIDDEN) {
                    image.setPixelRGBA(x, z, 0);
                    continue;
                }
                int shade = 1;
                if (z > 0 && colors[at - side] != 0) {
                    final float rise = (heights[at] - heights[at - side]) * pixelsPerBlock;
                    if (rise > SLOPE) shade = 2;
                    else if (rise < -SLOPE) shade = 0;
                }
                final int abgr = shades(color)[shade];
                image.setPixelRGBA(x, z, shown[at] == Visibility.DIMMED ? abgr >> 1 & 0x7F7F7F | 0xFF000000 : abgr);
            }
        }
        texture.upload();
    }

    private static int[] shades(int color) {
        final int at = color & 63;
        if (PALETTE[at] == null) {
            final MapColor map = MapColor.byId(at);
            PALETTE[at] = new int[]{map.calculateRGBColor(SHADES[0]), map.calculateRGBColor(SHADES[1]), map.calculateRGBColor(SHADES[2])};
        }
        return PALETTE[at];
    }

    /**
     * Frees the picture: its pixels and its GL texture. The texture is closed directly, as vanilla's map renderer does, and
     * not through {@code TextureManager.release}: in 1.21.1 that only deletes the GL texture, leaving the image allocated and
     * the texture registered with its old id, so the next {@link #resize} under the same name (the same ship coming back)
     * closed it again and deleted that id a second time, by then perhaps another texture's. Closing twice is harmless: the
     * second {@code register} under this name closes the old texture again, which finds nothing left to free.
     */
    @Override
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
            side = 0;
        }
    }
}
