package com.nobodiiiii.createbiotech.content.biocomputing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class BiocomputingAssetMigrationTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/create_biotech");
	private static final Path ART = Path.of("art/biocomputing/pack/assets/create_biotech");
	private static final Map<String, String> MODELS = Map.of(
		"computer_lower", "computer/lower", "computer_upper", "computer/upper",
		"computer_casing", "computer/casing", "transparent_computer_casing", "computer/transparent_casing",
		"biocomputing_material_request_port", "terminal/panel");
	private static final List<String> TEXTURES = List.of("base", "stage", "restrictor_feet_1",
		"restrictor_feet_2", "restrictor_head", "transparent_casing", "transparent_casing_connected");

	@Test
	void computationBelongsToTheContentModuleWithoutAForkInFoundation() throws Exception {
		var machine = Class.forName("com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine");
		assertEquals("com.nobodiiiii.createbiotech.content.biocomputing.computation", machine.getPackageName());
		for (String sourceSet : List.of("main", "test")) {
			Path old = Path.of("src", sourceSet, "java/com/nobodiiiii/createbiotech/foundation/computation");
			assertFalse(Files.exists(old), "the old package should be moved, not duplicated: " + old);
		}
	}

	@Test
	void physicalModelsAreMovedNotDuplicatedAndPackagedAtTheirNewPaths() throws IOException {
		for (var model : MODELS.entrySet()) {
			assertFalse(Files.exists(ASSETS.resolve("models/block/" + model.getKey() + ".json")));
			String path = "models/block/biocomputing/" + model.getValue() + ".json";
			assertTrue(Files.isRegularFile(ART.resolve(path)), path);
			try (var packaged = getClass().getResourceAsStream("/assets/create_biotech/" + path)) {
				assertNotNull(packaged, "the moved model must also be included by processResources");
			}
		}
	}

	@Test
	void physicalTexturesAndConnectedPairMoveWithoutLeavingOldCopies() throws IOException {
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		for (String name : TEXTURES) {
			Path path = ART.resolve("textures/block/biocomputing/computer/" + name + ".png");
			try (var stream = Files.newInputStream(path)) {
				assertArrayEquals(signature, stream.readNBytes(8), "valid PNG: " + name);
			}
			String old = name.startsWith("transparent_casing")
				? name.replace("transparent_casing", "transparent_computer_casing")
				: "computer/" + name;
			assertFalse(Files.exists(ASSETS.resolve("textures/block/" + old + ".png")), old);
		}
	}

	@Test
	void everyLocalParentAndTextureInTheOwnedModelsResolves() throws IOException {
		Path owned = ART.resolve("models/block/biocomputing");
		try (Stream<Path> files = Files.walk(owned)) {
			List<Path> models = files.filter(path -> path.toString().endsWith(".json")).toList();
			assertTrue(models.size() >= MODELS.size(), "migrated models remain present as new peripherals are added");
			for (Path path : models) {
				JsonObject model = read(path);
				if (model.has("parent")) checkReference(model.get("parent").getAsString(), "models", ".json");
				if (model.has("textures"))
					for (var texture : model.getAsJsonObject("textures").entrySet())
						checkReference(texture.getValue().getAsString(), "textures", ".png");
			}
		}
	}

	@Test
	void currentEntryPointsUseBiocomputingAssets() throws IOException {
		for (String name : Set.of("computer", "computer_casing", "transparent_computer_casing", "biocomputing_material_request_port")) {
			var root = name.equals("biocomputing_material_request_port") ? ASSETS : ART;
			assertModelReferences(read(root.resolve("blockstates/" + name + ".json")));
			String parent = read(root.resolve("models/item/" + name + ".json")).get("parent").getAsString();
			assertTrue(parent.startsWith("create_biotech:block/biocomputing/"), name);
			checkReference(parent, "models", ".json");
		}
	}

	private static void assertModelReferences(JsonElement value) {
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				if (entry.getKey().equals("model")) {
					String reference = entry.getValue().getAsString();
					assertTrue(reference.startsWith("create_biotech:block/biocomputing/"), reference);
					checkReference(reference, "models", ".json");
				} else assertModelReferences(entry.getValue());
			}
		} else if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(BiocomputingAssetMigrationTest::assertModelReferences);
		}
	}

	private static void checkReference(String reference, String kind, String suffix) {
		if (!reference.startsWith("create_biotech:")) return; // Vanilla/Create resources are supplied by their packs.
		String path = kind + "/" + reference.substring("create_biotech:".length()) + suffix;
		assertTrue(Files.isRegularFile(ART.resolve(path)) || Files.isRegularFile(ASSETS.resolve(path)),
			"unresolved " + kind + " reference: " + reference);
	}
	private static JsonObject read(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}
}
