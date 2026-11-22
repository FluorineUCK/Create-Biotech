package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Executes the production client adapter, substituting only world observations and packet delivery. */
class FactoryGoggleClientTest {
	@BeforeAll
	static void bootstrap() throws Exception {
		AdvancedGogglePacketTest.bootstrap();
	}

	@Test
	void renderFramesInvalidateCachedAOnNonNullBWithoutAdvancingTick() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		var first = fixture.sent.getFirst();
		fixture.client.receive(FactoryGoggleProjectionServiceTest.projection(fixture.a), first.requestId());
		assertFalse(fixture.render(fixture.a).isEmpty());

		fixture.pointAt(fixture.b);
		assertTrue(fixture.render(fixture.b).isEmpty());
		fixture.pointAt(fixture.a);
		assertTrue(fixture.render(fixture.a).isEmpty(), "returning to A before another tick must not revive its cached rows");
		assertEquals(1, fixture.sent.size(), "rendering must never dispatch a request");
	}

	@Test
	void responseObservationOfBRetiresPendingAWithoutAdvancingTick() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		var first = fixture.sent.getFirst();
		var oldA = FactoryGoggleProjectionServiceTest.projection(fixture.a);

		fixture.pointAt(fixture.b);
		fixture.client.receive(oldA, first.requestId());
		fixture.pointAt(fixture.a);
		fixture.client.receive(oldA, first.requestId());
		assertTrue(fixture.render(fixture.a).isEmpty(), "a response observed at B must retire pending A even when its payload is rejected");
		assertEquals(1, fixture.sent.size(), "receiving must never dispatch a request");
	}

	@Test
	void renderObservationOfBAlsoRetiresPendingAWithoutAdvancingTick() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		var first = fixture.sent.getFirst();
		fixture.pointAt(fixture.b);
		fixture.render(fixture.b);
		fixture.pointAt(fixture.a);
		fixture.client.receive(FactoryGoggleProjectionServiceTest.projection(fixture.a), first.requestId());
		assertTrue(fixture.render(fixture.a).isEmpty());
		assertEquals(1, fixture.sent.size());

		fixture.client.clientTick();
		assertEquals(2, fixture.sent.size());
		assertNotEquals(first.requestId(), fixture.sent.getLast().requestId());
		fixture.client.receive(FactoryGoggleProjectionServiceTest.projection(fixture.a), fixture.sent.getLast().requestId());
		assertFalse(fixture.render(fixture.a).isEmpty(), "the next tick may request fresh A after the observed target change");
	}

	@Test
	void worldChangeWithIdenticalTargetClearsCacheAndPendingResponsesBeforeTick() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		var first = fixture.sent.getFirst();
		var response = FactoryGoggleProjectionServiceTest.projection(fixture.a);
		fixture.client.receive(response, first.requestId());
		assertFalse(fixture.render(fixture.a).isEmpty());
		fixture.observation.set(new FactoryGoggleClient.Observation(new Object(), fixture.a));
		assertTrue(fixture.render(fixture.a).isEmpty());
		fixture.client.receive(response, first.requestId());
		assertTrue(fixture.render(fixture.a).isEmpty());
		assertEquals(1, fixture.sent.size());
	}

	@Test
	void repeatedRenderAndResponseObservationsDoNotConsumeOrAccelerateRequestCadence() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		var first = fixture.sent.getFirst();
		for (int i = 0; i < 10; i++) {
			fixture.render(fixture.a);
			fixture.client.receive(FactoryGoggleProjectionServiceTest.projection(fixture.a), first.requestId());
		}
		assertEquals(1, fixture.sent.size());
		for (int i = 0; i < 4; i++) fixture.client.clientTick();
		assertEquals(1, fixture.sent.size());
		fixture.client.clientTick();
		assertEquals(2, fixture.sent.size());
	}

	@Test
	void advancedPositionUsesWorldCoordinatesRatherThanRenderingTheJavaRecord() {
		var fixture = new Fixture();
		fixture.client.clientTick();
		fixture.client.receive(FactoryGoggleProjectionServiceTest.projection(fixture.a), fixture.sent.getFirst().requestId());
		var text = fixture.render(fixture.a).stream().map(Component::getString).toList();
		assertTrue(text.stream().anyMatch(line -> line.contains("minecraft:overworld")));
		assertTrue(text.stream().anyMatch(line -> line.contains("0, 0, 0")));
		assertFalse(text.stream().anyMatch(line -> line.contains("ResourceKey[") || line.contains("SpaceAddress[")));
	}

	private static final class Fixture {
		final Object world = new Object();
		final FactoryGoggleTarget a = FactoryGoggleProjectionServiceTest.target(1);
		final FactoryGoggleTarget b = FactoryGoggleProjectionServiceTest.target(2);
		final AtomicReference<FactoryGoggleClient.Observation> observation = new AtomicReference<>(new FactoryGoggleClient.Observation(world, a));
		final List<AdvancedGoggleRequestPacket> sent = new ArrayList<>();
		final FactoryGoggleClient client = new FactoryGoggleClient(observation::get, sent::add, () -> 5);

		void pointAt(FactoryGoggleTarget target) {
			observation.set(new FactoryGoggleClient.Observation(world, target));
		}

		List<Component> render(FactoryGoggleTarget target) {
			var metadata = new FactoryGoggleMetadata();
			var tag = new CompoundTag();
			tag.put(FactoryGoggleTarget.ROOT, target.save());
			metadata.read(tag);
			var owner = new FactoryGoggleInformation() {
				@Override public FactoryGoggleState normalGoggles() { return FactoryGoggleState.EMPTY; }
				@Override public FactoryGoggleMetadata advancedGoggleMetadata() { return metadata; }
			};
			var tooltip = new ArrayList<Component>();
			client.appendTooltip(owner, tooltip);
			return tooltip;
		}
	}
}
