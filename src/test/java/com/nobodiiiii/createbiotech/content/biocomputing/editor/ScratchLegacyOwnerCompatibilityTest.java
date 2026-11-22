package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

/** The fixture was saved by the pre-integration block host, not by the codec under test. */
class ScratchLegacyOwnerCompatibilityTest {
    @Test
    void sleepingCustomBlockResumesTheOldCallWithoutReplayingItsPublicWrite() throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        try (var input = getClass().getResourceAsStream("/biocomputing/compatibility/scratch-owner-v52-sleeping-call.nbt")) {
            assertNotNull(input, "Missing pre-integration owner NBT");
            var raw = NbtIo.readCompressed(input, NbtAccounter.create(512 * 1024));
            assertEquals(52, raw.getInt(FlowBlockNbtCodec.MARKER));
            var payload = assertInstanceOf(DataValue.RecordValue.class,
                ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive()));
            var flow = assertInstanceOf(DataValue.RecordValue.class, payload.field("flow"));
            assertEquals(integer(54), flow.field("version"));

            var host = new ComputerBlockHost(() -> {});
            host.read(raw);
            host.tick(limits, true);
            var before = host.flowSnapshot().orElseThrow(() -> new AssertionError(host.diagnostic().toString()));
            var state = before.state();
            assertTrue(state.paused());
            assertEquals(SLEEPING, state.status());
            assertEquals(3, state.sleepTicks());
            assertEquals(23, state.work());
            var continuation = state.assembly().orElseThrow();
            assertEquals(31, continuation.pc());
            assertEquals(1, continuation.calls().orElseThrow().frames().size());
            assertEquals(integer(1), before.memory().cells().field("counter"));
            assertFalse(state.locals().fields().containsKey("answer"));

            // World ticks do not make a paused call run; explicitly step its remaining three active ticks.
            for (int tick = 0; tick < 4; tick++) host.tick(limits, true);
            assertEquals(before, host.flowSnapshot().orElseThrow());
            for (int remaining = 2; remaining >= 0; remaining--) {
                host.step(limits, true);
                var next = host.flowSnapshot().orElseThrow();
                assertEquals(remaining, next.state().sleepTicks());
                assertEquals(remaining == 0 ? READY : SLEEPING, next.state().status());
                assertEquals(state.work(), next.state().work(), "Countdown does not rerun the wait or its caller");
                assertEquals(integer(1), next.memory().cells().field("counter"));
                assertFalse(next.state().locals().fields().containsKey("answer"));
            }

            host.setPaused(false, limits, true);
            for (int tick = 0; tick < 18 && host.flowSnapshot().orElseThrow().state().status() != COMPLETE; tick++)
                host.tick(limits, true);
            var done = host.flowSnapshot().orElseThrow();
            assertEquals(COMPLETE, done.state().status(), () -> done.state().diagnostic().toString());
            assertEquals(state.flow(), done.state().flow(), "Do not replace the running program with a freshly installed one");
            assertEquals(integer(5), done.state().locals().field("answer"));
            assertEquals(integer(11), done.memory().cells().field("counter"), "The pre-wait +1 and post-call +10 occur exactly once");
            assertEquals(32, done.state().work(), "Continue on the old cycle schedule");
        }
    }

    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
