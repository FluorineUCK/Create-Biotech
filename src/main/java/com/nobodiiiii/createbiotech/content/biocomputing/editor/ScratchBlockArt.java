package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Nine-slice rendering of the Scratch Blocks SVG atlas. The source curves are baked, not redrawn. */
final class ScratchBlockArt {
    private static final int SCALE = 2, STRIDE_X = 88, STRIDE_Y = 48;
    private static final int TEXTURE_WIDTH = Region.values().length * STRIDE_X * SCALE, TEXTURE_HEIGHT = 10 * STRIDE_Y * SCALE;
    private static final ResourceLocation TEXTURE =
        CreateBiotech.asResource("textures/gui/biocomputing/scratch_blocks.png");

    enum Region {
        HAT(75, 39, 50, 5, 12, 8, 4),
        STACK(84, 30, 26, 5, 6, 7, 4),
        MOUTH(82, 30, 42, 5, 6, 7, 4),
        FOOT(82, 18, 42, 5, 6, 7, 4),
        REPORTER(60, 22, 11, 12, 0, 0, 0),
        BOOLEAN(76, 22, 11, 12, 0, 0, 0),
        LITERAL(22, 18, 9, 9, 0, 0, 0),
        RAIL(18, 6, 0, 0, 0, 0, 0),
        DEFINITION(78, 48, 27, 12, 12, 8, 4),
        PROTOTYPE(76, 30, 26, 5, 6, 7, 4),
        CAP(84, 26, 26, 5, 6, 4, 0),
        // Starts two units above the divider to close the upstream inner corner.
        MIDDLE(82, 24, 42, 5, 8, 7, 4);

        final int width, height, left, right, top, bottom, tab;
        Region(int width, int height, int left, int right, int top, int bottom, int tab) {
            this.width = width;
            this.height = height;
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.tab = tab;
        }
    }

    private ScratchBlockArt() {}

    private static int row(Category category) {
        return switch (category) {
            case EVENTS -> 0;
            case CONTROL -> 1;
            case OPERATORS -> 2;
            case VARIABLES -> 3;
            case STORAGE -> 4;
            case DATA -> 5;
            case SENSING, EXTENSIONS, READERS, COMMUNICATION, OUTPUT -> 6;
            case CUSTOM -> 9;
        };
    }

    static void draw(GuiGraphics g, int x, int y, int width, int height, Category category, Region region) {
        int overlap = region == Region.MIDDLE ? 2 : 0;
        int totalWidth = width + 2, totalHeight = height + region.tab + 2 + overlap;
        int left = Math.min(region.left, Math.max(0, totalWidth - region.right - 1));
        int right = Math.min(region.right, totalWidth - left - 1);
        int top = Math.min(region.top, totalHeight - region.bottom - 1);
        int[] xs = {x - 1, x - 1 + left, x - 1 + totalWidth - right, x - 1 + totalWidth};
        int startY = y - 1 - overlap;
        int[] ys = {startY, startY + top, startY + totalHeight - region.bottom, startY + totalHeight};
        int u = region.ordinal() * STRIDE_X, v = row(category) * STRIDE_Y;
        int[] us = {u, u + region.left, u + region.width - region.right, u + region.width};
        int[] vs = {v, v + region.top, v + region.height - region.bottom, v + region.height};
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
            int w = xs[i + 1] - xs[i], h = ys[j + 1] - ys[j];
            if (w <= 0 || h <= 0) continue;
            g.blit(TEXTURE, xs[i], ys[j], w, h, us[i] * SCALE, vs[j] * SCALE,
                (us[i + 1] - us[i]) * SCALE, (vs[j + 1] - vs[j]) * SCALE, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
    }
}
