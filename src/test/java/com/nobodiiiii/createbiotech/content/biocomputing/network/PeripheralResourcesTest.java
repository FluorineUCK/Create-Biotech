package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PeripheralResourcesTest {
    @Test void cableAndReaderHaveCompleteUsableAssetsAndSurvivalRecipes() throws Exception {
        for (String id : new String[]{"biocomputing_data_cable", "biocomputing_block_reader", "biocomputing_redstone_output"}) {
            var state = json("assets/create_biotech/blockstates/" + id + ".json");
            assertTrue(state.has("multipart") || state.has("variants"));
            String parent = json("assets/create_biotech/models/item/" + id + ".json").get("parent").getAsString();
            assertTrue(json("assets/" + parent.replace(":", "/models/") + ".json").has("textures"));
            assertEquals("create_biotech:" + id, json("data/create_biotech/recipe/crafting/" + id + ".json")
                .getAsJsonObject("result").get("id").getAsString());
            assertEquals("create_biotech:" + id, json("data/create_biotech/loot_table/blocks/" + id + ".json")
                .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
            for (String locale : new String[]{"en_us", "zh_cn"}) assertTrue(json("assets/create_biotech/lang/" + locale + ".json").has("block.create_biotech." + id));
        }
        var reader = json("assets/create_biotech/blockstates/biocomputing_block_reader.json").getAsJsonObject("variants");
        assertEquals(6, reader.size());
        var cable = json("assets/create_biotech/blockstates/biocomputing_data_cable.json").getAsJsonArray("multipart");
        assertEquals(7, cable.size());
        for (var part : cable) {
            String model = part.getAsJsonObject().getAsJsonObject("apply").get("model").getAsString();
            assertTrue(json("assets/" + model.replace(":", "/models/") + ".json").has("elements"));
        }
    }
    private static JsonObject json(String resource) throws Exception {
        try (var stream = PeripheralResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
