package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NarratorOutputProtocolTest {
    @Test void speechIsRateLimitedAndAnAdmittedUtteranceIsNotPersistedForReplay() {
        var owner = UUID.randomUUID();
        var before = LocalTextOutputState.grant(LocalTextOutputState.closed(UUID.randomUUID(), UUID.randomUUID()),
            Optional.of(owner), UUID.randomUUID());
        var address = new DataValue.RecordValue(Map.of("x", number(1), "y", number(2), "z", number(3)));
        var frame = say(before, address, "Ready");
        var denied = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, before, UUID.randomUUID(), address, frame, 24, 100, 40, true).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        var over = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, before, owner, address, say(before, address, "界".repeat(9)), 24, 100, 40, true).orElseThrow();
        assertEquals(text("speech_text_limit"), over.reply().field("code"));
        assertSame(before, over.next());
        var budget = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, before, owner, address, frame, 24, 100, 40, false).orElseThrow();
        assertEquals(text("speech_budget_exhausted"), budget.reply().field("code"));
        assertSame(before, budget.next());

        var admitted = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, before, owner, address, frame, 24, 100, 40, true).orElseThrow();
        assertEquals(text("accepted"), admitted.reply().field("code"));
        assertEquals(Optional.of("Ready"), admitted.utterance());
        var archived = LocalTextOutputState.save(admitted.next());
        assertFalse(archived.toString().contains("Ready"));
        var restored = LocalTextOutputState.load(archived, UUID.randomUUID());
        assertEquals("interrupted", restored.last());
        var stale = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, restored, owner, address, frame, 24, 110, 40, true).orElseThrow();
        assertEquals(text("stale_session"), stale.reply().field("code"));
        assertTrue(stale.utterance().isEmpty());
        var early = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, restored, owner, address, say(restored, address, "New"), 24, 139, 40, true).orElseThrow();
        assertEquals(text("speech_rate_limit"), early.reply().field("code"));
        assertSame(restored, early.next());
        var next = LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, restored, owner, address, say(restored, address, "New"), 24, 140, 40, true).orElseThrow();
        assertEquals(Optional.of("New"), next.utterance());
    }

    private static DataValue.RecordValue say(LocalTextOutputState.State state, DataValue.RecordValue address, String line) {
        return new DataValue.RecordValue(Map.of("kind", text("narrator_output_command"), "version", number(1), "target", address,
            "request", text("say:1"), "operation", text("speak"), "arguments", new DataValue.RecordValue(Map.of(
            "device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", number(state.revision()), "text", text(line)))));
    }

    private static DataValue.IntegerValue number(long v) { return new DataValue.IntegerValue(v); }
    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
}
