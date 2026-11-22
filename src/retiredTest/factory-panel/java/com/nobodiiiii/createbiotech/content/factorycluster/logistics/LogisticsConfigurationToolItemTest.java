package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelOpeningHint;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
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
	void rejectedAliasOpeningRetainsFirstBindIntentForRetry() {
		var directory = new LogisticsNetworkDirectory();
		ItemStack tool = tool();
		var capture = FakeInteraction.linked(NETWORK, true); capture.directory = directory;
		LogisticsConfigurationToolItem.executeServer(tool, capture);
		var rejected = FakeInteraction.panel(true, true, true); rejected.directory = directory;
		rejected.aliasOpeningAccepted = false;
		LogisticsConfigurationToolItem.executeServer(tool, rejected);
		assertFalse(rejected.aliasEditorOpened);
		var retry = FakeInteraction.panel(true, true, true); retry.directory = directory;
		LogisticsConfigurationToolItem.executeServer(tool, retry);
		assertTrue(retry.aliasEditorOpened, "opening refusal must not consume first-bind UI intent");
	}

	@Test
	void captureThenBindUsesOneRealDirectoryAndOpensFirstAliasOnlyOnce() {
		var directory = new LogisticsNetworkDirectory();
		ItemStack tool = tool();
		var capture = FakeInteraction.linked(NETWORK, true); capture.directory = directory;
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, capture));
		assertFalse(capture.aliasEditorOpened, "capture must not open the editor before Cluster binding");
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, capture));
		var denied = FakeInteraction.panel(false, true, true); denied.directory = directory;
		assertEquals(InteractionResult.FAIL, LogisticsConfigurationToolItem.executeServer(tool, denied));
		var panel = FakeInteraction.panel(true, true, true); panel.directory = directory;
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertTrue(panel.aliasEditorOpened, "capture allocation must retain first-bind editor intent across repeated capture and failed bind");
		var repeated = FakeInteraction.panel(true, true, true); repeated.directory = directory;
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, repeated));
		assertFalse(repeated.aliasEditorOpened, "successful opening consumes only the UI intent");
		assertTrue(repeated.panelOpened);
	}

	@Test
	void capturingAnotherKnownNetworkAndClearingCannotCarryOldFirstAliasIntent() {
		var directory = new LogisticsNetworkDirectory();
		UUID known = new UUID(0, 920); directory.ensure(known);
		for (boolean clear : List.of(false, true)) {
			ItemStack tool = tool();
			var first = FakeInteraction.linked(NETWORK, true);
			LogisticsConfigurationToolItem.executeServer(tool, first);
			if (clear) LogisticsToolData.clear(tool);
			var different = FakeInteraction.linked(known, true); different.directory = directory;
			LogisticsConfigurationToolItem.executeServer(tool, different);
			var panel = FakeInteraction.panel(true, true, true); panel.directory = directory;
			LogisticsConfigurationToolItem.executeServer(tool, panel);
			assertFalse(panel.aliasEditorOpened, "an unrelated capture must not inherit pending first alias intent");
			assertEquals(Optional.of(known), panel.selectedNetwork);
		}
	}

	@Test
	void capturesNetworkThenBindsWholeFormedClusterAtPanel() {
		ItemStack tool = tool();
		FakeInteraction capture = FakeInteraction.linked(NETWORK, true);

		assertEquals(InteractionResult.SUCCESS,
			LogisticsConfigurationToolItem.executeServer(tool, capture));
		assertEquals(NETWORK, LogisticsToolData.read(tool).networkId().orElseThrow());
		assertTrue(tool.getItem().isFoil(tool));

		FakeInteraction panel = FakeInteraction.panel(true, true, false);
		assertEquals(InteractionResult.SUCCESS,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertEquals(List.of(new LogisticsBinding(NETWORK)), panel.boundBindings);
		assertTrue(panel.aliasEditorOpened);
		assertEquals(FactoryPanelOpeningHint.DEFAULT, panel.openingHint,
			"an alias-only opening must not leak a shared Panel context");
		assertEquals(Optional.empty(), panel.selectedNetwork);
	}

	@Test
	void linkedComponentPermissionFailureDoesNotCaptureUuid() {
		ItemStack tool = tool();
		assertEquals(InteractionResult.FAIL, LogisticsConfigurationToolItem.executeServer(
			tool, FakeInteraction.linked(NETWORK, false)));
		assertEquals(Optional.empty(), LogisticsToolData.read(tool).networkId());
		assertFalse(tool.getItem().isFoil(tool));
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
		assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.tooltip.BoundLogisticsTooltipData
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
	void rejectsUnformedPanelWithoutRegisteringOrChangingTool() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		FakeInteraction panel = FakeInteraction.panel(false, true, false);

		assertEquals(InteractionResult.FAIL,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertTrue(panel.boundBindings.isEmpty());
		assertFalse(panel.aliasEditorOpened);
		assertFalse(panel.panelOpened);
		assertEquals(Optional.of(NETWORK), LogisticsToolData.read(tool).networkId());
	}

	@Test
	void administrationFailureFailsClosedBeforeDirectoryOrMenu() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		FakeInteraction panel = FakeInteraction.panel(true, false, false);

		assertEquals(InteractionResult.FAIL,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertFalse(panel.ensureCalled);
		assertFalse(panel.aliasEditorOpened);
		assertFalse(panel.panelOpened);
	}

	@Test
	void fullDirectoryFailsBeforeAnyClusterBindingRevisionOrSelectionMutation() {
		ItemStack tool = tool();
		UUID refusedNetwork = new UUID(0,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L);
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(refusedNetwork)));
		LogisticsNetworkDirectory directory = fullDirectory();
		UUID priorNetwork = NETWORK;
		FakeInteraction panel = FakeInteraction.panel(true, true, true);
		panel.directory = directory;
		panel.boundBindings = List.of(new LogisticsBinding(priorNetwork));
		panel.bindingRevision = 7;
		panel.selectedNetwork = Optional.of(priorNetwork);

		assertEquals(InteractionResult.FAIL,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertTrue(panel.ensureCalled);
		assertEquals(0, panel.replaceCalls);
		assertEquals(List.of(new LogisticsBinding(priorNetwork)), panel.boundBindings);
		assertEquals(7, panel.bindingRevision);
		assertEquals(Optional.of(priorNetwork), panel.selectedNetwork);
		assertFalse(panel.aliasEditorOpened);
		assertFalse(panel.panelOpened);
		assertTrue(directory.find(refusedNetwork).isEmpty());
	}

	@Test
	void emptyToolOpensUnifiedNetworkPageWithoutClearingClusterBindings() {
		ItemStack tool = tool();
		FakeInteraction panel = FakeInteraction.panel(true, true, true);
		panel.boundBindings = List.of(new LogisticsBinding(NETWORK));

		assertEquals(InteractionResult.SUCCESS,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertEquals(List.of(new LogisticsBinding(NETWORK)), panel.boundBindings);
		assertEquals(FactoryPanelOpeningHint.LOGISTICS_NETWORK, panel.openingHint);
		assertEquals(Optional.empty(), panel.selectedNetwork);
		assertTrue(panel.panelOpened);
		assertFalse(panel.aliasEditorOpened);
	}

	@Test
	void configuredToolAppendsNetworksWithoutReplacingThePrimaryAndRepeatedClicksAreIdempotent() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		FakeInteraction target = FakeInteraction.panel(true, true, true);
		UUID primary = UUID.randomUUID();
		target.boundBindings = List.of(new LogisticsBinding(primary));
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, target));
		assertEquals(List.of(new LogisticsBinding(primary), new LogisticsBinding(NETWORK)), target.boundBindings);
		assertEquals(1, target.replaceCalls);
		assertEquals(InteractionResult.SUCCESS, LogisticsConfigurationToolItem.executeServer(tool, target));
		assertEquals(1, target.replaceCalls, "Adding an already-bound network must not bump the Cluster revision");
	}

	@Test
	void alreadyRegisteredNetworkSelectsUnifiedPageInsteadOfReopeningAliasEditor() {
		ItemStack tool = tool();
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		FakeInteraction panel = FakeInteraction.panel(true, true, true);

		assertEquals(InteractionResult.SUCCESS,
			LogisticsConfigurationToolItem.executeServer(tool, panel));
		assertTrue(panel.panelOpened);
		assertFalse(panel.aliasEditorOpened);
		assertEquals(Optional.of(NETWORK), panel.selectedNetwork);
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
	void itemModelDeliberatelyReferencesTheAbsentTextureSprite() throws Exception {
		String path = "assets/create_biotech/models/item/logistics_configuration_tool.json";
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
			String model = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(model.contains("create_biotech:item/logistics_configuration_tool"));
		}
		assertEquals(null, getClass().getClassLoader().getResource(
			"assets/create_biotech/textures/item/logistics_configuration_tool.png"));
	}

	@Test
	void panelOpeningContextCarriesHintAndOptionalServerSelection() {
		var selected = new FactoryPanelOpeningHint.OpeningContext(
			FactoryPanelOpeningHint.LOGISTICS_NETWORK, Optional.of(NETWORK));
		var empty = new FactoryPanelOpeningHint.OpeningContext(
			FactoryPanelOpeningHint.LOGISTICS_NETWORK, Optional.empty());
		assertEquals(FactoryPanelOpeningHint.LOGISTICS_NETWORK, selected.hint());
		assertEquals(Optional.of(NETWORK), selected.selectedNetwork());
		assertEquals(Optional.empty(), empty.selectedNetwork());
	}

	private static ItemStack tool() {
		return new ItemStack(new LogisticsConfigurationToolItem(
			new Item.Properties().stacksTo(1)));
	}

	private static LogisticsNetworkDirectory fullDirectory() {
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> entries = new LinkedHashMap<>();
		for (long ordinal = 1;
			ordinal <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES; ordinal++) {
			UUID network = new UUID(0, ordinal);
			entries.put(network, new LogisticsNetworkDirectoryEntry(network, ordinal,
				Optional.empty(), 0));
		}
		return LogisticsNetworkDirectory.fromState(new LogisticsNetworkDirectory.State(entries,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L), ignored -> {});
	}

	private static final class FakeInteraction
		implements LogisticsConfigurationToolItem.ServerInteraction {
		private final Optional<UUID> linkedNetwork;
		private final boolean mayInteract;
		private final boolean panel;
		private final boolean formed;
		private final boolean administration;
		private final boolean alreadyRegistered;
		private List<LogisticsBinding> boundBindings = List.of();
		private boolean ensureCalled;
		private int replaceCalls;
		private long bindingRevision;
		private LogisticsNetworkDirectory directory;
		private boolean aliasEditorOpened;
		private boolean aliasOpeningAccepted = true;
		private boolean panelOpened;
		private FactoryPanelOpeningHint openingHint = FactoryPanelOpeningHint.DEFAULT;
		private Optional<UUID> selectedNetwork = Optional.empty();

		private FakeInteraction(Optional<UUID> linkedNetwork, boolean mayInteract,
			boolean panel, boolean formed, boolean administration,
			boolean alreadyRegistered) {
			this.linkedNetwork = linkedNetwork;
			this.mayInteract = mayInteract;
			this.panel = panel;
			this.formed = formed;
			this.administration = administration;
			this.alreadyRegistered = alreadyRegistered;
		}

		static FakeInteraction linked(UUID network, boolean mayInteract) {
			return new FakeInteraction(Optional.of(network), mayInteract,
				false, false, false, false);
		}

		static FakeInteraction panel(boolean formed, boolean administration,
			boolean alreadyRegistered) {
			return new FakeInteraction(Optional.empty(), true, true, formed,
				administration, alreadyRegistered);
		}

		@Override public Optional<UUID> linkedNetwork() { return linkedNetwork; }
		@Override public boolean mayInteractWithLinkedNetwork() { return mayInteract; }
		@Override public boolean isBindingTarget() { return panel; }
		@Override public List<LogisticsBinding> currentBindings() { return boundBindings; }
		@Override public boolean hasFormedClusterAuthority() {
			return formed && administration;
		}
		@Override public ClusterBindingService.BindResult replaceBindings(
			List<LogisticsBinding> bindings) {
			replaceCalls++;
			if (!administration) return ClusterBindingService.BindResult.PERMISSION;
			boundBindings = List.copyOf(bindings);
			bindingRevision++;
			return ClusterBindingService.BindResult.OK;
		}
		@Override public LogisticsNetworkDirectory.AllocationResult ensureNetwork(UUID network) {
			ensureCalled = true;
			if (directory != null) return directory.tryEnsure(network);
			LogisticsNetworkDirectoryEntry entry = new LogisticsNetworkDirectoryEntry(network, 1,
				Optional.empty(), 0);
			return alreadyRegistered
				? LogisticsNetworkDirectory.AllocationResult.existing(entry)
				: LogisticsNetworkDirectory.AllocationResult.allocated(entry);
		}
		@Override public boolean openAliasEditor(ItemStack tool, UUID network) {
			aliasEditorOpened = aliasOpeningAccepted;
			return aliasOpeningAccepted;
		}
		@Override public void openPanel(FactoryPanelOpeningHint hint,
			Optional<UUID> selectedNetwork) {
			openingHint = hint;
			this.selectedNetwork = selectedNetwork;
			panelOpened = true;
		}
	}
}
