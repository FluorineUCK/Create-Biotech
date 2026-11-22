package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ListenerEditorProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LocalCommunicationEditProjection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class CommunicationNetworkViewCommandsTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2);
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(new SpaceAddress(
		Level.OVERWORLD, null, BlockPos.ZERO), id(3), id(4), CLUSTER, EPOCH);

	@Test
	void localCapabilitiesExposePrimaryAndListenerCommandsWithResponseOwnedFences() {
		CommunicationNetworkProjection projection = projection(true);
		FactoryPanelCommunicationCommandPacket primary = CommunicationNetworkViewCommands
			.changePrimary(OPENING, projection, "new-primary").orElseThrow();
		FactoryPanelCommunicationCommandPacket listeners = CommunicationNetworkViewCommands
			.replaceListeners(OPENING, projection, "listener-0000000000000001", "a,b")
			.orElseThrow();

		assertEquals(12, primary.expectedGeneration());
		assertEquals(13, primary.expectedViewRevision());
		assertEquals(33, ((FactoryPanelCommunicationCommandPacket.ChangePrimary)
			primary.command()).expectedConfigurationRevision());
		var command = (FactoryPanelCommunicationCommandPacket.ReplaceExternalListeners)
			listeners.command();
		assertEquals(44, command.expectedConfigurationRevision());
		assertEquals(List.of(CommunicationFrequency.of("a"), CommunicationFrequency.of("b")),
			command.frequencies());
	}

	@Test
	void missingListenerCapabilityCannotProduceAListenerCommand() {
		assertTrue(CommunicationNetworkViewCommands.replaceListeners(OPENING, projection(false),
			"listener-0000000000000001", "a").isEmpty());
	}

	@Test
	void everyResponseAdvertisedListenerTargetIsReachable() {
		CommunicationNetworkProjection projection = projection(true);
		var packet = CommunicationNetworkViewCommands.replaceListeners(OPENING, projection,
			"listener-0000000000000002", "second").orElseThrow();
		var command = (FactoryPanelCommunicationCommandPacket.ReplaceExternalListeners)
			packet.command();
		assertEquals("listener-0000000000000002", command.target());
		assertEquals(45, command.expectedConfigurationRevision());
	}

	private static CommunicationNetworkProjection projection(boolean listenerEditing) {
		ClusterCardProjection local = ClusterCardProjection.cluster(CLUSTER,
			ClusterServiceRole.STANDARD, ClusterCardProjection.FormationState.FORMED,
			ClusterCardProjection.OperationalState.ONLINE, 0, 4, 0, 0,
			ClusterCardProjection.LinkType.ADJACENT, CommunicationFrequency.of("old"), List.of(),
			false, listenerEditing);
		ListenerEditorProjection editor = new ListenerEditorProjection(
			"listener-0000000000000001", List.of(CommunicationFrequency.of("old")), 44);
		ListenerEditorProjection second = new ListenerEditorProjection(
			"listener-0000000000000002", List.of(), 45);
		return new CommunicationNetworkProjection(CLUSTER, EPOCH, 12, 13,
			CommunicationNetworkProjection.Status.CURRENT, 0, 1, List.of(local), 4,
			Optional.of(new LocalCommunicationEditProjection(33, List.of(editor, second))));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
