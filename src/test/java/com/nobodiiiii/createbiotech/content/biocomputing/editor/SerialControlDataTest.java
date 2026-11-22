package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class SerialControlDataTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(100000, 1000000, 10000000);
    @Test void appendedCommandsRoundTripWithoutChangingLegacyOrdinalsOrGrantingPayloadToRead() {
        assertEquals(26, ComputerControlSession.Action.READ_NETWORK.ordinal());
        var config = new SerialControlData.Configuration("s0", Direction.WEST, true);
        for (var action : List.of(ComputerControlSession.Action.READ_SERIAL, ComputerControlSession.Action.CONFIGURE_SERIAL)) {
            assertTrue(action.ordinal() > ComputerControlSession.Action.READ_NETWORK.ordinal());
            var bytes = action == ComputerControlSession.Action.READ_SERIAL ? new byte[0] : ProgramEditorWire.encodeValue(config.encode(), LIMITS);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                new ProgramControlRequest(1, UUID.randomUUID(), 1, 1, action, bytes).write(wire);
                var decoded = new ProgramControlRequest(wire); assertEquals(action, decoded.action()); assertArrayEquals(bytes, decoded.inputs());
                if (bytes.length > 0) assertEquals(config, SerialControlData.Configuration.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(decoded.inputs(), LIMITS)));
            } finally { wire.release(); }
        }
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1, UUID.randomUUID(), 1, 1,
            ComputerControlSession.Action.READ_SERIAL, ProgramEditorWire.encodeValue(config.encode(), LIMITS)));
    }
    @Test void fullHardwareDirectoryRemainsBoundedAndMalformedCountsOrFieldsAreRejected() {
        var ports = new TreeMap<String, SerialDevice.PortView>();
        for (int i = 0; i < HardwarePins.MAX_PINS; i++) ports.put(i + "x".repeat(60), new SerialDevice.PortView(Optional.of(Direction.EAST), true,
            new SerialDevice.Status("interrupted", "topology_unloaded", "configuration_changed", new SerialReceiveBuffer.Status(2, 256, Set.of(SerialReceiveBuffer.Fault.BUFFER_FULL)))));
        var view = new SerialDevice.View(Optional.empty(), ports);
        var encoded = ProgramEditorWire.encodeValue(SerialControlData.encode(view), LIMITS);
        assertEquals(view, SerialControlData.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(encoded, LIMITS)));
        var root = (DataValue.RecordValue)SerialControlData.encode(view).field("serial");
        var rawPorts = (DataValue.RecordValue)root.field("ports"); var original = (DataValue.RecordValue)rawPorts.fields().values().iterator().next();
        for (String key : List.of("frames", "bytes", "side", "enabled", "faults", "extra")) {
            var invalid = new LinkedHashMap<>(original.fields()); invalid.put(key, new DataValue.IntegerValue(-1));
            var invalidValue = new DataValue.RecordValue(Map.of("serial", new DataValue.RecordValue(Map.of("diagnostic", DataValue.NullValue.INSTANCE,
                "ports", new DataValue.RecordValue(Map.of("s0", new DataValue.RecordValue(invalid)))))));
            assertThrows(IllegalArgumentException.class, () -> SerialControlData.decode(invalidValue), key);
        }
    }
    @Test void gatewayMetadataRoundTripsAndRejectsMalformedTupleWithoutChangingTheMenuPacketLimit() {
        var binding = new SerialGatewayBinding(UUID.randomUUID(), new BlockPos(-30000000, -64, 30000000), Direction.WEST, "n".repeat(64));
        var view = new SerialDevice.View(Optional.empty(), Map.of("s0", new SerialDevice.PortView(Optional.of(Direction.WEST), false,
            new SerialDevice.Status("idle", "closed", "", new SerialReceiveBuffer.Status(0, 0, Set.of())), Optional.of(binding))));
        var encoded = ProgramEditorWire.encodeValue(SerialControlData.encode(view), LIMITS);
        assertEquals(view, SerialControlData.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(encoded, LIMITS)));
        assertEquals(24 * 1024, ProgramEditorWire.MAX_BYTES);
        var root = (DataValue.RecordValue)SerialControlData.encode(view).field("serial");
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)((DataValue.RecordValue)root.field("ports")).field("s0")).fields());
        for (DataValue bad : List.<DataValue>of(new DataValue.ListValue(List.of()), new DataValue.TextValue("not a route"))) {
            fields.put("gateway", bad);
            var invalid = new DataValue.RecordValue(Map.of("serial", new DataValue.RecordValue(Map.of("diagnostic", DataValue.NullValue.INSTANCE,
                "ports", new DataValue.RecordValue(Map.of("s0", new DataValue.RecordValue(fields)))))));
            assertThrows(IllegalArgumentException.class, () -> SerialControlData.decode(invalid));
        }
    }
    @Test void graphicalBindingKeepsIdentityPositionAndOrientation() {
        var panel=new SerialControlData.PanelSocket(UUID.randomUUID(),new BlockPos(-2,45,4),Direction.SOUTH);
        var pick=new SerialControlData.GatewayConfiguration("s1","out",Optional.of(panel));
        assertEquals(pick,SerialControlData.GatewayConfiguration.decode(pick.encode()));
        var bad=new HashMap<>(panel.encode().fields());bad.put("side",new DataValue.TextValue("sideways"));
        assertThrows(IllegalArgumentException.class,()->SerialControlData.PanelSocket.decode(new DataValue.RecordValue(bad)));
    }
    @Test void serialControlsAndStatusCodesAreLocalizedInBothResourcePacks() throws Exception {
        var en = ComputerUiTranslationsTest.load("en_us"); var zh = ComputerUiTranslationsTest.load("zh_cn");
        var keys = en.keySet().stream().filter(k -> k.startsWith("create_biotech.biocomputing.serial.")).collect(java.util.stream.Collectors.toSet());
        assertTrue(keys.size() > 40);
        assertEquals(keys, zh.keySet().stream().filter(k -> k.startsWith("create_biotech.biocomputing.serial.")).collect(java.util.stream.Collectors.toSet()));
        for (String key : keys) {
            assertFalse(en.get(key).isBlank()); assertFalse(zh.get(key).isBlank());
            assertEquals(en.get(key).chars().filter(c -> c == '%').count(), zh.get(key).chars().filter(c -> c == '%').count(), key);
        }
    }
}
