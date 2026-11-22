package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ScalarResourcesTest {
    /** Missing translations would expose internal operation IDs in the player chooser. */
    @Test void everyPublicOperationCategoryAndChooserControlHasPackagedBilingualLabels() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            try (var stream = getClass().getResourceAsStream("/assets/create_biotech/lang/" + locale + ".json")) {
                assertNotNull(stream, locale);
                JsonObject language = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                label(language, "editor.kind.scalar", locale);
                for (var operation : ScalarOperation.values())
                    label(language, "operation.name." + operation.name().toLowerCase(Locale.ROOT), locale);
                for (var category : ScalarOperation.Category.values())
                    label(language, "operation.category." + category.name().toLowerCase(Locale.ROOT), locale);
                for (String control : List.of("title", "choose", "category", "previous", "next", "back",
                        "hint.eager", "hint.numeric", "hint.integer_to_decimal", "hint.decimal_to_integer",
                        "hint.sqrt", "hint.power", "hint.remainder", "hint.round", "hint.ceil", "hint.floor", "hint.is_error", "hint.error_extract"))
                    label(language, "operation." + control, locale);
            }
        }
    }

    private static void label(JsonObject language, String suffix, String locale) {
        String key = "create_biotech.biocomputing." + suffix;
        assertTrue(language.has(key), locale + ": " + key);
        assertFalse(language.get(key).getAsString().isBlank(), locale + ": " + key);
    }
}
