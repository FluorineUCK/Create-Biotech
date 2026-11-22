package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ProductionPortRequestTest {
    @Test void refreshRoundTripKeepsTheDraftAndCapturedSessionWithoutAnyNetworkInput() {
        var nonce = UUID.randomUUID();
        var request = new ProductionPortRequest(3, nonce, 7, ProductionPortRequest.Action.REFRESH, -1, "  生物工厂 *  ");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            request.write(buffer);
            var decoded = new ProductionPortRequest(buffer);
            assertEquals(request, decoded);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.matches(3, nonce, 7));
            assertFalse(decoded.matches(4, nonce, 7));
            assertFalse(decoded.matches(3, UUID.randomUUID(), 7));
            assertFalse(decoded.matches(3, nonce, 8));
        } finally { buffer.release(); }
    }

    @Test void publicDecoderRejectsInvalidChoicesActionsVersionsAndAddresses() {
        for (int choice : new int[] {-1, 128, Integer.MAX_VALUE})
            assertRejected(3, 7, 1, choice, "Factory");
        assertRejected(-1, 7, 0, -1, "Factory");
        assertRejected(3, -1, 0, -1, "Factory");
        assertRejected(3, 7, 99, -1, "Factory");
        assertRejected(3, 7, 0, 0, "Factory");
        assertRejected(3, 7, 2, -1, "Factory");
        for (String address : new String[] {"a".repeat(26), "bad\naddress", "bad\u00a7c", "\t"})
            assertRejected(3, 7, 0, -1, address);
        assertRejected(3, 7, 1, 0, "   ");
    }

    @Test void boundaryGrantAndEmptyRevokeRoundTrip() {
        for (var request : new ProductionPortRequest[] {
                new ProductionPortRequest(3, UUID.randomUUID(), 7, ProductionPortRequest.Action.GRANT, 127, "界".repeat(25)),
                new ProductionPortRequest(3, UUID.randomUUID(), 7, ProductionPortRequest.Action.REVOKE, -1, ""),
                new ProductionPortRequest(3, UUID.randomUUID(), 7, ProductionPortRequest.Action.REFRESH, -1, "")}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try { request.write(buffer); assertEquals(request, new ProductionPortRequest(buffer)); }
            finally { buffer.release(); }
        }
    }

    private static void assertRejected(int id, long revision, int action, int choice, String address) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(id); buffer.writeUUID(UUID.randomUUID()); buffer.writeVarLong(revision);
            buffer.writeVarInt(action); buffer.writeVarInt(choice); buffer.writeUtf(address);
            assertThrows(RuntimeException.class, () -> new ProductionPortRequest(buffer));
        } finally { buffer.release(); }
    }
}
