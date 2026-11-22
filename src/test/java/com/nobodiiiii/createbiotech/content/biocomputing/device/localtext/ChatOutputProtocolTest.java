package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatOutputProtocolTest {
    @Test void onlyAuthorizedAddressedLiteralMessagesCanBeAdmittedAndNothingIsReplayed() {
        var owner = UUID.randomUUID();
        var closed = LocalTextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        var state = LocalTextOutputState.grant(closed, Optional.of(owner), UUID.randomUUID());
        var target = new DataValue.RecordValue(Map.of("x", integer(1), "y", integer(2), "z", integer(3)));
        var frame = send(state, target, "/say literal");
        assertTrue(LocalTextOutputProtocol.plan(LocalTextOutputKind.NARRATION, state, owner, target,
            frame, 24, 100, 40, true).isEmpty(), "The speaker cannot accept a chat frame");
        assertEquals(text("closed"), plan(closed, owner, target, frame, 100, true).reply().field("code"));
        var denied = plan(state, UUID.randomUUID(), target, frame, 100, true);
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        for (String invalid : new String[]{"x\ny", "x\u00a7cy", "x\u202ey"}) {
            var rejected = plan(state, owner, target, send(state, target, invalid), 100, true);
            assertEquals(text("invalid_chat_text"), rejected.reply().field("code"));
            assertTrue(rejected.utterance().isEmpty());
        }
        assertEquals(text("chat_text_limit"),
            plan(state, owner, target, send(state, target, "界".repeat(9)), 100, true).reply().field("code"));
        assertEquals(text("chat_budget_exhausted"),
            plan(state, owner, target, frame, 100, false).reply().field("code"));

        var accepted = plan(state, owner, target, frame, 100, true);
        assertEquals(text("chat_output_reply"), accepted.reply().field("kind"));
        assertEquals(text("accepted"), accepted.reply().field("code"));
        assertEquals(Optional.of("/say literal"), accepted.utterance());
        var component = ChatOutputDelivery.message(new BlockPos(1, 2, 3), accepted.utterance().orElseThrow());
        var content = assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, component.getContents());
        assertEquals("create_biotech.chat_output.message", content.getKey());
        var literal = assertInstanceOf(net.minecraft.network.chat.Component.class, content.getArgs()[3]);
        assertEquals("/say literal", literal.getString());
        assertTrue(literal.getStyle().isEmpty());

        var archived = LocalTextOutputState.save(accepted.next());
        assertFalse(archived.toString().contains("/say literal"));
        var restored = LocalTextOutputState.load(archived, UUID.randomUUID());
        assertEquals("interrupted", restored.last());
        assertEquals(text("stale_session"), plan(restored, owner, target, frame, 110, true).reply().field("code"));
        assertEquals(text("chat_rate_limit"),
            plan(restored, owner, target, send(restored, target, "new"), 139, true).reply().field("code"));
    }

    private static LocalTextOutputProtocol.Plan plan(LocalTextOutputState.State state, UUID owner,
            DataValue.RecordValue target, DataValue.RecordValue frame, long tick, boolean quota) {
        return LocalTextOutputProtocol.plan(LocalTextOutputKind.CHAT, state, owner, target, frame, 24, tick, 40, quota).orElseThrow();
    }

    private static DataValue.RecordValue send(LocalTextOutputState.State state, DataValue.RecordValue target, String line) {
        return new DataValue.RecordValue(Map.of("kind", text("chat_output_command"), "version", integer(1), "target", target,
            "request", text("send:1"), "operation", text("send"), "arguments", new DataValue.RecordValue(Map.of(
                "device", text(state.device().toString()), "session", text(state.session().toString()),
                "revision", integer(state.revision()), "text", text(line)))));
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
