package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.gui.GuiSpriteBlitter;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Skin only; canvas and hit testing continue to use the editor's existing bounds. */
final class ScratchArt {
    enum Block {
        MAIN, CONTROL, CALL, DATA, SERIAL, SIGNAL, CARRIED;

        private final ResourceLocation body = id("block/" + name().toLowerCase(Locale.ROOT));
        private final ResourceLocation connector = id("connector/" + name().toLowerCase(Locale.ROOT));

        void body(GuiGraphics g, int x, int y, int w, int h, boolean selected) {
            GuiSpriteBlitter.blit(g, body, x, y, w, h);
            connector(g, x + 14, y + h - 3, 16, 5);
            if (selected) draw(g, Part.SELECTION, x, y, w, h);
        }

        void connector(GuiGraphics g, int x, int y, int w, int h) {
            GuiSpriteBlitter.blit(g, connector, x, y, w, h);
        }
    }

    enum Part {
        CANVAS, CATEGORY, JOINT, RAIL, SELECTION, DROP_OUTLINE, ASSIGNMENT,
        REPORTER_DRAG, VALUE_LITERAL("value/literal"), VALUE_EXPRESSION("value/expression"),
        BOOLEAN_LITERAL("value/boolean_literal"), BOOLEAN_EXPRESSION("value/boolean_expression");

        private final ResourceLocation sprite;
        Part() { this.sprite = id(name().toLowerCase(Locale.ROOT)); }
        Part(String path) { this.sprite = id(path); }
    }

    private ScratchArt() {}

    static void draw(GuiGraphics g, Part part, int x, int y, int w, int h) {
        GuiSpriteBlitter.blit(g, part.sprite, x, y, w, h);
    }

    static void value(GuiGraphics g, boolean bool, boolean literal, int x, int y, int w, int h) {
        if (!bool) {
            draw(g, literal ? Part.VALUE_LITERAL : Part.VALUE_EXPRESSION, x, y, w, h);
            return;
        }
        // Give the caps the same height-dependent horizontal inset as the existing diamond.
        // Scale the art, not the tile bounds or the label.
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        int logicalWidth = Math.max(1, Math.round(w * 18f / h));
        g.pose().scale(w / (float) logicalWidth, h / 18f, 1);
        draw(g, literal ? Part.BOOLEAN_LITERAL : Part.BOOLEAN_EXPRESSION, 0, 0, logicalWidth, 18);
        g.pose().popPose();
    }

    private static ResourceLocation id(String path) {
        return CreateBiotech.asResource("biocomputing/" + path);
    }
}
