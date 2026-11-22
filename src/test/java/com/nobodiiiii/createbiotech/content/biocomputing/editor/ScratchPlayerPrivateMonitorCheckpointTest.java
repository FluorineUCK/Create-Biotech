package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class ScratchPlayerPrivateMonitorCheckpointTest {
    @Test
    void aSavedPrivateListRemainsVisibleDuringTheOriginalWaitAndHidesWithoutChangingData() throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        // Scratch Private List (-2633647,-58,-11734061), saved through the client at frame 1986.
        // Active and Owner are the two original CompoundTags, not archives rebuilt for the current codec.
        try (var input = getClass().getResourceAsStream("/biocomputing/compatibility/scratch-player-private-list-monitored.nbt")) {
            assertNotNull(input);
            var savedPair = NbtIo.readCompressed(input, NbtAccounter.create(512 * 1024));
            var active = assertInstanceOf(DataValue.RecordValue.class, ValueNbtCodec.decode(savedPair.getCompound("Active"), limits.archive()));
            assertEquals(integer(52), active.field("version"));
            var document = ProgramDocumentCodec.decode(active);
            var owner = savedPair.getCompound("Owner");
            assertEquals(55, owner.getInt(FlowBlockNbtCodec.MARKER));
            var rawOwner = assertInstanceOf(DataValue.RecordValue.class, ValueNbtCodec.decode(owner.getCompound("Data"), limits.archive()));
            var rawFlow = assertInstanceOf(DataValue.RecordValue.class, rawOwner.field("flow"));
            assertEquals(integer(57), rawFlow.field("version"));

            var host = restore(owner, limits);
            var saved = host.flowSnapshot().orElseThrow();
            var body = document.flow().orElseThrow();
            assertEquals(body.program(document.archive()), saved.program());
            assertEquals(body.initial(), saved.initialInputs());
            assertEquals(FlowRuntime.Status.SLEEPING, saved.state().status());
            assertTrue(saved.state().paused());
            assertEquals(558, saved.state().sleepTicks());
            assertEquals(22, saved.state().work());
            var on = new DataValue.ListValue(List.of(new DataValue.TextValue("items")));
            var off = new DataValue.ListValue(List.of());
            var items = new DataValue.ListValue(List.of(integer(7)));
            assertEquals(on, view(host).field("private_monitors"));
            assertEquals(items, locals(host).field("items"));
            assertEquals(new DataValue.ListValue(List.of(integer(42))), saved.memory().cells().field("stock"));
            assertEquals(off, view(host).field("program_monitors"), "Private observation must not select the public list");

            host.tick(limits, true);
            assertEquals(saved, host.flowSnapshot().orElseThrow());
            host.step(limits, true);
            var single = host.flowSnapshot().orElseThrow();
            assertEquals(557, single.state().sleepTicks(), "A paused step consumes only one tick of the original wait");
            assertEquals(22, single.state().work());
            assertEquals(on, view(host).field("private_monitors"));

            var reloaded = restore(host.write(), limits);
            assertEquals(single, reloaded.flowSnapshot().orElseThrow());
            assertEquals(on, view(reloaded).field("private_monitors"));
            reloaded.setPaused(false, limits, true);
            for (int tick = 0; tick < 580 && reloaded.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; tick++) {
                reloaded.tick(limits, true);
            }
            var completed = reloaded.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, completed.state().status());
            assertEquals(24, completed.state().work(), "Only the hide instruction and completion remain");
            assertEquals(saved.control().generation(), completed.control().generation());
            assertEquals(off, view(reloaded).field("private_monitors"));
            assertEquals(items, locals(reloaded).field("items"), "The script hides the monitor, not its value");
            assertEquals(saved.memory(), completed.memory(), "Continuing must not re-run the list writes");
            assertTrue(completed.privateMonitors().watches().isEmpty());
        }
    }

    private static ComputerBlockHost restore(Tag owner, ComputerHostLimits limits) {
        var host = new ComputerBlockHost(() -> {});
        host.read(owner);
        host.tick(limits, true);
        assertTrue(host.loaded(), () -> host.diagnostic().toString());
        return host;
    }

    private static DataValue.RecordValue view(ComputerBlockHost host) {
        return ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty());
    }

    private static DataValue.RecordValue locals(ComputerBlockHost host) {
        return assertInstanceOf(DataValue.RecordValue.class, view(host).field("locals"));
    }

    private static DataValue.IntegerValue integer(long value) {
        return new DataValue.IntegerValue(value);
    }
}
