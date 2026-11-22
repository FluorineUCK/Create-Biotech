package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class NarratorScratchProtocolFixture {
    private NarratorScratchProtocolFixture() {}

    public record Grant(UUID sender, LocalTextOutputState.State state, Map<String, DataValue> token) {}

    public static Grant start(UUID sender, DataValue.RecordValue target, DataValue.RecordValue frame) {
        var closed = LocalTextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        var denied = plan(closed, sender, target, frame, 0, true);
        assertEquals(text("closed"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        var allowed = LocalTextOutputState.grant(closed, Optional.of(sender), UUID.randomUUID());
        var open = plan(allowed, sender, target, frame, 0, true);
        assertEquals(allowed, open.next());
        assertTrue(open.utterance().isEmpty());
        assertEquals(text("ok"),open.reply().field("code"));
        assertEquals(text("narrator_output_reply"), open.reply().field("kind"));
        assertEquals(frame.field("request"), open.reply().field("request"));
        var token = assertInstanceOf(DataValue.RecordValue.class,open.reply().field("state"));
        return new Grant(sender, allowed, Map.of("device",token.field("device"),"session",token.field("session"),"revision",token.field("revision")));
    }

    public static void verify(Grant grant, DataValue.RecordValue target, DataValue.RecordValue frame, String literal) {
        assertEquals(Set.of("device","session","revision","text"), assertInstanceOf(DataValue.RecordValue.class,frame.field("arguments")).fields().keySet());
        var denied = plan(grant.state(), UUID.randomUUID(), target, frame, 0, true);
        assertEquals(text("denied"), denied.reply().field("code"));
        assertEquals(grant.state(), denied.next());
        assertTrue(denied.utterance().isEmpty());
        var throttled = plan(grant.state(), grant.sender(), target, frame, 0, false);
        assertEquals(text("speech_budget_exhausted"), throttled.reply().field("code"));
        assertTrue(throttled.utterance().isEmpty());
        var admitted = plan(grant.state(), grant.sender(), target, frame, 0, true);
        assertEquals(text("accepted"), admitted.reply().field("code"));
        assertEquals(frame.field("request"), admitted.reply().field("request"));
        assertEquals(Optional.of(literal), admitted.utterance(), "The text becomes one pending utterance");
        assertTrue(admitted.next().pending(), "Acceptance is distinct from client speech playback");
        var replay = plan(admitted.next(), grant.sender(), target, frame, 20, true);
        assertEquals(text("changed"), replay.reply().field("code"));
        assertTrue(replay.utterance().isEmpty());
        var loaded = LocalTextOutputState.load(LocalTextOutputState.save(admitted.next()), UUID.randomUUID());
        assertEquals("interrupted", loaded.last());
        assertEquals(text("stale_session"), plan(loaded, grant.sender(), target, frame, 20, true).reply().field("code"));
    }

    private static LocalTextOutputProtocol.Plan plan(LocalTextOutputState.State state, UUID source, DataValue.RecordValue address,
            DataValue.RecordValue frame, long tick, boolean budget) {
        return LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, state, source, address, frame, 64, tick, 8, budget).orElseThrow();
    }

    private static DataValue.TextValue text(String value) {return new DataValue.TextValue(value);}
}
