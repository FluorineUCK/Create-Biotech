package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.junit.jupiter.api.Test;

class MusicOutputProtocolTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception {
        // Same registry-only fixture as other hardware tests; a plain JUnit worker has no FML mod list.
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true);
    }
    private final UUID source = UUID.randomUUID();
    private final DataValue.RecordValue address = record("x", number(1), "y", number(2), "z", number(3));

    @Test void selectingInstrumentIsAnExplicitSingleActionNotAnAmbientBlockSetting() {
        var closed = MusicOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        var state = MusicOutputState.grant(closed, Optional.of(source), UUID.randomUUID());
        var frame = play(state, "flute", 19);
        var accepted = MusicOutputProtocol.plan(state, source, address, frame).orElseThrow();
        assertEquals(text("accepted"), accepted.reply().field("code"));
        assertEquals(new MusicOutputState.Note(NoteBlockInstrument.FLUTE, 19), accepted.next().pending().orElseThrow());
        assertTrue(state.pending().isEmpty());
        assertEquals(text("changed"), MusicOutputProtocol.plan(accepted.next(), source, address, frame).orElseThrow().reply().field("code"));
        assertEquals(text("busy"), MusicOutputProtocol.plan(accepted.next(), source, address, play(accepted.next(), "harp", 0)).orElseThrow().reply().field("code"));
        var played = MusicOutputState.complete(accepted.next(), "played");
        assertTrue(played.pending().isEmpty()); assertEquals("played", played.last());
        assertEquals(text("changed"), MusicOutputProtocol.plan(played, source, address, frame).orElseThrow().reply().field("code"));
    }

    @Test void authorizationAndStrictTypedArgumentsPrecedeAcceptanceAndDoNotDiscloseTokens() {
        var state = MusicOutputState.grant(MusicOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
        var denied = MusicOutputProtocol.plan(state, UUID.randomUUID(), address, play(state, "flute", 12)).orElseThrow();
        assertSame(state, denied.next()); assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        var unidentified = MusicOutputProtocol.plan(state, null, address, play(state, "flute", 12)).orElseThrow();
        assertEquals(text("denied"), unidentified.reply().field("code"));
        assertSame(state, unidentified.next());
        for (var frame : List.of(play(state, "minecraft:any_sound", 12), play(state, "flute", -1), play(state, "flute", 25), play(state, "flute", Long.MAX_VALUE))) {
            var rejected = MusicOutputProtocol.plan(state, source, address, frame).orElseThrow();
            assertSame(state, rejected.next()); assertEquals(text("invalid_note"), rejected.reply().field("code"));
        }
        var args = new LinkedHashMap<>(tokens(state)); args.put("instrument", text("flute")); args.put("note", text("12"));
        assertEquals(text("invalid_note"), MusicOutputProtocol.plan(state, source, address, command("play", args)).orElseThrow().reply().field("code"));
        var revoked = MusicOutputState.grant(state, Optional.empty(), UUID.randomUUID());
        assertEquals(text("closed"), MusicOutputProtocol.plan(revoked, source, address, play(state, "flute", 12)).orElseThrow().reply().field("code"));
    }

    @Test void reloadedPendingNoteIsInterruptedAndNeverBecomesAReplayCommand() {
        var state = MusicOutputState.grant(MusicOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
        var pending = MusicOutputProtocol.plan(state, source, address, play(state, "bell", 24)).orElseThrow().next();
        var loaded = MusicOutputState.load(MusicOutputState.save(pending), UUID.randomUUID());
        assertTrue(loaded.pending().isEmpty()); assertEquals("interrupted", loaded.last());
        assertEquals(pending.revision(), loaded.revision());
        assertEquals(text("stale_session"), MusicOutputProtocol.plan(loaded, source, address, play(pending, "bell", 24)).orElseThrow().reply().field("code"));
        var cancelled = MusicOutputState.complete(pending, "cancelled");
        assertEquals("cancelled", MusicOutputState.load(MusicOutputState.save(cancelled), UUID.randomUUID()).last());
        assertThrows(IllegalArgumentException.class, () -> MusicOutputState.complete(cancelled, "played"));
        var corrupt = MusicOutputState.save(pending); corrupt.putInt("Version", 99);
        assertThrows(IllegalArgumentException.class, () -> MusicOutputState.load(corrupt, UUID.randomUUID()));
    }
    private Map<String, DataValue> tokens(MusicOutputState.State state) {
        return Map.of("device", text(state.device().toString()), "session", text(state.session().toString()), "revision", number(state.revision()));
    }
    private DataValue.RecordValue play(MusicOutputState.State state, String instrument, long note) {
        var args = new LinkedHashMap<>(tokens(state)); args.put("instrument", text(instrument)); args.put("note", number(note));
        return command("play", args);
    }
    private DataValue.RecordValue command(String operation, Map<String, DataValue> args) {
        return record("kind", text("music_output_command"), "version", number(1), "target", address,
            "request", text("music:1"), "operation", text(operation), "arguments", new DataValue.RecordValue(args));
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], (DataValue)pairs[i + 1]);
        return new DataValue.RecordValue(fields);
    }
}
