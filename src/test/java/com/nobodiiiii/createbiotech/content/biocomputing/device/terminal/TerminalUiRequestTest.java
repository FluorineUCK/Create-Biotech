package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalUiRequestTest {
    @Test
    void emptyInputSubmissionRemainsDistinctFromClickAndCannotCarryAnAuthorizationChoice() {
        UUID nonce = UUID.randomUUID();
        var submit = new TerminalUiRequest(7, nonce, TerminalUiRequest.Action.SUBMIT, -1, "form/query", "");
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            submit.write(wire);
            var restored = new TerminalUiRequest(wire);
            assertEquals(submit, restored);
            assertFalse(wire.isReadable());
            assertNotEquals(restored, new TerminalUiRequest(7, nonce, TerminalUiRequest.Action.CLICK, -1, "form/query", ""));
        } finally {
            wire.release();
        }
        assertThrows(IllegalArgumentException.class, () ->
            new TerminalUiRequest(7, nonce, TerminalUiRequest.Action.SUBMIT, 0, "form/query", ""));
        assertThrows(IllegalArgumentException.class, () ->
            new TerminalUiRequest(7, nonce, TerminalUiRequest.Action.SUBMIT, -1, "form/query", "x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () ->
            new TerminalUiRequest(7, nonce, TerminalUiRequest.Action.GRANT, 0, "form/query", ""));
    }
}
