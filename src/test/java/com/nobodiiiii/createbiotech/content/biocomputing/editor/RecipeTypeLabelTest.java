package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeTypeLabelTest {
    @Test void actualTreeKindHasLocalizedLabelsInBothShippedLanguages() throws Exception {
        var recipe = new DataValue.RecipeValue("recipe", 1, FoundationRecipeTest.projection(false));
        String key = "create_biotech.biocomputing.tree.type." + ValueTree.kind(recipe);
        for (String language : new String[]{"en_us", "zh_cn"}) {
            try (var stream = getClass().getResourceAsStream("/assets/create_biotech/lang/" + language + ".json")) {
                assertNotNull(stream);
                var labels = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(labels.has(key), language + ": " + key);
                assertFalse(labels.get(key).getAsString().isBlank());
            }
        }
        assertTrue(String.join("", ComputerValueDisplay.lines(recipe, 40)).startsWith("recipe@1"));
    }
}
