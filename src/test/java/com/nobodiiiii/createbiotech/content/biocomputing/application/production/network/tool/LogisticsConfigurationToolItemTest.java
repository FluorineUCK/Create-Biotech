package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;

class LogisticsConfigurationToolItemTest {
	private static final UUID NETWORK =
		UUID.fromString("40000000-0000-0000-0000-000000000001");

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

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(), List.of(),
				List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}






	@Test
	void capturedToolRendersSyncedAliasForNormalAndAdvancedTooltips() {
		LogisticsConfigurationToolItem item = new LogisticsConfigurationToolItem(
			new Item.Properties().stacksTo(1));
		ItemStack stack = new ItemStack(item);
		assertFalse(item.isFoil(stack));
		LogisticsToolData.write(stack, new LogisticsToolData(Optional.of(NETWORK)));
		var directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.compareAndSetAlias(NETWORK, 0, Optional.of("工具仓"));
		assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltipData
			.refresh(stack, List.of(NETWORK), directory));

		List<Component> normal = new java.util.ArrayList<>();
		List<Component> advanced = new java.util.ArrayList<>();
		item.appendHoverText(stack, null, normal, TooltipFlag.Default.NORMAL);
		item.appendHoverText(stack, null, advanced, TooltipFlag.Default.ADVANCED);
		assertTrue(item.isFoil(stack));
		assertTrue(rendered(normal).contains("工具仓"));
		assertTrue(rendered(advanced).contains("工具仓"));
		assertFalse(rendered(normal).contains(NETWORK.toString()));
		assertFalse(rendered(advanced).contains(NETWORK.toString()));

		LogisticsToolData.clear(stack);
		List<Component> cleared = new java.util.ArrayList<>();
		item.appendHoverText(stack, null, cleared, TooltipFlag.Default.NORMAL);
		assertFalse(item.isFoil(stack));
		assertTrue(rendered(cleared).contains("unbound"));
	}

	private static String rendered(List<Component> lines) {
		StringBuilder result = new StringBuilder();
		for (Component line : lines) result.append(representation(line));
		return result.toString();
	}

	private static String representation(Object component) {
		StringBuilder result = new StringBuilder(String.valueOf(component));
		try {
			Object siblings = component.getClass().getMethod("getSiblings").invoke(component);
			for (Object sibling : (List<?>) siblings) result.append(representation(sibling));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		return result.toString();
	}







	@Test
	void toolDataOwnsOneBoundedUuidAndRejectsForeignShape() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		assertEquals(Optional.of(NETWORK), LogisticsToolData.read(tool).networkId());
		LogisticsToolData.clear(tool);
		assertEquals(Optional.empty(), LogisticsToolData.read(tool).networkId());
		assertThrows(IllegalArgumentException.class,
			() -> new LogisticsToolData(null));
	}

	@Test
	void captureWritesTheNativeCreateNetworkPayload() {
		ItemStack tool = tool();

		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));

		assertEquals(NETWORK, LogisticallyLinkedBlockItem.networkFromStack(tool));
		assertTrue(LogisticallyLinkedBlockItem.isTuned(tool));
		assertEquals("create:packager_link", tool.get(DataComponents.BLOCK_ENTITY_DATA).copyTag().getString("id"));
	}

	@Test
	void nativeClearCannotResurrectAFormerCustomNetwork() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));

		tool.remove(DataComponents.BLOCK_ENTITY_DATA);

		assertEquals(Optional.empty(), LogisticsToolData.read(tool).networkId());
		assertFalse(hasLegacyNetwork(tool));
	}

	@Test
	void legacyCustomNetworkMigratesOnceAndKeepsUnrelatedCustomData() {
		ItemStack tool = tool();
		CompoundTag root = new CompoundTag();
		root.putString("Unrelated", "kept");
		CompoundTag legacy = new CompoundTag();
		legacy.putUUID("NetworkId", NETWORK);
		legacy.putBoolean("FirstAliasPending", true);
		root.put("CreateBiotechLogisticsTool", legacy);
		tool.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

		assertEquals(Optional.of(NETWORK), LogisticsToolData.read(tool).networkId());
		assertEquals(NETWORK, LogisticallyLinkedBlockItem.networkFromStack(tool));
		assertFalse(hasLegacyNetwork(tool));
		assertEquals("kept", tool.get(DataComponents.CUSTOM_DATA).copyTag().getString("Unrelated"));

		tool.remove(DataComponents.BLOCK_ENTITY_DATA);
		assertEquals(Optional.empty(), LogisticsToolData.read(tool).networkId());
	}

	@Test
	void nativeNetworkWinsCoexistingLegacyDataAndDeletesTheFallback() {
		ItemStack tool = tool();
		UUID legacyNetwork = new UUID(0, 77);
		CompoundTag legacyRoot = new CompoundTag();
		CompoundTag legacyPayload = new CompoundTag();
		legacyPayload.putUUID("NetworkId", legacyNetwork);
		legacyRoot.put("CreateBiotechLogisticsTool", legacyPayload);
		tool.set(DataComponents.CUSTOM_DATA, CustomData.of(legacyRoot));
		CompoundTag nativePayload = new CompoundTag();
		nativePayload.putUUID("Freq", NETWORK);
		tool.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(nativePayload));

		assertEquals(Optional.of(NETWORK), LogisticsToolData.read(tool).networkId());
		assertFalse(hasLegacyNetwork(tool));
		tool.remove(DataComponents.BLOCK_ENTITY_DATA);
		assertEquals(Optional.empty(), LogisticsToolData.read(tool).networkId());
	}

	@Test
	void retuningClearsPendingAliasForTheObsoleteNetwork() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		LogisticsToolData.setFirstAliasPending(tool, NETWORK, true);
		UUID replacement = new UUID(0, 99);

		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(replacement)));

		assertEquals(Optional.of(replacement), LogisticsToolData.read(tool).networkId());
		assertFalse(LogisticsToolData.firstAliasPending(tool, replacement));
		assertFalse(LogisticsToolData.firstAliasPending(tool, NETWORK));
	}

	private static boolean hasLegacyNetwork(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		CompoundTag root = data.copyTag();
		return root.contains("CreateBiotechLogisticsTool", net.minecraft.nbt.Tag.TAG_COMPOUND)
			&& root.getCompound("CreateBiotechLogisticsTool").hasUUID("NetworkId");
	}

	@Test
	void itemModelUsesAnAvailableNetworkToolSprite() throws Exception {
		String path = "assets/create_biotech/models/item/logistics_configuration_tool.json";
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
			String model = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(model.contains("create:item/transmitter"));
		}
		try (InputStream sprite = getClass().getClassLoader().getResourceAsStream("assets/create/textures/item/transmitter.png")) {
			org.junit.jupiter.api.Assertions.assertNotNull(sprite, "The bundled Create version supplies the fallback sprite");
			assertEquals(0x89504e47, new java.io.DataInputStream(sprite).readInt(), "PNG signature");
		}
	}


	private static ItemStack tool() {
		return new ItemStack(new LogisticsConfigurationToolItem(
			new Item.Properties().stacksTo(1)));
	}


}
