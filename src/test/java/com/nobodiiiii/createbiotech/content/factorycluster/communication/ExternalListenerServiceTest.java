package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;

class ExternalListenerServiceTest {
	private static final UUID COMPUTER = id(1);

	@Test
	void wanderingListenersAreUniqueBoundedAndConsumeSlots() {
		Scheduler scheduler = new Scheduler(2);
		ExternalListenerService service = service(scheduler);
		Computer computer = trader();

		assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(computer, 0,
			List.of(freq("A"), freq("B"))));
		assertEquals(2, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(ExternalListenerService.EditResult.TOO_MANY, service.replace(computer, 1,
			List.of(freq("A"), freq("B"), freq("C"))));
		assertEquals(ExternalListenerService.EditResult.DUPLICATE, service.replace(computer, 1,
			List.of(freq("A"), freq("A"))));
	}

	@Test
	void exhaustedOrdinarySlotsKeepDurableConfigurationAndProjectWaitProcess() {
		Scheduler scheduler = new Scheduler(1);
		ExternalListenerService service = service(scheduler);
		Computer computer = trader();

		assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(computer, 0,
			List.of(freq("A"), freq("B"))));
		assertEquals(List.of(ExternalListenerService.ListenerState.ACTIVE,
			ExternalListenerService.ListenerState.WAITING_PROCESS), service.projection(COMPUTER).stream()
			.map(ExternalListenerService.ListenerProjection::state).toList());
		assertEquals(WaitReason.PROCESS, service.projection(COMPUTER).get(1).waitReason().orElseThrow());
		assertEquals(List.of(freq("A"), freq("B")), computer.configuration().frequencies());
	}

	@Test
	void staleAndOrdinaryPersistentEditsAreRejectedWithoutClaimingSlots() {
		Scheduler scheduler = new Scheduler(2);
		ExternalListenerService service = service(scheduler);
		Computer trader = trader();
		Computer ordinary = new Computer(id(2), profile(ProductionProfession.LIBRARIAN));

		assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(trader, 0, List.of(freq("A"))));
		assertEquals(ExternalListenerService.EditResult.STALE, service.replace(trader, 0, List.of(freq("B"))));
		assertEquals(ExternalListenerService.EditResult.ORDINARY_COMPUTER, service.replace(ordinary, 0,
			List.of(freq("A"))));
		assertEquals(1, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(0, scheduler.occupancy(ordinary.id(), ProcessKind.RADIO_LISTENER));
	}

	@Test
	void removingAListenerReleasesItsSlotForOrdinaryWork() {
		Scheduler scheduler = new Scheduler(2);
		ExternalListenerService service = service(scheduler);
		Computer computer = trader();

		assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(computer, 0,
			List.of(freq("A"), freq("B"))));
		assertFalse(scheduler.tryScheduleOrdinary(computer.id()));
		assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(computer, 1,
			List.of(freq("A"))));
		assertTrue(scheduler.tryScheduleOrdinary(computer.id()));
	}

	@Test
	void durableConfigurationRejectsAListWithTheWrongElementType() {
		var raw = ExternalListenerConfiguration.empty().save();
		ListTag malformed = new ListTag();
		malformed.add(IntTag.valueOf(3));
		raw.put("Frequencies", malformed);

		assertTrue(ExternalListenerConfiguration.load(raw).isEmpty());
	}

	@Test
	void durableConfigurationRejectsOversizedNbtBeforeFrequencyTraversal() {
		var raw = ExternalListenerConfiguration.empty().save();
		ListTag oversized = new ListTag();
		oversized.add(net.minecraft.nbt.StringTag.valueOf("A"));
		oversized.add(net.minecraft.nbt.StringTag.valueOf("B"));
		oversized.add(net.minecraft.nbt.StringTag.valueOf("C"));
		raw.put("Frequencies", oversized);

		assertTrue(ExternalListenerConfiguration.load(raw).isEmpty());
	}

	@Test
	void publicationFailureProjectsRouteWaitReleasesTheOrdinarySlotAndDoesNotChurn() {
		Scheduler scheduler = new Scheduler(1);
		Computer computer = trader();
		ExternalListenerService service = new ExternalListenerService(scheduler,
			new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter(8, 32, 8),
			(host, frequency, reservation) -> { throw new IllegalStateException("topology changed"); },
			(host, frequency, authority) -> true);

		assertEquals(ExternalListenerService.EditResult.APPLIED,
			service.replace(computer, 0, List.of(freq("A"))));
		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(1, scheduler.reserveAttempts);
		service.reconcile(computer);
		assertEquals(1, scheduler.reserveAttempts,
			"unchanged publication prerequisites must not reserve and release every observation");
	}


	private static ExternalListenerService service(Scheduler scheduler) {
		var router = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter(8, 32, 8);
		java.util.Map<CommunicationFrequency,
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint> targets =
			new java.util.LinkedHashMap<>();
		for (String value : List.of("A", "B", "C")) {
			CommunicationFrequency frequency = freq(value);
			UUID targetCluster = UUID.nameUUIDFromBytes(("target:" + value)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			var target = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint(
				targetCluster, UUID.nameUUIDFromBytes(("target-member:" + value)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				new com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress(
					net.minecraft.world.level.Level.OVERWORLD, null,
					new net.minecraft.core.BlockPos(value.charAt(0), 64, 0)), 1, frequency, 1,
				java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
			targets.put(frequency, target);
		}
		return new ExternalListenerService(scheduler, router, (computer, frequency, reservation) -> {
			var target = targets.get(frequency);
			UUID sourceCluster = UUID.nameUUIDFromBytes(("source:" + frequency.value())
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			var source = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint(
				sourceCluster, UUID.nameUUIDFromBytes(("source-member:" + frequency.value())
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				new com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress(
					net.minecraft.world.level.Level.OVERWORLD, null,
					new net.minecraft.core.BlockPos(frequency.value().charAt(0), 64, 1)), 1, frequency, 1,
				java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
			return new ExternalListenerService.Publication(source, target,
				new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding(
					sourceCluster, UUID.nameUUIDFromBytes(("source-network:" + frequency.value())
						.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
					UUID.nameUUIDFromBytes(("target-network:" + frequency.value())
						.getBytes(java.nio.charset.StandardCharsets.UTF_8)), target.clusterId(), frequency,
					source.hop(), target.hop()));
		}, (computer, frequency, authority) -> true);
	}
	private static Computer trader() { return new Computer(COMPUTER, profile(ProductionProfession.WANDERING_TRADER)); }
	private static ProductionResourceProfile profile(ProductionProfession profession) {
		return ProductionResourceProfile.forProfession(profession, 1, 64).orElseThrow();
	}
	private static CommunicationFrequency freq(String value) { return CommunicationFrequency.of(value); }
	private static UUID id(long value) { return new UUID(0, value); }

	private static final class Computer implements ExternalListenerService.ListenerComputer {
		private final UUID id;
		private final ProductionResourceProfile profile;
		private ExternalListenerConfiguration configuration = ExternalListenerConfiguration.empty();
		private Computer(UUID id, ProductionResourceProfile profile) { this.id = id; this.profile = profile; }
		@Override public UUID id() { return id; }
		@Override public ProductionResourceProfile profile() { return profile; }
	@Override public boolean listenerPersistenceAuthorized() { return true; }
		@Override public ExternalListenerConfiguration configuration() { return configuration; }
		@Override public boolean compareAndSet(ExternalListenerConfiguration expected,
			ExternalListenerConfiguration next) {
			if (!configuration.equals(expected)) return false;
			configuration = next;
			return true;
		}
	}

	private static final class Scheduler implements ExternalListenerService.Scheduler {
		private final int capacity;
		private int reserveAttempts;
		private final java.util.Map<UUID, List<ExternalListenerService.Reservation>> listeners = new java.util.HashMap<>();
		private final java.util.Map<UUID, Integer> ordinary = new java.util.HashMap<>();
		private Scheduler(int capacity) { this.capacity = capacity; }
		@Override public Optional<ExternalListenerService.Reservation> reserve(UUID computerId,
			CommunicationFrequency frequency, int slotLimit) {
			reserveAttempts++;
			int used = listeners.getOrDefault(computerId, List.of()).size() + ordinary.getOrDefault(computerId, 0);
			if (used >= Math.min(capacity, slotLimit)) return Optional.empty();
			var reservation = new ExternalListenerService.Reservation(UUID.randomUUID(), computerId, used);
			listeners.computeIfAbsent(computerId, ignored -> new ArrayList<>()).add(reservation);
			return Optional.of(reservation);
		}
		@Override public void release(ExternalListenerService.Reservation reservation) {
			listeners.getOrDefault(reservation.computerId(), List.of()).remove(reservation);
		}
		@Override public int occupancy(UUID computerId, ProcessKind kind) {
			return kind == ProcessKind.RADIO_LISTENER ? listeners.getOrDefault(computerId, List.of()).size() : 0;
		}
		boolean tryScheduleOrdinary(UUID computerId) {
			int used = listeners.getOrDefault(computerId, List.of()).size() + ordinary.getOrDefault(computerId, 0);
			if (used >= capacity) return false;
			ordinary.merge(computerId, 1, Integer::sum);
			return true;
		}
	}
}
