package com.nobodiiiii.createbiotech.content.biocomputing;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;

class BiocomputingArtPackTest {
    private static final Path PACK = Path.of("art/biocomputing/pack");
    private static final Path MOD = Path.of("src/main/resources");

    @Test void theEditablePackIsAlsoTheDefaultPackagedArt() throws Exception {
        assertTrue(Files.isRegularFile(PACK.resolve("pack.mcmeta")), "Artists can load this directory directly");
        var pack = JsonParser.parseString(Files.readString(PACK.resolve("pack.mcmeta"))).getAsJsonObject();
        assertEquals(34, pack.getAsJsonObject("pack").get("pack_format").getAsInt());
        for (String path : new String[] {
                "textures/block/biocomputing/block_reader/front.png",
                "textures/block/biocomputing/computer/transparent_casing_connected.png",
                "textures/block/biocomputing/data_cable/hub.png",
                "models/block/biocomputing/block_reader.json",
                "models/block/biocomputing/text_output.json",
                "blockstates/biocomputing_block_reader.json",
                "models/item/biocomputing_block_reader.json",
                "blockstates/biocomputing_component_settings.json",
                "models/block/biocomputing/component_settings.json",
                "models/item/biocomputing_component_settings.json",
                "textures/gui/sprites/pattern/button/pressed.png.mcmeta",
                "textures/gui/pattern_factory_gauge.png"
        }) {
            Path relative = Path.of("assets/create_biotech").resolve(path);
            assertFalse(Files.exists(MOD.resolve(relative)), "Only one editable source: " + path);
            try (var packaged = getClass().getResourceAsStream("/assets/create_biotech/" + path)) {
                assertNotNull(packaged, path);
                assertArrayEquals(Files.readAllBytes(PACK.resolve(relative)), packaged.readAllBytes(), path);
            }
        }
        try (var packaged = getClass().getResourceAsStream("/pack.mcmeta")) {
            assertNotNull(packaged);
            assertArrayEquals(Files.readAllBytes(MOD.resolve("pack.mcmeta")), packaged.readAllBytes());
        }
    }

    @Test void onlyTheCarrierCableShipsAsAPhysicalBlock() {
        for (String suffix : List.of("cable", "divider", "multiplier")) {
            String id = "biocomputing_carrier_" + suffix;
            boolean available = suffix.equals("cable");
            for (String path : List.of(
                    "assets/create_biotech/blockstates/" + id + ".json",
                    "assets/create_biotech/models/item/" + id + ".json",
                    "data/create_biotech/recipe/crafting/" + id + ".json",
                    "data/create_biotech/loot_table/blocks/" + id + ".json")) {
                assertEquals(available, getClass().getResource("/" + path) != null, path);
            }
        }
    }

    @Test void scratchPartsAreIndividuallyReplaceableSprites() throws Exception {
        for (String part : new String[]{"canvas", "category", "joint", "rail", "selection", "drop_outline", "assignment", "reporter_drag",
                "block/main", "block/control", "block/call", "block/data", "block/serial", "block/signal", "block/carried",
                "connector/main", "connector/control", "connector/call", "connector/data", "connector/serial",
                "connector/signal", "connector/carried", "value/literal", "value/expression",
                "value/boolean_literal", "value/boolean_expression", "input"}) {
            String path = "assets/create_biotech/textures/gui/sprites/biocomputing/" + part + ".png";
            try (var packaged = getClass().getResourceAsStream("/" + path)) {
                assertNotNull(packaged, part);
                var source = PACK.resolve(path);
                var image = ImageIO.read(source.toFile());
                assertNotNull(image, part);
                assertArrayEquals(Files.readAllBytes(source), packaged.readAllBytes(), part);
                if (Files.exists(Path.of(source + ".mcmeta"))) {
                    try (var metadata = getClass().getResourceAsStream("/" + path + ".mcmeta")) {
                        assertNotNull(metadata, part);
                        var scaling = JsonParser.parseString(new String(metadata.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonObject("gui").getAsJsonObject("scaling");
                        assertEquals(image.getWidth(), scaling.get("width").getAsInt(), part);
                        assertEquals(image.getHeight(), scaling.get("height").getAsInt(), part);
                    }
                }
            }
        }
    }

    @Test void minecraftLoadsTheStandalonePackAndUsesItInPreferenceToTheDefaultArt() throws Exception {
        // Use the game's actual pack reader and priority stack, without opening a world.
        var defaults = new PathPackResources(
            new PackLocationInfo("defaults", Component.literal("Defaults"), PackSource.DEFAULT, Optional.empty()),
            Path.of("build/resources/main"));
        var art = new PathPackResources(
            new PackLocationInfo("editable-art", Component.literal("Art"), PackSource.DEFAULT, Optional.empty()), PACK);
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(defaults, art))) {
            try (var metadata = art.getRootResource("pack.mcmeta").get()) {
                var json = JsonParser.parseReader(new java.io.InputStreamReader(metadata, java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(34, json.getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt());
            }
            for (String path : List.of("textures/block/biocomputing/block_reader/front.png",
                    "models/block/biocomputing/block_reader.json",
                    "textures/gui/sprites/biocomputing/block/control.png")) {
                var id = ResourceLocation.fromNamespaceAndPath("create_biotech", path);
                assertEquals(2, manager.getResourceStack(id).size(), path);
                var visible = manager.getResource(id).orElseThrow();
                assertEquals("editable-art", visible.sourcePackId(), path);
                try (var stream = visible.open()) {
                    assertArrayEquals(Files.readAllBytes(PACK.resolve("assets/create_biotech").resolve(path)),
                        stream.readAllBytes(), path);
                }
            }
        }
    }
}
