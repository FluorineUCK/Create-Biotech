package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Kind;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ScratchBlockArt.Region;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Presentation for the Scratch Blocks atlas. No hit-testing or instruction semantics. */
final class FlowBlockSkin {
    static final int INK = 0xff25313e;
    private static final int TITLE_INSET = 8, MOUTH_TITLE_INSET = 23, TITLE_RIGHT_PADDING = 6;
    private static final int TOOLBOX_CAPTION_PADDING = 6;
    private static final float MIN_TOOLBOX_CAPTION_SCALE = 0.6f;
    private record CavityPreview(int head, int firstGap, int middle, int secondGap, int foot) {}
    private static final CavityPreview SINGLE_MOUTH = new CavityPreview(10, 4, 0, 0, 4);
    // The middle slice overlaps the spine above its origin. Give it room so that it cannot seal the upper opening.
    private static final CavityPreview DOUBLE_MOUTH = new CavityPreview(8, 4, 2, 3, 2);
    enum Shape { HAT, DEFINITION, PROTOTYPE, STACK, MOUTH, MIDDLE, FOOT, REPORTER, BOOLEAN, LITERAL, BOOLEAN_LITERAL, CAP }

    private FlowBlockSkin() {}

    static int ink(Category category, Shape shape) {
        if (shape == Shape.LITERAL || shape == Shape.BOOLEAN_LITERAL) return INK;
        return switch (category) {
            case DATA, CUSTOM -> 0xffffffff;
            default -> INK;
        };
    }

    static Shape shape(InlineGraphEdits.Source source) {
        // An argument accepting a Boolean does not make a block a predicate.
        var output = switch (source) {
            case InlineGraphEdits.Operator op -> op.operation().outputType(op.operation().inputs(), "palette");
            case InlineGraphEdits.TextOperator op -> op.operation().outputType(op.operation().inputs(), "palette");
            case InlineGraphEdits.ProcedureResult call -> call.procedure().outputs().get(call.result());
            case InlineGraphEdits.ValueEquality ignored -> DataType.Scalar.BOOLEAN;
            default -> FlowTypeDetails.source(source).stream().findFirst().map(FlowTypeDetails.Hint::type).orElse(null);
        };
        return output == DataType.Scalar.BOOLEAN ? Shape.BOOLEAN : Shape.REPORTER;
    }

    static Shape shape(Kind command) {
        if (command == Kind.STOP_SCRIPT) return Shape.CAP;
        if (command == Kind.WATCH || command == Kind.STARTUP || command == Kind.TERMINAL_KEY || command == Kind.RECEIVE_BROADCAST) return Shape.HAT;
        return command == Kind.IF || command == Kind.IF_ELSE || command == Kind.REPEAT || command == Kind.FOREVER
            || command == Kind.UNTIL || command == Kind.ATOMIC ? Shape.MOUTH : Shape.STACK;
    }

    static int minimumTitleWidth(Font font, String label) {
        return font.width(label) + TITLE_INSET + TITLE_RIGHT_PADDING;
    }

    record PrototypeTitle(String text, int inset) {}
    static PrototypeTitle prototypeTitle(Font font, String label, int width, boolean cavity) {
        return prototypeTitle(font, label, width, cavity, 1f);
    }

    private static PrototypeTitle prototypeTitle(Font font, String label, int width, boolean cavity, float scale) {
        // Keep the ordinary C-head indent when possible, but spend it on the
        // label before truncating. The title stays on the head, not in the mouth.
        int measured = (int)Math.ceil(font.width(label) * scale);
        int inset = cavity ? Math.clamp(width - measured - TITLE_RIGHT_PADDING, TITLE_INSET, MOUTH_TITLE_INSET) : TITLE_INSET;
        int available = Math.max(0, width - inset - TITLE_RIGHT_PADDING);
        return new PrototypeTitle(font.plainSubstrByWidth(label, (int)(available / scale)), inset);
    }

    record ToolboxCaption(String text, float scale) {}
    static ToolboxCaption toolboxCaption(Font font, String label, int width) {
        int available = Math.max(0, width - TOOLBOX_CAPTION_PADDING);
        int measured = font.width(label);
        if (measured <= available) return new ToolboxCaption(label, 1);
        float fit = (float)available / measured;
        if (fit >= MIN_TOOLBOX_CAPTION_SCALE) return new ToolboxCaption(label, fit);
        return new ToolboxCaption(font.plainSubstrByWidth(label, (int)(available / MIN_TOOLBOX_CAPTION_SCALE)), MIN_TOOLBOX_CAPTION_SCALE);
    }

    static void fitToolboxCaption(CreateTextButton button, Font font) {
        button.setIcon((graphics, ignoredX, ignoredY) -> {
            var caption = toolboxCaption(font, button.getMessage().getString(), button.getWidth());
            graphics.pose().pushPose();
            graphics.pose().translate(button.getX() + (button.getWidth() - 2) / 2f,
                button.getY() + (button.getHeight() - 10 * caption.scale()) / 2f, 0);
            graphics.pose().scale(caption.scale(), caption.scale(), 1);
            graphics.drawCenteredString(font, caption.text(), 0, 0, button.active ? 0xffeeeeee : 0xff999999);
            graphics.pose().popPose();
        });
    }

    static void prototype(CreateTextButton button, Font font, Category category, Shape shape) {
        prototype(button, font, category, shape, false);
    }

    static void prototype(CreateTextButton button, Font font, Category category, Shape shape, boolean alternate) {
        boolean cavity = shape == Shape.MOUTH;
        var preview = alternate ? DOUBLE_MOUTH : SINGLE_MOUTH;
        button.setBackground((g, state) -> {
            int x = button.getX(), y = button.getY(), w = button.getWidth();
            boolean hover = state == AllGuiTextures.BUTTON_HOVER;
            if (!cavity) {
                block(g, x, y, w, button.getHeight() - 3, category, shape, hover, !button.active);
                return;
            }
            // The compact palette sample has its own complete C silhouette;
            // the full-size drag preview uses the actual canvas layout.
            if (!button.active) g.setColor(1, 1, 1, .45f);
            try {
                ScratchBlockArt.draw(g, x, y, w, preview.head(), category, Region.MOUTH);
                rail(g, x, y + preview.head(), preview.firstGap(), category);
                int lower = y + preview.head() + preview.firstGap();
                if (preview.middle() > 0) {
                    ScratchBlockArt.draw(g, x, lower, w, preview.middle(), category, Region.MIDDLE);
                    lower += preview.middle();
                    rail(g, x, lower, preview.secondGap(), category);
                    lower += preview.secondGap();
                }
                ScratchBlockArt.draw(g, x, lower, w, preview.foot(), category, Region.FOOT);
            } finally {
                if (!button.active) g.setColor(1, 1, 1, 1);
            }
            if (hover) g.fill(x + w - 5, y + 3, x + w - 3, y + 8, 0xcaffffff);
        });
        button.setIcon((g, x, y) -> {
            int color = button.active ? ink(category, shape) : 0xffeeeeee;
            float scale = cavity ? Math.min(1f, (preview.head() - 1f) / font.lineHeight) : 1f;
            var title = prototypeTitle(font, button.getMessage().getString(), button.getWidth(), cavity, scale);
            int left = button.getX() + title.inset();
            if (!cavity) {
                g.drawString(font, title.text(), left, button.getY() + (button.getHeight() - 9) / 2, color, false);
                return;
            }
            float top = button.getY() + (preview.head() - font.lineHeight * scale) / 2f;
            g.pose().pushPose();
            try {
                g.pose().translate(left, top, 0);
                g.pose().scale(scale, scale, 1);
                g.drawString(font, title.text(), 0, 0, color, false);
            } finally {
                g.pose().popPose();
            }
        });
    }

    static void block(GuiGraphics g, int x, int y, int w, int h, Category category, Shape shape, boolean selected, boolean ghost) {
        if (w < 12 || h < 8) return;
        Region region = switch (shape) {
            case HAT -> Region.HAT;
            case DEFINITION -> Region.DEFINITION;
            case PROTOTYPE -> Region.PROTOTYPE;
            case STACK -> Region.STACK;
            case CAP -> Region.CAP;
            case MOUTH -> Region.MOUTH;
            case MIDDLE -> Region.MIDDLE;
            case FOOT -> Region.FOOT;
            case REPORTER -> Region.REPORTER;
            case BOOLEAN, BOOLEAN_LITERAL -> Region.BOOLEAN;
            case LITERAL -> Region.LITERAL;
        };
        if (ghost) g.setColor(1, 1, 1, .45f);
        try {
            ScratchBlockArt.draw(g, x, y, w, h, category, region);
        } finally {
            if (ghost) g.setColor(1, 1, 1, 1);
        }
        if (selected) g.fill(x + w - 5, y + 5, x + w - 3, y + h - 4, 0xcaffffff);
    }

    static void rail(GuiGraphics g, int x, int y, int h, Category category) {
        ScratchBlockArt.draw(g, x, y, FlowBlockCanvas.INDENT, h, category, Region.RAIL);
    }

}
