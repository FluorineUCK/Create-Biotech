package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeBrowserWireTest {
    @Test void independentStructureDiagnosticsRetainEveryHistoricalOrdinalAndField() {
        var names = List.of("UNFORMED", "VALID", "PARTIAL", "TOO_LARGE", "TOO_WIDE", "CORE_CONFLICT", "SPACE_MISMATCH");
        assertEquals(names, Arrays.stream(State.values()).map(Enum::name).toList());
        for (int ordinal = 0; ordinal < names.size(); ordinal++) {
            var value = new PatternBrowserDiagnostics(State.valueOf(names.get(ordinal)), 2, 3, 40, 5, 6, 7);
            var actual = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            var historical = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                PatternBrowserWire.writeDiagnostics(actual, value);
                for (int field : new int[]{ordinal, 2, 3, 40, 5, 6, 7}) historical.writeVarInt(field);
                assertEquals(historical, actual);
                assertEquals(value, PatternBrowserWire.readDiagnostics(actual));
                assertFalse(actual.isReadable());
            } finally { actual.release(); historical.release(); }
        }
    }
}
