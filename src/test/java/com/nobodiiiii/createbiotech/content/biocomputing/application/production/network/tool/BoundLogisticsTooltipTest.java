package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;

import net.minecraft.network.chat.Component;

class BoundLogisticsTooltipTest {
	private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SECOND = UUID.fromString("10000000-0000-0000-0000-000000000002");

	@Test
	void normalAndShiftTooltipsUseAliasesWithoutUuids() {
		BoundLogisticsTooltip tooltip = new BoundLogisticsTooltip(directory()::find);
		List<Component> normal = tooltip.build(List.of(FIRST, SECOND), false, true);
		assertEquals("gold", color(normal.getFirst()).toString());
		assertTrue(translationKey(normal.getFirst()).contains("configured"));
		assertTrue(representation(normal).contains("主仓") && representation(normal).contains(" +1"), representation(normal));
		assertFalse(representation(normal).contains(FIRST.toString()));
		List<Component> shift = tooltip.build(List.of(FIRST, SECOND), true, true);
		assertTrue(representation(shift).contains("•") && representation(shift).contains("主仓"));
		assertTrue(translationKey(shift.getLast()).contains("panel_hint"));
	}

	@Test
	void unboundAndUnavailableBindingsUseLocalizedNonIdentityStates() {
		BoundLogisticsTooltip tooltip = new BoundLogisticsTooltip(ignored -> Optional.empty());
		List<Component> unbound = tooltip.build(List.of(), false, false);
		assertTrue(translationKey(unbound.getFirst()).contains("unbound"));
		assertEquals("gray", color(unbound.getFirst()).toString());
		List<Component> unavailable = tooltip.build(List.of(FIRST), false, false);
		assertTrue(translationKey(unavailable.getFirst()).contains("synchronizing"));
		assertFalse(representation(unavailable).contains(FIRST.toString()));
	}

	@Test
	void defaultDirectoryAliasAndToolHintRemainReadableAndDistinct() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(FIRST);
		BoundLogisticsTooltip tooltip = new BoundLogisticsTooltip(directory::find);
		assertTrue(representation(tooltip.build(List.of(FIRST), false, false))
			.contains("create_biotech.factory_cluster.logistics.default_name"));
		List<Component> shift = tooltip.build(List.of(FIRST), true, false);
		assertTrue(translationKey(shift.getLast()).contains("tool_hint"));
		assertFalse(translationKey(shift.getLast()).contains("panel_hint"));
	}

	private static LogisticsNetworkDirectory directory() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(FIRST); directory.ensure(SECOND);
		directory.compareAndSetAlias(FIRST, 0, Optional.of("主仓"));
		directory.compareAndSetAlias(SECOND, 0, Optional.of("次仓"));
		return directory;
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

	private static Object color(Component component) {
		try {
			Object raw = component;
			Object style = raw.getClass().getMethod("getStyle").invoke(raw);
			return style.getClass().getMethod("getColor").invoke(style);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String translationKey(Component component) {
		try {
			Object raw = component;
			return String.valueOf(raw.getClass().getMethod("getContents").invoke(raw));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
