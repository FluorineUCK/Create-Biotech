package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLibraryFile;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerFileRamHostTest {
    @Test void stagesAnOldWireCompatibleTransferWithoutResetOrRetiringTheSource() throws Exception {
        ComputerBehaviourTest.bootstrap();
        var block = new ComputerBehaviourTest.Harness();
        var limits = block.selection.limits();
        block.selection = new HardwareSelection(HardwareSelection.Status.READY, block.selection.resident(),
            block.selection.profile(), limits.withMemoryUnits(32768));
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var program = new FlowProgram(Map.of(), List.of());
        computer.installFlow(program, new DataValue.RecordValue(Map.of()));
        computer.setPaused(true);
        var owner = UUID.randomUUID();
        byte[] source;
        try (var file = getClass().getResourceAsStream("/biocomputing/speaker-test-tone.mp3")) {
            assertNotNull(file);
            source = file.readAllBytes();
        }
        String name = "示例🎵.mp3";
        var stage = computer.reserveFileRam(owner, name, source.length).orElseThrow();
        assertTrue(stage.append(0, source));
        assertTrue(stage.seal(ignored -> true));
        var staged = computer.stagedFile(owner).orElseThrow();
        var before = computer.flowSnapshot().orElseThrow();
        assertTrue(computer.installedProgram().orElseThrow().paused());
        assertTrue(computer.prepareStagedFileTransfer(UUID.randomUUID(), staged.id()).isEmpty());

        var wire = SpeakerMediaLibraryFile.encode(name, source, 65536, limits.archive());
        var transfer = computer.prepareStagedFileTransfer(owner, staged.id()).orElseThrow();
        assertEquals(wire.length, transfer.readyBytes());
        for (int start = 0; start < wire.length; start += 64) {
            int length = Math.min(64, wire.length - start);
            assertArrayEquals(Arrays.copyOfRange(wire, start, start + length), transfer.slice(start, length));
        }
        assertTrue(computer.prepareStagedFileTransfer(owner, staged.id()).isEmpty());
        transfer.abort();
        block.selection = new HardwareSelection(HardwareSelection.Status.READY, block.selection.resident(),
            block.selection.profile(), limits.withMemoryUnits(22000));
        assertEquals("media_ram_unavailable", assertThrows(IllegalArgumentException.class,
            () -> computer.prepareStagedFileTransfer(owner, staged.id())).getMessage());
        assertEquals(staged, computer.stagedFile(owner).orElseThrow());
        assertEquals(before, computer.flowSnapshot().orElseThrow());
        block.selection = new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(),
            block.selection.profile(), limits.withMemoryUnits(32768));
        var retry = computer.prepareStagedFileTransfer(owner, staged.id()).orElseThrow();
        assertEquals(wire.length, retry.readyBytes());
        retry.abort();
    }

    @Test void fileAndRunningProgramFitOneRamBudgetWithoutRewritingTheProgram() {
        var limits = new ComputerHostLimits(new ResourceLimits(256, 1024, 8), new ArchiveLimits(4096, 65536, 262144));
        var program = new FlowProgram(Map.of("delay", DataType.Scalar.INTEGER, "payload", DataType.Scalar.TEXT),
            List.of(new FlowProgram.Sleep("wait", "delay")), new ProgramArchive(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition(
                new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of())).withAssembly();
        var input = new DataValue.RecordValue(Map.of("delay", new DataValue.IntegerValue(2),
            "payload", new DataValue.TextValue("a".repeat(160))));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, input, limits, true);
        var snapshot = host.flowSnapshot().orElseThrow();
        var archive = host.write();
        var ram = new ComputerFileRam();
        var owner = UUID.randomUUID();
        assertTrue(ram.reserve(owner, "large.mid", 900, 1024,
            remaining -> host.fitsMemory(limits.withMemoryUnits(remaining))).isEmpty());
        assertEquals(archive, host.write());
        assertEquals(snapshot, host.flowSnapshot().orElseThrow());
        var file = ram.reserve(owner, "small.mid", 100, 1024,
            remaining -> host.fitsMemory(limits.withMemoryUnits(remaining)));
        assertTrue(file.isPresent());
        assertTrue(host.synchronize(limits.withMemoryUnits(1024 - ram.reservedBytes()), true));
        host.step(limits.withMemoryUnits(1024 - ram.reservedBytes()), true);
        assertEquals(FlowRuntime.Status.SLEEPING, host.flowSnapshot().orElseThrow().state().status());
        ram.clear(owner);
        assertEquals(0, ram.reservedBytes());
        assertTrue(host.synchronize(limits, true));
        assertEquals(FlowRuntime.Status.SLEEPING, host.flowSnapshot().orElseThrow().state().status());
    }
}
