package com.nobodiiiii.createbiotech.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

class ComputerModelResourceTest {
	private static final Path ASSETS = Path.of("art/biocomputing/pack/assets/create_biotech");
	private static final Pattern TEXTURE_REFERENCE = Pattern.compile(
		"\\\"(?:[0-9]+|particle)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void splitComputerGeometryUsesOnlyPresentCreateBiotechTextures() throws IOException {
		for (String name : Set.of("lower.json", "upper.json")) {
			String model = Files.readString(ASSETS.resolve("models/block/biocomputing/computer").resolve(name));
			assertTrue(model.contains("\"elements\""), name + " is not Computer geometry");
			Matcher textures = TEXTURE_REFERENCE.matcher(model);
			Set<String> declared = new HashSet<>();
			boolean found = false;
			while (textures.find()) {
				found = true;
				declared.add(textures.group(0).substring(1,
					textures.group(0).indexOf('"', 1)));
				String reference = textures.group(1);
				if (reference.startsWith("#")) continue;
				assertTrue(reference.startsWith("create_biotech:block/"),
					() -> "foreign or unresolved Computer texture: " + reference);
				String path = reference.substring("create_biotech:".length());
				assertTrue(Files.isRegularFile(ASSETS.resolve("textures").resolve(path + ".png")),
					() -> "missing Computer texture: " + reference);
			}
			Matcher faceReferences = Pattern.compile("\\\"texture\\\"\\s*:\\s*\\\"#([^\\\"]+)\\\"")
				.matcher(model);
			while (faceReferences.find())
				assertTrue(declared.contains(faceReferences.group(1)),
					() -> "undefined Computer texture variable: #" + faceReferences.group(1));
			assertTrue(found, name + " declares no textures");
		}
		String upper = Files.readString(ASSETS.resolve("models/block/biocomputing/computer/upper.json"));
		assertTrue(upper.contains("\"from\":[3,7,3]"),
			"the source head is rebased from y=23 to the upper model's y=7");
	}

	@Test
	void computerItemContinuesToUseTheBlockModel() throws IOException {
		String item = Files.readString(ASSETS.resolve("models/item/computer.json"));
		assertTrue(item.contains("\"parent\": \"create_biotech:block/biocomputing/computer/lower\""));
	}

	@Test
	void transparentCasingHasSelfDropLootAndGeneratedPickaxeMiningTag() throws IOException {
		Path loot = Path.of("src/main/resources/data/create_biotech/loot_table/blocks/"
			+ "transparent_computer_casing.json");
		assertTrue(Files.isRegularFile(loot),
			"the registered transparent casing needs a self-drop loot table");
		JsonObject lootJson = JsonParser.parseString(Files.readString(loot)).getAsJsonObject();
		assertTrue(lootJson.getAsJsonArray("pools").toString()
			.contains("create_biotech:transparent_computer_casing"),
			"the transparent casing loot table must emit itself");

		Path pickaxeTag = Path.of("src/generated/resources/data/minecraft/tags/block/mineable/pickaxe.json");
		JsonArray values = JsonParser.parseString(Files.readString(pickaxeTag)).getAsJsonObject()
			.getAsJsonArray("values");
		assertTrue(values.asList().stream()
			.anyMatch(value -> value.getAsString()
				.equals("create_biotech:transparent_computer_casing")),
			"generated pickaxe mining data must include transparent casing");
	}

	@Test
	void blockstateVariantsAndModelBoxesMatchRuntimeComputerShape() throws IOException {
		JsonObject variants = JsonParser.parseString(Files.readString(
			ASSETS.resolve("blockstates/computer.json"))).getAsJsonObject().getAsJsonObject("variants");
		Set<String> expectedKeys = new HashSet<>();
		Map<String, Integer> rotations = Map.of("north", 0, "east", 90, "south", 180, "west", 270);
		for (String half : List.of("lower", "upper")) {
			for (var entry : rotations.entrySet()) {
				String key = "facing=" + entry.getKey() + ",half=" + half;
				expectedKeys.add(key);
				JsonObject variant = variants.getAsJsonObject(key);
				assertEquals("create_biotech:block/biocomputing/computer/" + half,
					variant.get("model").getAsString());
				assertEquals(entry.getValue().intValue(),
					variant.has("y") ? variant.get("y").getAsInt() : 0);
			}
		}
		assertEquals(expectedKeys, variants.keySet(),
			"Computer blockstate must expose exactly four facings for each physical half");

		JsonArray lowerElements = modelElements("lower.json");
		JsonArray upperElements = modelElements("upper.json");
		assertEquals(8, lowerElements.size());
		assertEquals(3, upperElements.size());
		ComputerBlock computer = new ComputerBlock(Block.Properties.of());
		assertEquals(shape(lowerElements).toAabbs(), computer.defaultBlockState()
			.setValue(ComputerBlock.FACING, Direction.NORTH)
			.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER)
			.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs());
		assertEquals(shape(upperElements).toAabbs(), computer.defaultBlockState()
			.setValue(ComputerBlock.FACING, Direction.NORTH)
			.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER)
			.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs());
	}

	private static JsonArray modelElements(String file) throws IOException {
		return JsonParser.parseString(Files.readString(ASSETS.resolve("models/block/biocomputing/computer").resolve(file)))
			.getAsJsonObject().getAsJsonArray("elements");
	}

	private static VoxelShape shape(JsonArray elements) {
		VoxelShape shape = Shapes.empty();
		for (var value : elements) {
			JsonObject element = value.getAsJsonObject();
			JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
			shape = Shapes.or(shape, Block.box(from.get(0).getAsDouble(), from.get(1).getAsDouble(),
				from.get(2).getAsDouble(), to.get(0).getAsDouble(), to.get(1).getAsDouble(),
				to.get(2).getAsDouble()));
		}
		return shape;
	}
}
