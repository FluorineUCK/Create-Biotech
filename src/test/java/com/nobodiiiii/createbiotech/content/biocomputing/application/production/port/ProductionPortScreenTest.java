package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class ProductionPortScreenTest {
    @Test void smallAndLargeLayoutsKeepInputSourcesPagingAndActionsSeparate() {
        for (int height : new int[] {204, 240, 270, 320, 720}) {
            for (int choices : new int[] {0, 1, 2, 8, 128}) {
                var layout = ProductionPortScreen.layoutFor(320, height, choices);
                assertTrue(layout.addressY() + 18 <= layout.choicesLabelY());
                assertTrue(layout.choicesLabelY() + 9 <= layout.sourceY());
                int sourceEnd = layout.sourceY() + (layout.rows() - 1) * 22 + 18;
                assertTrue(sourceEnd <= (layout.paging() ? layout.pageY() : layout.grantY()), height + "/" + choices);
                if (layout.paging()) assertTrue(layout.pageY() + 18 <= layout.grantY());
                assertTrue(layout.grantY() + 18 <= layout.bottomY());
                assertTrue(layout.bottomY() + 18 <= layout.height());
                assertTrue(layout.height() <= height);
                assertEquals(choices > layout.rows(), layout.paging());
            }
        }
    }

    @Test void sourceAndUnknownDiagnosticsEscapeLiteralFormattingInLabelsAndTooltips() {
        var previous = Language.getInstance();
        var translations = Map.of("create_biotech.production_port.source", "%s at %s / %s / %s",
            "create_biotech.production_port.computer", "Computer");
        try {
            Language.inject(new Language() {
                @Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
                @Override public boolean has(String key) { return translations.containsKey(key); }
                @Override public boolean isDefaultRightToLeft() { return false; }
                @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
            });
            var binding = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("computer"),
                "terminal", new DataValue.TextValue("1, 2, 3"), "port", new DataValue.TextValue("bad\n§c\"name"),
                "member", new DataValue.TextValue("4, 5, 6")));
            assertEquals("Computer at \"1, 2, 3\" / \"bad\\n\\u00a7c\\\"name\" / \"4, 5, 6\"",
                ProductionPortScreen.description(binding).getString());
            assertEquals("\"future_error\\n\\u00a7c\"", ProductionPortScreen.statusText("future_error\n§c").getString());
            assertEquals("", ProductionPortScreen.statusText("").getString());
        } finally { Language.inject(previous); }
    }
}
