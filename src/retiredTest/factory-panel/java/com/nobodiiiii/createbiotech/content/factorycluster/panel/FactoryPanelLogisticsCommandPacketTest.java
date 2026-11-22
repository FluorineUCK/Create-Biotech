package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAliasService;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket.RenameAlias;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket.Result;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

class FactoryPanelLogisticsCommandPacketTest {
	private static final UUID PANEL = id(1), CLUSTER = id(2), EPOCH = id(3), SESSION = id(4);
	private static final UUID NETWORK = id(5), UNBOUND = id(6);
	private static final SpaceAddress ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(4, 5, 6));
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(ADDRESS, PANEL,
		SESSION, CLUSTER, EPOCH);
	private static final ClusterAuthority FORMED_AUTHORITY = new ClusterAuthority(
		ClusterMemberType.COMPUTER_COORDINATOR, id(7));
	private static final ClusterBinding BINDING = new ClusterBinding(CLUSTER, 7, FORMED_AUTHORITY,
		List.of(new LogisticsBinding(NETWORK)));

	@Test
	void aliasRenameUsesTheGlobalDirectoryAndExactAliasRevisionCas() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		LogisticsNetworkAliasService aliases = aliases(directory, (network, player) -> true);
		FactoryPanelLogisticsCommandPacket packet = packet(NETWORK, 0, Optional.of("主仓"));

		assertEquals(Result.APPLIED, packet.execute(authority(packet), BINDING, null, aliases));
		assertEquals(Result.STALE, packet.execute(authority(packet), BINDING, null, aliases));
		assertEquals(Optional.of("主仓"), directory.find(NETWORK).orElseThrow().customAlias());
		assertEquals(1, directory.find(NETWORK).orElseThrow().revision());
		FactoryPanelLogisticsCommandPacket semanticNoOp = packet(NETWORK, 1,
			Optional.of("  主仓  "));
		assertEquals(Result.STALE, semanticNoOp.execute(authority(semanticNoOp), BINDING, null,
			aliases));
		assertEquals(1, directory.find(NETWORK).orElseThrow().revision());

		ClusterAuthority otherCoordinator = new ClusterAuthority(
			ClusterMemberType.COMPUTER_COORDINATOR, id(90));
		ClusterBinding otherBinding = new ClusterBinding(id(91), 0, otherCoordinator,
			List.of(new LogisticsBinding(NETWORK)));
		LogisticsNetworkProjectionService otherClusterView = new LogisticsNetworkProjectionService(
			directory, new LogisticsAddressObserver(8),
			candidate -> candidate.equals(otherCoordinator) ? Optional.of(otherBinding) : Optional.empty(), 8);
		var globallyRefreshed = otherClusterView.project(otherCoordinator, Optional.of(NETWORK), 0, 8)
			.networks().getFirst();
		assertEquals(expectedLiteral("主仓"), globallyRefreshed.displayName());
		assertEquals(1, globallyRefreshed.revision());
	}

	@Test
	void administrationFailureAndUnboundOrUnformedPanelFailClosed() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.ensure(UNBOUND);
		FactoryPanelLogisticsCommandPacket permitted = packet(NETWORK, 0, Optional.of("主仓"));
		FactoryPanelLogisticsCommandPacket unbound = packet(UNBOUND, 0, Optional.of("旁路"));

		assertEquals(Result.FORBIDDEN, permitted.execute(authority(permitted), BINDING, null,
			aliases(directory, (network, player) -> false)));
		assertEquals(Result.NETWORK_NOT_BOUND, unbound.execute(authority(unbound), BINDING, null,
			aliases(directory, (network, player) -> true)));
		ClusterBinding wrongAuthority = new ClusterBinding(CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.PANEL, PANEL),
			List.of(new LogisticsBinding(NETWORK)));
		assertEquals(Result.AUTHORITY_MISMATCH, permitted.execute(authority(permitted), wrongAuthority,
			null, aliases(directory, (network, player) -> true)));
		assertTrue(directory.find(NETWORK).orElseThrow().customAlias().isEmpty());
	}

	@Test
	void packetCodecCarriesOnlyTheAliasRenameCommand() {
		FactoryPanelLogisticsCommandPacket expected = packet(NETWORK, 17, Optional.empty());
		RegistryFriendlyByteBuf bytes = buffer();

		expected.write(bytes);

		assertEquals(expected, decode(bytes));
		assertEquals(new RenameAlias(17, Optional.empty()), expected.command());
	}

	private static FactoryPanelLogisticsCommandPacket packet(UUID network, long expectedRevision,
		Optional<String> alias) {
		return new FactoryPanelLogisticsCommandPacket(OPENING, network,
			new RenameAlias(expectedRevision, alias));
	}

	private static FactoryPanelAuthorityValidator.ViewValidation authority(
		FactoryPanelLogisticsCommandPacket packet) {
		FactoryPanelViewRequestPacket request = new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.NETWORK_LOGISTICS, OptionalLong.empty(), 0, 0,
			Optional.of(packet.logisticsNetworkId()));
		assertEquals(request.authorityRequest(), packet.authorityRequest());
		return FactoryPanelAuthorityValidator.validateView(packet.authorityRequest(),
			new FactoryPanelAuthorityValidator.ViewFacts(ADDRESS, PANEL, SESSION, CLUSTER, CLUSTER,
				EPOCH, true, true, true, true, true, true, true, Optional.empty(), 24))
			.orElseThrow();
	}

	private static LogisticsNetworkAliasService aliases(LogisticsNetworkDirectory directory,
		BiPredicate<UUID, Player> mayAdministrate) {
		try {
			Constructor<LogisticsNetworkAliasService> constructor =
				LogisticsNetworkAliasService.class.getDeclaredConstructor(
					LogisticsNetworkDirectory.class, BiPredicate.class);
			constructor.setAccessible(true);
			return constructor.newInstance(directory, mayAdministrate);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static RegistryFriendlyByteBuf buffer() {
		try {
			Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
			Object byteBuf = Class.forName("io.netty.buffer.Unpooled")
				.getMethod("buffer").invoke(null);
			return (RegistryFriendlyByteBuf) RegistryFriendlyByteBuf.class
				.getConstructor(byteBufType, RegistryAccess.class)
				.newInstance(byteBuf, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static FactoryPanelLogisticsCommandPacket decode(Object bytes) {
		try {
			return FactoryPanelLogisticsCommandPacket.class
				.getConstructor(RegistryFriendlyByteBuf.class).newInstance(bytes);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static Object expectedLiteral(String text) {
		try {
			return Class.forName("net.minecraft.network.chat.Component")
				.getMethod("literal", String.class).invoke(null, text);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
