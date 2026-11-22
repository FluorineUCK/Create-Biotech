package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class ScratchPlayerStartupCheckpointTest {
    private static final ModuleDefinition.Key SECOND_HAT = new ModuleDefinition.Key("startup_block_2", 1);

    @Test void aCompletedStartupScriptIsNotReissuedWhileTheOtherScriptResumesItsSecondsWait() throws Exception {
        var hardware = StockApplicationDriverDocuments.hardware();
        // Normal player save, Scratch Startup Hats, (291,64,1), screenshot 1556. No document or checkpoint was rewritten.
        try (var in = getClass().getResourceAsStream("/biocomputing/compatibility/scratch-player-startup-paused.nbt")) {
            assertNotNull(in);
            var pair = NbtIo.readCompressed(in, NbtAccounter.create(512 * 1024));
            var active = assertInstanceOf(DataValue.RecordValue.class, ValueNbtCodec.decode(pair.getCompound("Active"), hardware.archive()));
            assertEquals(integer(54), active.field("version"));
            var document = ProgramDocumentCodec.decode(active);
            var body = document.flow().orElseThrow();
            var owner = pair.getCompound("Owner");
            assertEquals(54, owner.getInt(FlowBlockNbtCodec.MARKER));
            var rawOwner = assertInstanceOf(DataValue.RecordValue.class, ValueNbtCodec.decode(owner.getCompound("Data"), hardware.archive()));
            var rawFlow = assertInstanceOf(DataValue.RecordValue.class, rawOwner.field("flow"));
            assertEquals(integer(56), rawFlow.field("version"));

            var restored = restore(owner, hardware);
            var snapshot = restored.flowSnapshot().orElseThrow();
            assertEquals(body.program(document.archive()), snapshot.program());
            assertEquals(body.initial(), snapshot.initialInputs());
            var hat = snapshot.program().steps().stream().filter(FlowProgram.Procedure.class::isInstance)
                .map(FlowProgram.Procedure.class::cast).filter(FlowProgram.Procedure::startup).findFirst().orElseThrow();
            assertEquals(SECOND_HAT, hat.key());
            assertTrue(snapshot.tasks().contexts().isEmpty(), "The second startup script has already retired");
            assertEquals(integer(1), snapshot.memory().cells().field("hits"));
            var pause = snapshot.state();
            assertEquals(FlowRuntime.Status.SLEEPING, pause.status());
            assertTrue(pause.paused());
            assertEquals(6, pause.work());
            assertEquals(3_564, pause.sleepTicks());

            restored.tick(hardware, true);
            assertEquals(snapshot, restored.flowSnapshot().orElseThrow());
            restored.step(hardware, true);
            var single = restored.flowSnapshot().orElseThrow();
            assertEquals(3_563, single.state().sleepTicks(), "Only one Minecraft tick leaves the sleeping continuation");
            assertEquals(pause.assembly(), single.state().assembly());
            assertEquals(pause.work(), single.state().work());
            assertTrue(single.state().paused());

            var reloaded = restore(restored.write(), hardware);
            assertEquals(single, reloaded.flowSnapshot().orElseThrow());
            reloaded.setPaused(false, hardware, true);
            for (int tick = 0; tick < 16; tick++) reloaded.tick(hardware, true);
            var continued = reloaded.flowSnapshot().orElseThrow();
            assertEquals(3_547, continued.state().sleepTicks());
            assertEquals(pause.work(), continued.state().work());
            assertEquals(snapshot.control().generation(), continued.control().generation());
            assertEquals(integer(1), continued.memory().cells().field("hits"), "Loading and continuing do not re-dispatch the second startup script");
            assertTrue(continued.tasks().contexts().isEmpty());

            reloaded.reset(hardware, true);
            var reset = reloaded.flowSnapshot().orElseThrow();
            assertEquals(integer(0), reset.memory().cells().field("hits"));
            assertEquals(snapshot.control().generation() + 1, reset.control().generation());
            reloaded.setPaused(false, hardware, true);
            boolean fullDurationSeen = false;
            for (int tick = 0; tick < 24; tick++) {
                reloaded.tick(hardware, true);
                var current = reloaded.flowSnapshot().orElseThrow();
                fullDurationSeen |= current.state().sleepTicks() == 3_600;
            }
            var restarted = reloaded.flowSnapshot().orElseThrow();
            assertTrue(fullDurationSeen, "Reset reevaluates the original 180-second block as 3,600 ticks, not 180 ticks");
            assertEquals(integer(1), restarted.memory().cells().field("hits"), "Only an explicit reset launches the second startup script again, once");
            assertTrue(restarted.tasks().contexts().isEmpty());
        }
    }

    private static ComputerBlockHost restore(Tag owner, ComputerHostLimits hardware) {
        var host = new ComputerBlockHost(() -> {});
        host.read(owner);
        host.tick(hardware, true);
        assertTrue(host.loaded(), () -> host.diagnostic().toString());
        return host;
    }

    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
