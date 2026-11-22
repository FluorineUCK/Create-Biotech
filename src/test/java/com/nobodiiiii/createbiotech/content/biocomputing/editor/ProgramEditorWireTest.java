package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;

class ProgramEditorWireTest {
    @Test void publicPinListRejectsDuplicateAndOversizedPacketClaims() {
        var packet = new ProgramEditorResponse(7, UUID.randomUUID(), 1, 0, "ready", "", new ResourceLimits(1, 1, 1), LIMITS,
            new byte[0], new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of()));
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int size : new int[]{-1, 65, 2}) {
                wire.clear(); packet.write(wire); wire.writerIndex(wire.writerIndex() - 1); wire.writeVarInt(size);
                if (size == 2) { wire.writeUtf("x0"); wire.writeUtf("x0"); }
                assertThrows(IllegalArgumentException.class, () -> new ProgramEditorResponse(wire));
            }
        } finally { wire.release(); }
    }
    @Test void captureResponseDoesNotDependOnUnrelatedLargeComputerMemory() {
        var draft = new ProgramDraft(); String n = draft.add(ProgramDraftTest.number(1), 0, 0); draft.output("value", n);
        var memory = new DataValue.RecordValue(Map.of("large", new DataValue.TextValue("x".repeat(40_000))));
        var checkpoint = new MachineCheckpoint(memory, ComputationMachine.Status.IDLE, false, 0, 0,
            Optional.empty(), Optional.empty(), List.of());
        var snapshot = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.Snapshot(
            UUID.randomUUID(), draft.snapshot(), checkpoint, Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(ComputerStatusView.of(snapshot), LIMITS));
        var captured = new DataValue.RecordValue(Map.of("peripherals", new DataValue.RecordValue(Map.of("game_time", new DataValue.IntegerValue(42)))));
        var answer = new ComputerControlSession.Answer("ok", 1, "", Optional.of(snapshot), Optional.of(captured));
        var response = ProgramStatusResponse.fromAnswer(1, UUID.randomUUID(), 1, answer, LIMITS);
        assertEquals("ok", response.code());
        var view = (DataValue.RecordValue) ProgramEditorWire.decodeValue(response.status(), LIMITS);
        assertEquals(Map.of("captured_inputs", captured), view.fields());
    }
    @Test void readRequestCannotCarryClientChosenInputs() {
        var read = ComputerControlSession.Action.valueOf("READ_PERIPHERALS");
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1, UUID.randomUUID(), 1, 0, read, new byte[]{1}));
        var request = new ProgramControlRequest(1, UUID.randomUUID(), 1, 0, read, new byte[0]);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try { request.write(wire); assertEquals(read, new ProgramControlRequest(wire).action()); }
        finally { wire.release(); }
    }
    static final ArchiveLimits LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
    @Test void roundTripUsesTheExistingArchiveCodecAndRetainsNamedFields() {
        var draft = new ProgramDraft(); String n = draft.add(ProgramDraftTest.number(42), 1, 2); draft.output("答案", n);
        var archive = draft.snapshot();
        assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, LIMITS), LIMITS));
    }
    @Test void oversizedOrTrailingPayloadCannotBecomeAnEmptyOrPartialProgram() {
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decode(new byte[ProgramEditorWire.MAX_BYTES + 1], LIMITS));
        var draft = new ProgramDraft(); String n = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("x".repeat(40_000))), 0, 0); draft.output("text", n);
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encode(draft.snapshot(), LIMITS));
        draft.configure(n, ProgramDraftTest.number(1));
        byte[] valid = ProgramEditorWire.encode(draft.snapshot(), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decode(Arrays.copyOf(valid, valid.length + 1), LIMITS));
    }
    @Test void requestCopiesPayloadAndRejectsOversizedLengthBeforeNbtParsing() {
        byte[] payload = {1, 2}; UUID nonce = UUID.randomUUID();
        var packet = new ProgramEditorRequest(3, nonce, 4, 5, true, false, payload);
        payload[0] = 9; assertEquals(1, packet.archive()[0]);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            packet.write(wire); var copy = new ProgramEditorRequest(wire);
            assertEquals(nonce, copy.nonce()); assertEquals(4, copy.intent()); assertArrayEquals(new byte[]{1,2}, copy.archive());
            wire.clear(); wire.writeVarInt(3); wire.writeUUID(nonce); wire.writeVarLong(1); wire.writeVarLong(0);
            wire.writeBoolean(true); wire.writeBoolean(false); wire.writeVarInt(ProgramEditorWire.MAX_BYTES + 1);
            assertThrows(RuntimeException.class, () -> new ProgramEditorRequest(wire));
        } finally { wire.release(); }
    }
    @Test void responseRoundTripCarriesOnlyBoundedPublicLimitsAndCopiesArchive() {
        byte[] payload = {3, 4}; UUID nonce = UUID.randomUUID();
        var packet = new ProgramEditorResponse(7, nonce, 8, 9, "ready", "ok",
            new ResourceLimits(10, 11, 12), LIMITS, payload,
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("bus_left", "bus_right"), List.of("sense", "drive")));
        payload[0] = 99; assertArrayEquals(new byte[]{3, 4}, packet.archive());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            packet.write(wire); var copy = new ProgramEditorResponse(wire);
            assertEquals(packet.containerId(), copy.containerId()); assertEquals(nonce, copy.nonce());
            assertEquals(packet.hardware(), copy.hardware()); assertEquals(packet.archiveLimits(), copy.archiveLimits());
            assertEquals(packet.pins(), copy.pins());
            assertArrayEquals(new byte[]{3, 4}, copy.archive());
            wire.clear(); wire.writeVarInt(7); wire.writeUUID(nonce); wire.writeVarLong(8); wire.writeVarLong(9);
            wire.writeUtf("ready", 32); wire.writeUtf("", 512); wire.writeVarLong(1); wire.writeVarLong(1); wire.writeVarLong(1);
            wire.writeVarInt(1); wire.writeVarLong(1); wire.writeVarLong(1); wire.writeVarInt(ProgramEditorWire.MAX_BYTES + 1);
            assertThrows(RuntimeException.class, () -> new ProgramEditorResponse(wire));
        } finally { wire.release(); }
    }
    @Test void controlPacketsRoundTripAndRejectOversizedLengthsBeforeValueDecoding() {
        UUID nonce=UUID.randomUUID();byte[] encoded=ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(2))),LIMITS);
        var request=new ProgramControlRequest(2,nonce,3,4,ComputerControlSession.Action.RUN,encoded);
        var wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{request.write(wire);var copy=new ProgramControlRequest(wire);assertEquals(request.action(),copy.action());assertArrayEquals(encoded,copy.inputs());wire.clear();wire.writeVarInt(2);wire.writeUUID(nonce);wire.writeVarLong(3);wire.writeVarLong(4);wire.writeVarInt(0);wire.writeVarInt(ProgramEditorWire.MAX_BYTES+1);assertThrows(RuntimeException.class,()->new ProgramControlRequest(wire));}finally{wire.release();}
    }
    @Test void statusPacketRoundTripsStructuredMapAndErrorValuesWithoutDiscardingThem() {
        var view=new DataValue.RecordValue(Map.of("map",new DataValue.MapValue(Map.of("a",new DataValue.IntegerValue(1))),"error",new DataValue.ErrorValue("bad","detail")));
        byte[] encoded=ProgramEditorWire.encodeValue(view,LIMITS);UUID nonce=UUID.randomUUID();var packet=new ProgramStatusResponse(1,nonce,2,3,"ok","",encoded);var wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{packet.write(wire);var copy=new ProgramStatusResponse(wire);assertEquals(view,ProgramEditorWire.decodeValue(copy.status(),LIMITS));}finally{wire.release();}
    }}

