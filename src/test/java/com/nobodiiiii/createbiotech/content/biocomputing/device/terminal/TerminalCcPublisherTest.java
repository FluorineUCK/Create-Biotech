package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalCcPublisherTest {
    private static final Limits LIMITS = new Limits(12, 1, 32,
        new SerialRecordCodec.Limits(4096, 32768, new ArchiveLimits(256, 2048, 4096)),
        new SerialRecordCodec.Limits(1024, 8192, new ArchiveLimits(96, 256, 512)));
    private static final String LAYOUT = """
        {"version":3,"root":"form","nodes":{
          "form":{"kind":"column","children":["caption","entry"]},
          "caption":{"kind":"text","value":{"kind":"path","input":"output","steps":[{"kind":"key","name":"title"}]}},
          "entry":{"kind":"input","initial":{"kind":"literal","value":""},
            "enabled":{"kind":"literal","value":true},"entry":"save","argument":"answer",
            "inputs":{"kind":"literal","value":{"counter":9007199254740993}}}
        }}
        """;
    private static final String ROOTS = "{\"output\":{\"title\":\"From Lua\",\"sample\":[1,1.0,true,null]}}";

    @Test void exampleUsesTheNativeLayoutSchemaAndTypedJson() {
        assertEquals("form", TerminalCodec.decode(EditorValueFormat.parse(EditorValueFormat.Kind.JSON, LAYOUT)).root());
        assertTrue(EditorValueFormat.format(EditorValueFormat.parse(EditorValueFormat.Kind.JSON, ROOTS)).contains("[1,1.0,true,null]"));
    }

    @Test void onePublisherAndExplicitSwitchRotateSessionAndRetainPage() {
        var initial = closed(UUID.randomUUID(), UUID.randomUUID());
        var serialId = UUID.randomUUID();
        var serial = grant(initial, Optional.of(serialId), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> grantCc(serial, OptionalInt.of(0), UUID.randomUUID()));
        var cc = grantCc(grant(serial, Optional.empty(), UUID.randomUUID()), OptionalInt.of(0), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> grant(cc, Optional.of(serialId), UUID.randomUUID()));
        var address = record(Map.of("x", number(1)));
        var request = record(Map.of("kind", text("terminal_ui_command"), "version", number(1), "target", address,
            "request", text("probe"), "operation", text("status"), "arguments", record(Map.of())));
        assertEquals(text("closed"), TerminalUiProtocol.plan(cc, serialId, address, request, LIMITS).orElseThrow().reply().field("code"));
        var page = TerminalCcProtocol.publish(cc, 0, cc.device().toString(), cc.session().toString(), "0", LAYOUT, ROOTS, LIMITS).state();
        var event = interact(page, page.session(), page.revision(), "4:form/5:entry", Optional.of("typed"), LIMITS);
        assertEquals("queued", event.code());
        var saved = save(event.state());
        var reloaded = load(saved, UUID.randomUUID());
        assertEquals(OptionalInt.of(0), reloaded.ccPublisher());
        assertEquals(page.page(), reloaded.page());
        assertTrue(reloaded.events().isEmpty());
        var stale = assertThrows(IllegalArgumentException.class, () -> TerminalCcProtocol.publish(reloaded, 0,
            cc.device().toString(), cc.session().toString(), "1", LAYOUT, ROOTS, LIMITS));
        assertEquals("stale_session", stale.getMessage());
        saved.putUUID("Controller", serialId);
        assertThrows(IllegalArgumentException.class, () -> load(saved, UUID.randomUUID()));
        var revoked = grantCc(event.state(), OptionalInt.empty(), UUID.randomUUID());
        assertTrue(revoked.events().isEmpty());
        assertEquals(page.page(), revoked.page());
        assertEquals("access_denied", assertThrows(IllegalArgumentException.class,
            () -> TerminalCcProtocol.status(revoked, 0)).getMessage());
    }

    @Test void publishesStructuredPageAndPreparesOneClaimWithoutLosingPrecision() {
        var before = grantCc(closed(UUID.randomUUID(), UUID.randomUUID()), OptionalInt.of(0), UUID.randomUUID());
        String device = before.device().toString(), session = before.session().toString();
        assertEquals("access_denied", assertThrows(IllegalArgumentException.class,
            () -> TerminalCcProtocol.publish(before, 1, device, session, "0", LAYOUT, ROOTS, LIMITS)).getMessage());
        assertEquals("invalid_view", TerminalCcProtocol.publish(before, 0, device, session, "0", LAYOUT, "{\"x\":0,\"x\":1}", LIMITS).code());
        assertEquals("view_capacity", TerminalCcProtocol.publish(before, 0, device, session, "0", LAYOUT, " ".repeat(5000), LIMITS).code());
        var published = TerminalCcProtocol.publish(before, 0, device, session, "0", LAYOUT, ROOTS, LIMITS);
        assertEquals("published", published.code());
        assertEquals(record(Map.of("title", text("From Lua"), "sample", EditorValueFormat.parse(EditorValueFormat.Kind.JSON, "[1,1.0,true,null]"))),
            published.state().page().orElseThrow().roots().field("output"));
        assertEquals("changed", assertThrows(IllegalArgumentException.class,
            () -> TerminalCcProtocol.publish(published.state(), 0, device, session, "0", LAYOUT, ROOTS, LIMITS)).getMessage());
        var event = interact(published.state(), before.session(), 1, "4:form/5:entry", Optional.of("typed"), LIMITS);
        assertEquals("queued", event.code());
        assertEquals("1", TerminalCcProtocol.status(event.state(), 0).get("next_event"));
        var planned = TerminalCcProtocol.take(event.state(), 0, device, session, "1", "1");
        assertEquals("save", planned.event().get("name"));
        assertEquals("1", planned.event().get("layout_revision"));
        assertTrue(((String)planned.event().get("values_json")).contains("9007199254740993"));
        assertTrue(((String)planned.event().get("values_json")).contains("\"typed\""));
        assertEquals(1, event.state().events().size(), "Preparing a Lua result must not consume the event");
        assertEquals("no_events", assertThrows(IllegalArgumentException.class,
            () -> TerminalCcProtocol.take(planned.next(), 0, device, session, "1", "1")).getMessage());
    }
}
