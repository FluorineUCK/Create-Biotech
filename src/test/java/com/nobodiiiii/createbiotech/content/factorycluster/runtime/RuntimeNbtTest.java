package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import net.minecraft.nbt.*;

class RuntimeNbtTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2);
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 1_000, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);

	@Test void currentOrchestrationRuntimeRoundTripsExactly() {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 2, 0).orElseThrow();
		ClusterEngineState state = new ClusterEngine().advance(ClusterEngineState.empty(CLUSTER, EPOCH),
			List.of(new WorldFact.MemberLoadState(CLUSTER, EPOCH, 1, id(3), profile, true, 4, 5)), CONFIG, Long.MAX_VALUE).state();
		CompoundTag encoded = RuntimeNbt.save(state);
		assertEquals(1, encoded.getInt("Version"));
		assertEquals(state, RuntimeNbt.load(encoded).orElseThrow());
	}

	@Test void uncertainIntentDispositionIsPartOfTheExactCurrentSchema() {
		ClusterEngineState empty = ClusterEngineState.empty(CLUSTER, EPOCH);
		ProductionEffect intent = new ProductionEffect.RadioSend(id(30), CLUSTER, 1,
			new ProductionEffect.TargetFence(EPOCH, id(3), 0, 0), id(31), "payload");
		ClusterEngineState state = new ClusterEngineState(CLUSTER, EPOCH, 1, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(), empty.members(),
			Map.of(intent.intentId(), intent), Set.of(intent.intentId()), 0, null);
		CompoundTag encoded = RuntimeNbt.save(state);
		assertTrue(encoded.contains("UncertainIntents", Tag.TAG_LIST));
		assertEquals(state, RuntimeNbt.load(encoded).orElseThrow());
	}

	@Test void decoderAcceptsOnlyExactCurrentSchemaAndFailsClosedOnMalformedBounds() {
		CompoundTag encoded = RuntimeNbt.save(ClusterEngineState.empty(CLUSTER, EPOCH));
		encoded.putString("Legacy", "not retained");
		assertTrue(RuntimeNbt.load(encoded).isEmpty());
		CompoundTag oversized = RuntimeNbt.save(ClusterEngineState.empty(CLUSTER, EPOCH));
		ListTag members = oversized.getList("Members", CompoundTag.TAG_COMPOUND);
		for (int index = 0; index <= RuntimeNbt.MAX_MEMBERS; index++) members.add(new CompoundTag());
		assertTrue(RuntimeNbt.load(oversized).isEmpty());
	}

	@Test void decoderRejectsCursorsOutsideTheirCurrentCollections() {
		CompoundTag malformed = RuntimeNbt.save(ClusterEngineState.empty(CLUSTER, EPOCH));
		malformed.putInt("MemberCursor", 1);
		assertTrue(RuntimeNbt.load(malformed).isEmpty());
	}

	@Test void decoderRejectsNonCanonicalMemberOrderThatWouldChangeRoundRobinMeaning() {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		ClusterEngine engine = new ClusterEngine();
		ClusterEngineState state = engine.advance(ClusterEngineState.empty(CLUSTER, EPOCH), List.of(
			new WorldFact.MemberLoadState(CLUSTER, EPOCH, 1, id(3), profile, true, 0, 0)), CONFIG, Long.MAX_VALUE).state();
		state = engine.advance(state, List.of(
			new WorldFact.MemberLoadState(CLUSTER, EPOCH, 1, id(4), profile, true, 0, 0)), CONFIG, Long.MAX_VALUE).state();
		CompoundTag malformed = RuntimeNbt.save(state);
		ListTag members = malformed.getList("Members", CompoundTag.TAG_COMPOUND);
		members.add(members.remove(0));
		assertTrue(RuntimeNbt.load(malformed).isEmpty());
	}
	private static UUID id(long value) { return new UUID(0, value); }
}
