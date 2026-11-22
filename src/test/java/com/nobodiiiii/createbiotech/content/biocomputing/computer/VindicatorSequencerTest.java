package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VindicatorSequencerTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws ReflectiveOperationException {
        ComputerBehaviourTest.bootstrap();
    }

    @Test void behaviourGatesBySpeciesAndPublishesOnlyFromAnActiveLoadedTick() {
        var block = new ComputerBehaviourTest.Harness();
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var command = start(step(13,3));
        assertThrows(IllegalArgumentException.class, () -> computer.configureSequencer(command));
        var selection = block.selection;
        var id = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("vindicator"),
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("none"),1);
        block.selection = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(
            selection.status(),java.util.Optional.of(id),"vindicator",selection.limits());
        computer.configureSequencer(command);
        block.tick();
        assertEquals(Map.of("p0",13),computer.signalOutputs().orElseThrow().outputs());
        assertTrue(computer.flowSnapshot().isEmpty());
        var nbt = new net.minecraft.nbt.CompoundTag();
        computer.write(nbt,net.minecraft.core.RegistryAccess.EMPTY,false);
        var packet = nbt.copy(); computer.write(packet,net.minecraft.core.RegistryAccess.EMPTY,true);
        assertFalse(packet.contains(VindicatorSequencer.ROOT));
        block.available = false; computer.unload();
        assertTrue(computer.signalOutputs().isEmpty());
        var restored = new ComputerBehaviourTest.Harness(); restored.selection = block.selection;
        var host = restored.getBehaviour(ComputerBehaviour.TYPE);
        host.read(nbt,net.minecraft.core.RegistryAccess.EMPTY,false);
        assertTrue(host.signalOutputs().orElseThrow().outputs().isEmpty(),"Saved level isn't a live drive");
        restored.tick(); restored.tick();
        assertEquals(Map.of("p0",13),host.signalOutputs().orElseThrow().outputs());
        restored.tick(); assertTrue(host.signalOutputs().orElseThrow().outputs().isEmpty());
    }
    static RecordValue step(long level, long ticks) {
        return new RecordValue(Map.of("level", new IntegerValue(level), "ticks", new IntegerValue(ticks)));
    }
    static RecordValue start(RecordValue... steps) {
        return new RecordValue(Map.of("op",new TextValue("start"),"pin",new TextValue("p0"),
            "segments",new ListValue(List.of(steps))));
    }
    @Test void rejectsTheEntireReplacementAndRetainsThePreciseDurableCursor() {
        var s = new VindicatorSequencer();
        s.configure(start(step(4,2),step(0,1)),List.of("p0"));
        s.tick();
        var before = s.save();
        for (var bad : List.of(start(),start(step(-1,1)),start(step(16,1)),start(step(1,0)),
                start(step(1,-1)),start(step(Long.MAX_VALUE,1)),start(java.util.Collections.nCopies(17,step(1,1)).toArray(RecordValue[]::new)))) {
            assertThrows(IllegalArgumentException.class, () -> s.configure(bad,List.of("p0")));
            assertEquals(before,s.save());
        }
        assertThrows(IllegalArgumentException.class, () -> s.configure(start(step(1,1)),List.of("p1")));
        var restored = new VindicatorSequencer();
        restored.read(before);
        assertTrue(restored.mux(SimpleIoState.EMPTY,true).outputs().isEmpty(), "no old pulse before a new tick");
        restored.tick(); assertEquals(Map.of("p0",4),restored.mux(SimpleIoState.EMPTY,true).outputs());
        restored.suspend(); assertTrue(restored.mux(SimpleIoState.EMPTY,true).outputs().isEmpty());
        restored.tick(); assertEquals(Map.of("p0",0),restored.mux(SimpleIoState.EMPTY,true).outputs());
        restored.tick(); assertFalse(restored.armed());
    }
    @Test void unknownOrCorruptArchivesNeverDriveAndCanBeExplicitlyCleared() {
        var s = new VindicatorSequencer();
        var raw = new net.minecraft.nbt.CompoundTag(); raw.putInt("Version",999);
        s.read(raw); s.tick();
        assertEquals(raw,s.save()); assertFalse(s.armed());
        assertEquals(new TextValue("invalid_save"),s.status(true,false).field("state"));
        s.configure(new RecordValue(Map.of("op",new TextValue("stop"))),List.of("p0"));
        assertNull(s.save());
    }
    @Test void ownsOnePhysicalPinWithoutWritingTheOrdinaryLatch() {
        var ordinary = new SimpleIoState(Map.of("p0", 7, "p1", 9));
        var sequence = new VindicatorSequencer();
        sequence.configure(start(step(0,1), step(15,2)), List.of("p0","p1"));
        for (int level : List.of(0,15,15,7)) {
            sequence.tick();
            assertEquals(Map.of("p0", level, "p1", 9), sequence.mux(ordinary, true).outputs());
        }
        assertEquals(7, ordinary.outputs().get("p0"));
    }
}
