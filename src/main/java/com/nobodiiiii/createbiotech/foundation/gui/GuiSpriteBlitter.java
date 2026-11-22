package com.nobodiiiii.createbiotech.foundation.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;

/** Native GUI sprites, without submitting the same single texel once per destination pixel. */
public final class GuiSpriteBlitter {
    private GuiSpriteBlitter() {}

    public static void blit(GuiGraphics graphics, ResourceLocation id, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        // Resolve on each draw so F3+T and resource-pack changes cannot leave stale atlas UVs or borders.
        var sprites = Minecraft.getInstance().getGuiSprites();
        var sprite = sprites.getSprite(id);
        if (!(sprites.getSpriteScaling(sprite) instanceof GuiSpriteScaling.NineSlice slice)) {
            graphics.blitSprite(id, x, y, width, height);
            return;
        }
        int left = Math.min(slice.border().left(), width / 2);
        int right = Math.min(slice.border().right(), width / 2);
        int top = Math.min(slice.border().top(), height / 2);
        int bottom = Math.min(slice.border().bottom(), height / 2);
        if (width == slice.width() && height == slice.height()) {
            tile(graphics, sprite, slice, x, y, width, height, 0, 0, width, height);
        } else if (height == slice.height()) {
            tile(graphics, sprite, slice, x, y, left, height, 0, 0, left, height);
            tile(graphics, sprite, slice, x + left, y, width - left - right, height,
                left, 0, slice.width() - left - right, height);
            tile(graphics, sprite, slice, x + width - right, y, right, height,
                slice.width() - right, 0, right, height);
        } else if (width == slice.width()) {
            tile(graphics, sprite, slice, x, y, width, top, 0, 0, width, top);
            tile(graphics, sprite, slice, x, y + top, width, height - top - bottom,
                0, top, width, slice.height() - top - bottom);
            tile(graphics, sprite, slice, x, y + height - bottom, width, bottom,
                0, slice.height() - bottom, width, bottom);
        } else {
            int[] xs = {0, left, width - right, width};
            int[] ys = {0, top, height - bottom, height};
            int[] us = {0, left, slice.width() - right, slice.width()};
            int[] vs = {0, top, slice.height() - bottom, slice.height()};
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                tile(graphics, sprite, slice, x + xs[col], y + ys[row], xs[col + 1] - xs[col],
                    ys[row + 1] - ys[row], us[col], vs[row], us[col + 1] - us[col], vs[row + 1] - vs[row]);
            }
        }
    }

    private static void tile(GuiGraphics graphics, TextureAtlasSprite sprite, GuiSpriteScaling.NineSlice slice,
        int x, int y, int width, int height, int u, int v, int tileWidth, int tileHeight) {
        if (width <= 0 || height <= 0) return;
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("Empty GUI sprite tile");
        int pixelWidth = sprite.contents().width(), pixelHeight = sprite.contents().height();
        // Metadata units need not be physical texels (e.g. a higher-resolution resource pack).
        boolean singleX = (long) tileWidth * pixelWidth == slice.width()
            && (long) u * pixelWidth % slice.width() == 0;
        boolean singleY = (long) tileHeight * pixelHeight == slice.height()
            && (long) v * pixelHeight % slice.height() == 0;
        int stepX = singleX ? width : tileWidth;
        int stepY = singleY ? height : tileHeight;
        int atlasWidth = Math.round(pixelWidth / (sprite.getU1() - sprite.getU0()));
        int atlasHeight = Math.round(pixelHeight / (sprite.getV1() - sprite.getV0()));
        for (int dx = 0; dx < width; dx += stepX) for (int dy = 0; dy < height; dy += stepY) {
            int drawWidth = Math.min(stepX, width - dx), drawHeight = Math.min(stepY, height - dy);
            int sourceWidth = singleX ? tileWidth : drawWidth;
            int sourceHeight = singleY ? tileHeight : drawHeight;
            // Scale both UV numerator and denominator: preserve fractional metadata-to-atlas coordinates.
            graphics.blit(sprite.atlasLocation(), x + dx, y + dy, drawWidth, drawHeight,
                (float) sprite.getX() * slice.width() + u * pixelWidth,
                (float) sprite.getY() * slice.height() + v * pixelHeight,
                sourceWidth * pixelWidth, sourceHeight * pixelHeight,
                atlasWidth * slice.width(), atlasHeight * slice.height());
        }
    }
}
