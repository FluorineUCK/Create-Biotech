package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ScratchBlockArt.Region;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared Scratch procedure prototype geometry for the live preview and the definition hat. */
record ScratchSignaturePreview(String caption, int width, List<Chip> chips, List<Phrase> phrases) {
    record Phrase(String text, int x) {}
    static final int HEIGHT = 24;
    private static final int CHIP_HEIGHT = 20;

    record Parameter(String name, boolean bool) {}
    record Chip(Parameter parameter, String label, int x, int width) {
        int inset() { return parameter.bool() ? 10 : 7; }
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= 2 && py < 2 + CHIP_HEIGHT;
        }
    }

    static ScratchSignaturePreview layout(Font font, String title, List<Parameter> parameters) {
        return layout(font, title, parameters, List.of());
    }
    static ScratchSignaturePreview layout(Font font, String title, List<Parameter> parameters, List<ScratchProcedureLabel> labels) {
        // The preview scrolls and the canvas pans. Truncating here also hides the fixed revision.
        int x = 14 + measure(font, title);
        var chips = new ArrayList<Chip>();
        var phrases = new ArrayList<Phrase>();
        for (int index = 0; index <= parameters.size(); index++) {
            for (var part : labels) if (part.before() == index) {
                phrases.add(new Phrase(part.text(), x));
                x += measure(font, part.text()) + 6;
            }
            if (index == parameters.size()) break;
            var param = parameters.get(index);
            var label = param.name();
            int width = Math.max(24, measure(font, label) + (param.bool() ? 20 : 14));
            chips.add(new Chip(param, label, x, width));
            x += width + 4;
        }
        return new ScratchSignaturePreview(title, Math.max(36, x + 1), List.copyOf(chips), List.copyOf(phrases));
    }

    private static int measure(Font font, String text) { return font == null ? text.length() * 6 : font.width(text); }

    void draw(GuiGraphics graphics, Font font, int x, int y) {
        ScratchBlockArt.draw(graphics,x,y,width,HEIGHT,Category.CUSTOM,Region.PROTOTYPE);
        int baseline = y + (HEIGHT - 8) / 2;
        graphics.drawString(font, caption, x + 7, baseline, 0xffffffff, false);
        for (var phrase : phrases) graphics.drawString(font, phrase.text(), x + phrase.x(), baseline, 0xffffffff, false);
        for (var chip : chips) {
            ScratchBlockArt.draw(graphics,x+chip.x(),y+2,chip.width(),CHIP_HEIGHT,
                Category.CUSTOM,chip.parameter().bool()?Region.BOOLEAN:Region.REPORTER);
            graphics.drawString(font, chip.label(), x + chip.x() + chip.inset(), baseline, 0xffffffff, false);
        }
    }
}
