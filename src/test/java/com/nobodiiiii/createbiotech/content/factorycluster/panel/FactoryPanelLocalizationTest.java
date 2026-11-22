package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class FactoryPanelLocalizationTest {
	private static final Path SCREEN_DIRECTORY = Path.of("src/main/java/com/nobodiiiii/createbiotech/"
		+ "content/factorycluster/panel/screen");
	private static final List<String> LANGUAGES = List.of("en_us", "zh_cn");

	@Test
	void panelUsesStableTranslationKeysForLabelsAndRuntimeStates() throws Exception {
		String source;
		try (var files = Files.list(SCREEN_DIRECTORY)) {
			source = files.filter(path -> path.toString().endsWith(".java"))
				.map(path -> { try { return Files.readString(path); }
					catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); } })
				.reduce("", String::concat);
		}
		assertFalse(source.contains("Component.literal("),
			"Panel display text must never bypass translation keys");
		List<String> required = List.of(
			"create_biotech.factory.panel.output",
			"create_biotech.factory.panel.delivery_address",
			"create_biotech.factory.panel.multiplier",
			"create_biotech.factory.panel.signal_position",
			"create_biotech.factory.panel.signal_side",
			"create_biotech.factory.panel.hint.direction",
			"create_biotech.factory.panel.signal_strength",
			"create_biotech.factory.panel.hint.signal_strength",
			"create_biotech.factory.panel.mode_summary",
			"create_biotech.factory.panel.goal_row",
			"create_biotech.factory.panel.process_row",
			"create_biotech.factory.panel.process_detail",
			"create_biotech.factory.panel.agent_row",
			"create_biotech.factory.process_kind.goal",
			"create_biotech.factory.process_kind.library_query",
			"create_biotech.factory.process_kind.speculative_expansion",
			"create_biotech.factory.process_kind.remote_listener",
			"create_biotech.factory.process_kind.agent",
			"create_biotech.factory.process_kind.priority_lease",
			"create_biotech.factory.process_kind.output_destination",
			"create_biotech.factory.panel.view.monitor",
			"create_biotech.factory.panel.view.tasks",
			"create_biotech.factory.panel.view.network",
			"create_biotech.factory.panel.view.communication",
			"create_biotech.factory.panel.view.logistics",
			"create_biotech.factory.panel.loading",
			"create_biotech.factory.panel.stale",
			"create_biotech.factory.panel.monitor.computer",
			"create_biotech.factory.panel.monitor.slot",
			"create_biotech.factory.panel.monitor.goal_node",
			"create_biotech.factory.panel.monitor.tree_summary",
			"create_biotech.factory.panel.monitor.assigned_slot",
			"create_biotech.factory.panel.tasks.remaining_wait",
			"create_biotech.factory.panel.communication.diagnostics",
			"create_biotech.factory.panel.communication.page.listeners",
			"create_biotech.factory.panel.new_task.unavailable.no_input",
			"create_biotech.factory.panel.new_task.unavailable.incomplete_agent",
			"create_biotech.factory.panel.tasks.new",
			"create_biotech.factory.panel.tasks.row",
			"create_biotech.factory.panel.new_task.draft",
			"create_biotech.factory.panel.new_task.submit",
			"create_biotech.factory.panel.communication.row",
			"create_biotech.factory.panel.logistics.network",
			"create_biotech.factory.panel.logistics.component",
			"create_biotech.factory.panel.logistics.rename",
			"create_biotech.factory.cluster_role.standard",
			"create_biotech.factory.cluster_role.agent",
			"create_biotech.factory.operational_state.online",
			"create_biotech.factory.operational_state.degraded",
			"create_biotech.factory.operational_state.offline",
			"create_biotech.factory.component_status.online",
			"create_biotech.factory.component_status.offline",
			"create_biotech.factory.component_status.conflict");
		for (String language : LANGUAGES) {
			Path path = Path.of("src/main/resources/assets/create_biotech/lang/" + language + ".json");
			String translations = Files.readString(path);
			for (String key : required)
				assertTrue(translations.contains("\"" + key + "\""),
					language + " is missing " + key);
		}
	}
}
