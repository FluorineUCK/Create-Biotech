package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CapturedEditorStatusTransportTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10_000, 100_000, 1_000_000);

    @Test void completeReaderCaptureFitsExistingPacketAndArrivesThroughTheMatchingMenu() throws Exception {
        var fields = new DataValue.RecordValue(Map.of("peripherals", doubleChest()));
        var payload = new DataValue.RecordValue(Map.of("captured_inputs", fields));
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(payload, LIMITS),
            "The complete snapshot exceeds the old uncompressed packet");
        UUID nonce = UUID.randomUUID();
        var answer = new ComputerControlSession.Answer("ok", 7, "", Optional.empty(), Optional.of(fields));
        var reply = ProgramStatusResponse.fromAnswer(1, nonce, 3, answer, LIMITS);
        assertEquals("ok", reply.code(), reply.detail());
        assertTrue(reply.status().length > 0 && reply.status().length <= ProgramEditorWire.MAX_BYTES);
        var owner = menu(nonce);
        set(owner, "latestControlAction", ComputerControlSession.Action.READ_PERIPHERALS);
        owner.accept(reply);
        assertEquals(payload, owner.status().orElseThrow());
        assertEquals(fields, owner.capturedInputs().orElseThrow(), "No slots are dropped or aliased");
        assertTrue(owner.debuggerInputs().fields().isEmpty(), "Receiving suggestions is not a debugger input edit");
        var constrained = ProgramStatusResponse.fromAnswer(1, nonce, 3, answer, new ArchiveLimits(100, 1_000, 10_000));
        assertEquals("too_large", constrained.code(), "Compression does not loosen the caller's archive budget");
        assertEquals(0, constrained.status().length);

        var networkMenu = menu(nonce);
        set(networkMenu, "latestControlAction", ComputerControlSession.Action.READ_LOG);
        networkMenu.accept(reply);
        assertTrue(networkMenu.status().isEmpty(), "A non-capture action does not accept this envelope");
    }

    @Test void smallCaptureKeepsLegacyBytesAndUnrelatedLargeStatusStillFails() throws Exception {
        UUID nonce = UUID.randomUUID();
        var tiny = new DataValue.RecordValue(Map.of("network", new DataValue.RecordValue(Map.of())));
        var answer = new ComputerControlSession.Answer("ok", 7, "", Optional.empty(), Optional.of(tiny));
        var reply = ProgramStatusResponse.fromAnswer(1, nonce, 3, answer, LIMITS);
        assertArrayEquals(ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("captured_inputs", tiny)), LIMITS), reply.status());
        var networkMenu = menu(nonce);
        set(networkMenu, "latestControlAction", ComputerControlSession.Action.READ_NETWORK);
        networkMenu.accept(reply);
        assertEquals(tiny, networkMenu.capturedInputs().orElseThrow());

        var unrelated = new ComputerControlSession.Answer("ok", 7, "", Optional.empty(), Optional.empty(), Optional.of(doubleChest()));
        var rejected = ProgramStatusResponse.fromAnswer(1, nonce, 4, unrelated, LIMITS);
        assertEquals("too_large", rejected.code());
        assertEquals(0, rejected.status().length);
    }

    private static ComputerProgramMenu menu(UUID nonce) throws Exception {
        var menu = allocate(ComputerProgramMenu.class);
        set(menu, "containerId", 1);
        set(menu, "nonce", nonce);
        set(menu, "latestControlIntent", 3L);
        set(menu, "response", Optional.of(new ProgramEditorResponse(1, nonce, 1, 7, "ready", "",
            new ResourceLimits(256, 1_000, 8), LIMITS, new byte[0])));
        set(menu, "status", Optional.empty());
        set(menu, "controlResponse", Optional.empty());
        set(menu, "debuggerInputs", new DataValue.RecordValue(Map.of()));
        return menu;
    }

    private static DataValue.RecordValue doubleChest() {
        var slots = new DataValue.ListValue(IntStream.range(0, 54).<DataValue>mapToObj(i -> new DataValue.RecordValue(Map.of(
            "slot", new DataValue.IntegerValue(i),
            "description", new DataValue.TextValue("Slot " + i + ": " + "Sample item description. ".repeat(22)),
            "count", new DataValue.IntegerValue(i % 16)))).toList());
        var components = new DataValue.RecordValue(Map.of("create_biotech:inventory", slots));
        var sample = new DataValue.RecordValue(Map.of("components", components));
        return new DataValue.RecordValue(Map.of("readers", new DataValue.RecordValue(Map.of("0,0,0", sample))));
    }
}
