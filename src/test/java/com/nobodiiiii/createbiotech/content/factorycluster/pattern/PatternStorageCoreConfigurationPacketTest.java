package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.CommunicationFrequencyEditBox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PatternStorageCoreConfigurationPacketTest {
	private static final Path JAVA = Path.of("src/main/java/com/nobodiiiii/createbiotech");

	@Test
	void oldPlacedRoutingPacketAndEveryCallableMutationPathAreAbsent() throws Exception {
		Path removedPacket = JAVA.resolve(
			"content/factorycluster/pattern/PatternStorageCoreConfigurationPacket.java");
		assertFalse(Files.exists(removedPacket));

		String packets = Files.readString(JAVA.resolve("network/CBPackets.java"));
		String core = Files.readString(JAVA.resolve(
			"content/biocomputing/library/LibraryBlockEntity.java"));
		String block = Files.readString(JAVA.resolve(
			"content/biocomputing/block/LibraryCoreBlock.java"));
		String menu = Files.readString(JAVA.resolve(
			"content/biocomputing/application/recipe/menu/PatternStorageCoreMenu.java"));
		String screen = Files.readString(JAVA.resolve(
			"content/biocomputing/application/recipe/menu/PatternStorageCoreScreen.java"));
		assertFalse(packets.contains("PatternStorageCoreConfigurationPacket"));
		assertFalse(core.contains("configureRouting("));
		assertFalse(core.contains("captureRoutingSessionFence("));
		assertFalse(core.contains("routingSessionFence("));
		assertTrue(block.contains("host.useLibrary(player)"));
		assertTrue(core.contains("return openPatternBrowser(serverPlayer)"));
		assertFalse(block.contains("serverPlayer.openMenu(core"));
		assertFalse(menu.contains("configurationPacket("));
		// Browser search/ghost packets are reads, not a return of the removed routing editor.
		assertFalse(screen.contains("CommunicationFrequencyEditBox"));
		assertFalse(screen.contains("LibraryCorePlacementPacket"));
		assertFalse(screen.contains("configurationPacket("));
		assertTrue(screen.contains("PatternBrowserFilter.localized("));
		assertFalse(screen.contains("text(\"locked\")"));
	}
}
