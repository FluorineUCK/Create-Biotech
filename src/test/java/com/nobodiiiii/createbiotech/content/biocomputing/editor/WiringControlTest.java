package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class WiringControlTest {
    static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @Test void explicitEnableUsesStrictBooleanSelectionAndAppendedActionsOnBothDeviceProtocols() {
        for (boolean enabled : List.of(false, true)) {
            var selection = new WiringControlData.SetEnabled(7, "wire", enabled);
            assertEquals(selection, WiringControlData.SetEnabled.decode(selection.encode()));
            for (DataValue malformed : List.of(new DataValue.IntegerValue(1), new DataValue.TextValue("true"), DataValue.NullValue.INSTANCE)) {
                var fields = new LinkedHashMap<>(selection.encode().fields()); fields.put("enabled", malformed);
                assertThrows(IllegalArgumentException.class, () -> WiringControlData.SetEnabled.decode(new DataValue.RecordValue(fields)));
            }
            var extra = new LinkedHashMap<>(selection.encode().fields()); extra.put("endpoint", new DataValue.TextValue("forged"));
            assertThrows(IllegalArgumentException.class, () -> WiringControlData.SetEnabled.decode(new DataValue.RecordValue(extra)));
            var bytes = ProgramEditorWire.encodeValue(selection.encode(), LIMITS);
            var action = ComputerControlSession.Action.SET_WIRING_ENABLED;
            assertEquals(24, ComputerControlSession.Action.READ_LOG.ordinal()); assertEquals(25, action.ordinal()); assertTrue(action.wiring());
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var request = new ProgramControlRequest(1, UUID.randomUUID(), 9, 3, action, bytes); request.write(wire);
                var decoded = new ProgramControlRequest(wire);
                assertEquals(action, decoded.action()); assertArrayEquals(bytes, decoded.inputs());
            } finally { wire.release(); }
            var clusterAction = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession.Action.SET_WIRING_ENABLED;
            assertEquals(7, com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession.Action.READ_LOG.ordinal());
            assertEquals(8, clusterAction.ordinal()); assertTrue(clusterAction.wiring()); assertTrue(clusterAction.requiresValue());
            var clusterWire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var request = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest(1, UUID.randomUUID(), 9, 3, clusterAction, bytes);
                request.write(clusterWire);
                var decoded = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest(clusterWire);
                assertEquals(clusterAction, decoded.action()); assertArrayEquals(bytes, decoded.value());
            } finally { clusterWire.release(); }
        }
    }
    @Test void bilingualWiringLabelsHaveMatchingKeysAndFormatArguments() throws Exception {
        var english = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/en_us.json"))).getAsJsonObject();
        var chinese = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/zh_cn.json"))).getAsJsonObject();
        var keys = english.keySet().stream().filter(key -> key.contains(".wiring.")).collect(java.util.stream.Collectors.toSet());
        assertFalse(keys.isEmpty()); assertEquals(keys, chinese.keySet().stream().filter(key -> key.contains(".wiring.")).collect(java.util.stream.Collectors.toSet()));
        for (String key : keys) {
            String a = english.get(key).getAsString(), b = chinese.get(key).getAsString();
            assertFalse(a.isBlank()); assertFalse(b.isBlank()); assertEquals(a.split("%s",-1).length,b.split("%s",-1).length,key);
        }
        assertTrue(english.has("create_biotech.biocomputing.communication.wiring")); assertTrue(chinese.has("create_biotech.biocomputing.communication.wiring"));
    }
    @Test void selectionRecordsRejectExtraAuthorityAndNumericCoercions() {
        var add = new WiringControlData.Add(7, "wire§literal", "x0", 2);
        assertEquals(add, WiringControlData.Add.decode(add.encode()));
        for (String field : List.of("position", "device", "endpoint")) {
            var extra = new LinkedHashMap<>(add.encode().fields()); extra.put(field, new DataValue.TextValue("forged"));
            assertThrows(IllegalArgumentException.class, () -> WiringControlData.Add.decode(new DataValue.RecordValue(extra)));
        }
        for (DataValue value : List.of(new DataValue.DecimalValue(2), new DataValue.IntegerValue(-1), new DataValue.IntegerValue(1L << 32))) {
            var fields = new LinkedHashMap<>(add.encode().fields()); fields.put("peer", value);
            assertThrows(IllegalArgumentException.class, () -> WiringControlData.Add.decode(new DataValue.RecordValue(fields)));
        }
        assertThrows(IllegalArgumentException.class, () -> new WiringControlData.Remove(0, "wire"));
        var remove = new WiringControlData.Remove(9, "wire"); assertEquals(remove, WiringControlData.Remove.decode(remove.encode()));
        assertThrows(IllegalArgumentException.class, () -> WiringControlData.Remove.decode(add.encode()));
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(add.encode(), new ArchiveLimits(1, 0, 1)));
    }
    @Test void wiringActionsAppendWithoutChangingOldOrdinalsAndOnlyMutationsCarryBoundedData() {
        assertEquals(20, ComputerControlSession.Action.TERMINAL_SUBMIT.ordinal());
        var data = new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(7), "name", new DataValue.TextValue("wire"),
            "local_pin", new DataValue.TextValue("x0"), "peer", new DataValue.IntegerValue(0)));
        var payload = ProgramEditorWire.encodeValue(data, LIMITS);
        int ordinal = 21;
        for (String name : List.of("READ_WIRING", "ADD_WIRING", "REMOVE_WIRING")) {
            var action = ComputerControlSession.Action.valueOf(name);
            assertEquals(ordinal++, action.ordinal()); assertTrue(action.endpoint());
            var bytes = name.equals("READ_WIRING") ? new byte[0] : payload;
            var request = new ProgramControlRequest(1, UUID.randomUUID(), 8, 3, action, bytes);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try { request.write(wire); var decoded = new ProgramControlRequest(wire);
                assertEquals(action, decoded.action()); assertArrayEquals(bytes, decoded.inputs());
            } finally { wire.release(); }
            if (name.equals("READ_WIRING")) assertThrows(IllegalArgumentException.class,
                () -> new ProgramControlRequest(1, UUID.randomUUID(), 8, 3, action, payload));
        }
    }
}
