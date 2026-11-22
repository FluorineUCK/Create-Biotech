package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsConfigurationToolItem;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsToolData;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

class BoundLogisticsTooltipDataTest {
	private static final UUID NETWORK = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000002");

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList(); net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true); bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}

	@Test
	void unrelatedOrUntunedInventoryDoesNotOpenGlobalStorage() {
		BoundLogisticsTooltipData.refreshEligible(stack(), () -> { throw new AssertionError("ordinary inventory consulted storage"); });
		ItemStack tool = new ItemStack(new LogisticsConfigurationToolItem(new Item.Properties()));
		BoundLogisticsTooltipData.refreshEligible(tool, () -> { throw new AssertionError("untuned tool consulted storage"); });
	}

	@Test
	void serverSnapshotLetsClientTooltipUseAliasesWithoutDirectoryAccess() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK); directory.compareAndSetAlias(NETWORK, 0, Optional.of("主仓"));
		ItemStack stack = stack();
		assertTrue(BoundLogisticsTooltipData.refresh(stack, List.of(NETWORK), directory));
		String lines = representation(BoundLogisticsTooltip.fromStack(stack).build(List.of(NETWORK), false, true));
		assertTrue(lines.contains("主仓"));
		assertFalse(lines.contains(NETWORK.toString()));
	}

	@Test
	void refreshOnlyWritesChangedDirectoryProjectionAndRejectsMismatches() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK); directory.compareAndSetAlias(NETWORK, 0, Optional.of("主仓"));
		ItemStack stack = stack();
		assertTrue(BoundLogisticsTooltipData.refresh(stack, List.of(NETWORK), directory));
		int originalHash = ItemStack.hashItemAndComponents(stack);
		assertFalse(BoundLogisticsTooltipData.refresh(stack, List.of(NETWORK), directory));
		assertTrue(originalHash == ItemStack.hashItemAndComponents(stack));
		directory.compareAndSetAlias(NETWORK, 1, Optional.of("新主仓"));
		assertTrue(BoundLogisticsTooltipData.refresh(stack, List.of(NETWORK), directory));
		assertTrue(representation(BoundLogisticsTooltip.fromStack(stack).build(List.of(NETWORK), false, true))
			.contains("新主仓"));
		assertTrue(representation(BoundLogisticsTooltip.fromStack(stack).build(List.of(OTHER), false, true)).contains("synchronizing"));
		assertTrue(BoundLogisticsTooltipData.refresh(stack, List.of(), directory));
		assertTrue(BoundLogisticsTooltipData.read(stack).isEmpty());
	}

	@Test
	void staleOrderedProjectionNeverRendersASubsetAlias() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.ensure(OTHER);
		directory.compareAndSetAlias(NETWORK, 0, Optional.of("甲"));
		directory.compareAndSetAlias(OTHER, 0, Optional.of("乙"));
		ItemStack stack = stack();
		assertTrue(BoundLogisticsTooltipData.refresh(stack, List.of(NETWORK, OTHER), directory));

		String firstOnly = representation(BoundLogisticsTooltip.fromStack(stack)
			.build(List.of(NETWORK), false, true));
		String secondOnly = representation(BoundLogisticsTooltip.fromStack(stack)
			.build(List.of(OTHER), false, true));
		assertTrue(firstOnly.contains("synchronizing"));
		assertFalse(firstOnly.contains("甲"));
		assertTrue(secondOnly.contains("synchronizing"));
		assertFalse(secondOnly.contains("乙"));
	}

	@Test
	void visibleContainerToolRefreshesRenamedAliasWithoutAStackTick() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.compareAndSetAlias(NETWORK, 0, Optional.of("旧名"));
		ItemStack tool = new ItemStack(new LogisticsConfigurationToolItem(
			new Item.Properties().stacksTo(1)));
		LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(NETWORK)));
		BoundLogisticsTooltipData.refreshVisibleStacks(ItemStack.EMPTY, List.of(tool), directory);
		assertTrue(representation(BoundLogisticsTooltip.fromStack(tool).build(List.of(NETWORK), false, false))
			.contains("旧名"));

		directory.compareAndSetAlias(NETWORK, 1, Optional.of("新名"));
		BoundLogisticsTooltipData.refreshVisibleStacks(ItemStack.EMPTY, List.of(tool), directory);
		assertTrue(representation(BoundLogisticsTooltip.fromStack(tool).build(List.of(NETWORK), false, false))
			.contains("新名"));
		int hash = ItemStack.hashItemAndComponents(tool);
		BoundLogisticsTooltipData.refreshVisibleStacks(ItemStack.EMPTY, List.of(tool), directory);
		assertTrue(hash == ItemStack.hashItemAndComponents(tool));
	}

	private static String representation(Object component) {
		StringBuilder result = new StringBuilder(String.valueOf(component));
		try {
			Object siblings = component.getClass().getMethod("getSiblings").invoke(component);
			for (Object sibling : (List<?>) siblings) result.append(representation(sibling));
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
		return result.toString();
	}
	private static String representation(List<Component> components) {
		StringBuilder result = new StringBuilder();
		for (Object component : components) result.append(representation(component));
		return result.toString();
	}
	private static ItemStack stack() { return new ItemStack(new Item(new Item.Properties())); }

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}
}
