package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.biocomputing.block.TransparentComputerCasingBlock;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class FactoryPanelBlockItemTest {
	@Test
	void panelIsRecognizedByCreatesNativeTuningAndOutlineHandler() {
		var item = new FactoryPanelBlockItem(new FactoryPanelBlock(BlockBehaviour.Properties.of()),
			new net.minecraft.world.item.Item.Properties());
		assertTrue(com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem.class.isInstance(item),
			"Create yields linked-block clicks and renders outlines only for its native linked item type");
	}

	@Test
	void singleBindingPublishesNativeFrequencyAndRetuningReplacesIt() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Unrelated", "kept");
		assertTrue(FactoryPanelBlockItem.writeBindingsToTag(tag, List.of(new LogisticsBinding(FIRST_NETWORK))));
		assertTrue(tag.hasUUID("Freq"), "Create's outline handler reads the native Freq field");
		assertEquals(FIRST_NETWORK, tag.getUUID("Freq"));
		assertTrue(FactoryPanelBlockItem.writeBindingsToTag(tag, List.of(new LogisticsBinding(SECOND_NETWORK))));
		assertEquals(SECOND_NETWORK, tag.getUUID("Freq"));
		assertEquals(List.of(new LogisticsBinding(SECOND_NETWORK)), FactoryPanelBlockItem.readBindingsFromTag(tag));
		assertEquals("kept", tag.getString("Unrelated"));
	}

	@Test
	void clearingBindingsAlsoClearsNativeOutlineFrequencyWithoutRemovingIdentity() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("PanelId", UUID.randomUUID());
		tag.putUUID("ClusterId", UUID.randomUUID());
		tag.putUUID("Freq", FIRST_NETWORK);
		FactoryPanelBlockItem.writeBindingsToTag(tag, List.of(new LogisticsBinding(FIRST_NETWORK)));
		FactoryPanelBlockItem.clearBindingsFromTag(tag);
		assertFalse(tag.contains("Freq"));
		assertTrue(FactoryPanelBlockItem.readBindingsFromTag(tag).isEmpty());
		assertTrueIdentitiesRemain(tag);
	}
	private static final UUID FIRST_NETWORK =
		UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_NETWORK =
		UUID.fromString("30000000-0000-0000-0000-000000000002");

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
		FactoryPanelBlock panel = new FactoryPanelBlock(BlockBehaviour.Properties.of());
		BlockState opaque = new ComputerCasingBlock(BlockBehaviour.Properties.of()).defaultBlockState();
		BlockState transparent = new TransparentComputerCasingBlock(BlockBehaviour.Properties.of()).defaultBlockState();

		assertEquals(ComputerShellMaterial.OPAQUE,
			FactoryPanelBlockItem.embeddingMaterial(opaque, Direction.EAST).orElseThrow());
		assertEquals(ComputerShellMaterial.TRANSPARENT,
			FactoryPanelBlockItem.embeddingMaterial(transparent, Direction.WEST).orElseThrow());
		assertTrue(FactoryPanelBlockItem.embeddingMaterial(Blocks.STONE.defaultBlockState(), Direction.EAST)
			.isEmpty());
		assertTrue(FactoryPanelBlockItem.embeddingMaterial(opaque, Direction.UP).isEmpty());
		assertTrue(FactoryPanelBlockItem.embeddingMaterial(panel.defaultBlockState(), Direction.EAST)
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

	@Test
	void bindingTagUpdatesPreserveUnrelatedBlockEntityDataAndNormalizeIds() {
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putString("Unrelated", "kept");
		blockEntityData.putUUID("PanelId", UUID.randomUUID());
		blockEntityData.putUUID("ClusterId", UUID.randomUUID());

		FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, List.of(
			new LogisticsBinding(FIRST_NETWORK),
			new LogisticsBinding(FIRST_NETWORK),
			new LogisticsBinding(SECOND_NETWORK)));

		assertEquals("kept", blockEntityData.getString("Unrelated"));
		assertEquals(List.of(FIRST_NETWORK, SECOND_NETWORK),
			FactoryPanelBlockItem.readBindingsFromTag(blockEntityData).stream()
				.map(LogisticsBinding::logisticsNetworkId).toList());

		FactoryPanelBlockItem.clearBindingsFromTag(blockEntityData);

		assertFalse(blockEntityData.contains("LogisticsBindings"));
		assertEquals("kept", blockEntityData.getString("Unrelated"));
		assertTrueIdentitiesRemain(blockEntityData);
	}

	@Test
	void thirtyThirdItemBindingIsRefusedWithoutChangingPersistedBindings() {
		CompoundTag blockEntityData = new CompoundTag();
		List<LogisticsBinding> maximum = bindings(ClusterBinding.MAX_BINDINGS);
		assertTrue(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, maximum));

		List<LogisticsBinding> oversized = new ArrayList<>(maximum);
		oversized.add(new LogisticsBinding(UUID.randomUUID()));

		assertFalse(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, oversized));
		assertEquals(maximum, FactoryPanelBlockItem.readBindingsFromTag(blockEntityData));
	}

	@Test
	void nonAuthorityConfiguredPanelCannotEditItsOfflineBindingReplica() {
		UUID panelId = UUID.randomUUID();
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putUUID("PanelId", panelId);
		ClusterBinding replica = new ClusterBinding(UUID.randomUUID(), 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				UUID.randomUUID()), bindings(1));
		blockEntityData.put("BindingState", replica.save());

		assertFalse(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData,
			bindings(2)));
		assertEquals(replica,
			ClusterBinding.tryLoad(blockEntityData.getCompound("BindingState"))
				.orElseThrow());
	}

	@Test
	void exhaustedAuthorityRevisionRejectsNativeClearAndRetuneWithoutMutation() {
		UUID panelId = UUID.randomUUID();
		CompoundTag data = new CompoundTag();
		data.putUUID("PanelId", panelId);
		data.putUUID("Freq", FIRST_NETWORK);
		data.put("BindingState", new ClusterBinding(UUID.randomUUID(), Long.MAX_VALUE,
			new ClusterAuthority(ClusterMemberType.PANEL, panelId),
			List.of(new LogisticsBinding(FIRST_NETWORK))).save());
		CompoundTag before = data.copy();

		assertFalse(FactoryPanelBlockItem.canEditBindingsFromTag(data),
			"Native clear must fail before sending feedback or changing Freq when the revision cannot advance");
		assertFalse(FactoryPanelBlockItem.writeBindingsToTag(data,
			List.of(new LogisticsBinding(SECOND_NETWORK))));
		assertEquals(before, data);
	}

	@Test
	void configuredPanelRendersOnlyItsSynchronizedAliasAndGlints() {
		FactoryPanelBlockItem item = new FactoryPanelBlockItem(new FactoryPanelBlock(
			BlockBehaviour.Properties.of()), new net.minecraft.world.item.Item.Properties());
		ItemStack stack = new ItemStack(item);
		CompoundTag data = new CompoundTag();
		ListTag bindings = new ListTag();
		bindings.add(new LogisticsBinding(FIRST_NETWORK).save());
		data.put(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY, bindings);
		stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
		var directory = new com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory();
		directory.ensure(FIRST_NETWORK);
		directory.compareAndSetAlias(FIRST_NETWORK, 0, java.util.Optional.of("面板仓"));
		assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.tooltip.BoundLogisticsTooltipData
			.refresh(stack, List.of(FIRST_NETWORK), directory));

		List<Component> normal = new ArrayList<>();
		List<Component> advanced = new ArrayList<>();
		item.appendHoverText(stack, null, normal, TooltipFlag.Default.NORMAL);
		item.appendHoverText(stack, null, advanced, TooltipFlag.Default.ADVANCED);
		assertTrue(item.isFoil(stack));
		assertFalse(item.isFoil(new ItemStack(item)));
		assertTrue(rendered(normal).contains("面板仓"));
		assertTrue(rendered(advanced).contains("面板仓"));
		assertFalse(rendered(normal).contains(FIRST_NETWORK.toString()));
		assertFalse(rendered(advanced).contains(FIRST_NETWORK.toString()));

		bindings = new ListTag();
		bindings.add(new LogisticsBinding(SECOND_NETWORK).save());
		data.put(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY, bindings);
		stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
		List<Component> mismatch = new ArrayList<>();
		item.appendHoverText(stack, null, mismatch, TooltipFlag.Default.NORMAL);
		assertTrue(rendered(mismatch).contains("synchronizing"));
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

	private static List<LogisticsBinding> bindings(int count) {
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (int index = 0; index < count; index++)
			bindings.add(new LogisticsBinding(new UUID(0, index + 1L)));
		return List.copyOf(bindings);
	}

	private static void assertTrueIdentitiesRemain(CompoundTag tag) {
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("PanelId"));
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("ClusterId"));
	}
}
