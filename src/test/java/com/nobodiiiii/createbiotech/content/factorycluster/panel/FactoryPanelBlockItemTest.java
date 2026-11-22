package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.biocomputing.block.TransparentComputerCasingBlock;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortItem;

class FactoryPanelBlockItemTest {
	@Test
	void carriedPortShowsTheAliasOfItsSelectionNotAnArchivedBinding() {
		var item = new MaterialRequestPortItem(new MaterialRequestPortBlock(BlockBehaviour.Properties.of()),
			new net.minecraft.world.item.Item.Properties());
		var stack = new net.minecraft.world.item.ItemStack(item);
		var selected = java.util.UUID.randomUUID();
		var directory = new com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory();
		directory.ensure(selected);
		directory.compareAndSetAlias(selected, 0, java.util.Optional.of("面板仓"));
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltipData.refresh(stack, List.of(selected), directory);
		var tag = new net.minecraft.nbt.CompoundTag();
		tag.putUUID("Freq", selected);
		tag.putString("id", "create_biotech:biocomputing_material_request_port");
		stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(tag));
		assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltip.forNativePort(stack, false).toString().contains("面板仓"));
		tag.putUUID("Freq", java.util.UUID.randomUUID());
		stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(tag));
		assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltip.forNativePort(stack, false).stream()
			.noneMatch(line -> line.toString().contains("面板仓")));
	}

	@Test
	void panelIsRecognizedByCreatesNativeTuningAndOutlineHandler() {
		var item = new MaterialRequestPortItem(new MaterialRequestPortBlock(BlockBehaviour.Properties.of()),
			new net.minecraft.world.item.Item.Properties());
		assertTrue(com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem.class.isInstance(item),
			"Create yields linked-block clicks and renders outlines only for its native linked item type");
	}

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void embedsOnlyOnHorizontalComputerShellsAndCapturesExactBaseMaterial() {
		MaterialRequestPortBlock panel = new MaterialRequestPortBlock(BlockBehaviour.Properties.of());
		BlockState opaque = new ComputerCasingBlock(BlockBehaviour.Properties.of()).defaultBlockState();
		BlockState transparent = new TransparentComputerCasingBlock(BlockBehaviour.Properties.of()).defaultBlockState();

		assertEquals(ComputerShellMaterial.OPAQUE,
			MaterialRequestPortItem.embeddingMaterial(opaque, Direction.EAST).orElseThrow());
		assertEquals(ComputerShellMaterial.TRANSPARENT,
			MaterialRequestPortItem.embeddingMaterial(transparent, Direction.WEST).orElseThrow());
		assertTrue(MaterialRequestPortItem.embeddingMaterial(Blocks.STONE.defaultBlockState(), Direction.EAST)
			.isEmpty());
		assertTrue(MaterialRequestPortItem.embeddingMaterial(opaque, Direction.UP).isEmpty());
		assertTrue(MaterialRequestPortItem.embeddingMaterial(panel.defaultBlockState(), Direction.EAST)
			.isEmpty());
	}

	@Test
	void embeddedPanelMultipartPreservesBothCasingBasesAndPanelFaceForEveryHorizontalFacing() throws Exception {
		String blockstate = resource("assets/create_biotech/blockstates/factory_panel.json");
		assertTrue(blockstate.contains("\"multipart\""));
		assertTrue(blockstate.contains("\"base_material\": \"opaque\""));
		assertTrue(blockstate.contains("create_biotech:block/biocomputing/computer/casing"));
		assertTrue(blockstate.contains("\"base_material\": \"transparent\""));
		assertTrue(blockstate.contains("create_biotech:block/biocomputing/computer/transparent_casing"));
		for (String facing : List.of("north", "east", "south", "west"))
			assertTrue(blockstate.contains("\"facing\": \"" + facing + "\""));
		assertEquals(4, count(blockstate, "create_biotech:block/biocomputing/terminal/panel"));
	}

	private static int count(String value, String needle) {
		return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
	}

	private static String resource(String path) throws Exception {
		try (InputStream stream = FactoryPanelBlockItemTest.class.getClassLoader().getResourceAsStream(path)) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
