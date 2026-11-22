package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleOutputProtocolTest {
    @Test void sharedBudgetRefusesWithoutAdmissionAndRecoveredAdmissionNeverReplays() {
        var owner = UUID.randomUUID();
        var state = ParticleOutputState.grant(ParticleOutputState.closed(UUID.randomUUID(), UUID.randomUUID()),
            Optional.of(owner), UUID.randomUUID());
        var kind = ResourceLocation.withDefaultNamespace("smoke");
        var limits = new ParticleOutputBurst.Limits(8, 3, 1, Set.of(kind));
        var particle = new SimpleParticleType(false);
        var address = new DataValue.RecordValue(Map.of("x", integer(1), "y", integer(2), "z", integer(3)));
        var frame = new DataValue.RecordValue(Map.of("kind", text("particle_output_command"), "version", integer(1), "target", address,
            "request", text("p:1"), "operation", text("emit"), "arguments", new DataValue.RecordValue(Map.of(
            "device", text(state.device().toString()), "session", text(state.session().toString()), "revision", integer(state.revision()),
            "burst", ParticleOutputBurstTest.request(kind, new DataValue.RecordValue(Map.of()))))));
        var budget = new ParticleOutputBudget();
        assertTrue(budget.consume(40, 4, 6));
        assertFalse(budget.consume(40, 3, 6));
        var refused = ParticleOutputProtocol.plan(state, owner, address, frame, limits, budget.remaining(40, 6), id -> Optional.of(particle)).orElseThrow();
        assertEquals(text("particle_budget_exhausted"), refused.reply().field("code"));
        assertSame(state, refused.next());
        assertTrue(refused.burst().isEmpty());

        var accepted = ParticleOutputProtocol.plan(state, owner, address, frame, limits, budget.remaining(41, 6), id -> Optional.of(particle)).orElseThrow();
        assertEquals(text("accepted"), accepted.reply().field("code"));
        assertEquals(3, accepted.burst().orElseThrow().count());
        assertTrue(budget.consume(41, 3, 6));
        var disk = ParticleOutputState.save(accepted.next());
        var restored = ParticleOutputState.load(disk, UUID.randomUUID());
        assertEquals("interrupted", restored.last());
        var stale = ParticleOutputProtocol.plan(restored, owner, address, frame, limits, 6, id -> Optional.of(particle)).orElseThrow();
        assertEquals(text("stale_session"), stale.reply().field("code"));
        assertTrue(stale.burst().isEmpty());
        var denied = ParticleOutputProtocol.plan(state, UUID.randomUUID(), address, frame, limits, 6, id -> fail("Unauthorized frame must not decode")).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
    }

    private static DataValue.IntegerValue integer(long x) { return new DataValue.IntegerValue(x); }
    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
}
