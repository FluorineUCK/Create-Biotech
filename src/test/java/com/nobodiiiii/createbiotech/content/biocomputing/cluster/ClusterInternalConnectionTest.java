package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterRouterTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClusterInternalConnectionTest {
    static final Map<String, ClusterConfiguration.Member> MEMBERS = Map.of("A", WORKER,
        "B", new ClusterConfiguration.Member(CALLER, new BlockPos(7, 70, 9)));
    static final ClusterConfiguration.Connection LINK = new ClusterConfiguration.Connection("A", "calculate", "B", "worker", true);

    @Test void connectionsAreExplicitAndCannotShadowAnotherDestinationOfTheSameSource() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("x", new ClusterConfiguration.Connection("A", "x", "missing", "in", true))));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("x", new ClusterConfiguration.Connection("A", "x", "A", "in", true))));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("x", LINK, "y", new ClusterConfiguration.Connection("A", "calculate", "B", "other", false))));
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(MEMBERS,
            Map.of("public", new ClusterConfiguration.Port("A", "a", "calculate", "external", true)), Map.of("x", LINK)));
        // The namespace is per source: B may use the same name without making A's destination ambiguous.
        assertEquals(2, config(Map.of("x", LINK, "y", new ClusterConfiguration.Connection("B", "calculate", "A", "a", true))).connections().size());
    }
    @Test void connectionsSurviveDurableRouterReloadAndCannotBeAcceptedAsPublicPorts() {
        var router = new ClusterRouter(() -> {}); var config = config(Map.of("calculation", LINK));
        assertEquals("configured", router.configure(config, LIMITS));
        var saved = router.write(); var restored = new ClusterRouter(() -> {}); restored.read(saved);
        assertEquals(config, restored.snapshot(LIMITS).orElseThrow().configuration());
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.CLOSED,
            restored.receive(ClusterState.Side.EXTERNAL, "calculation", message(CALLER, 0), LIMITS));
    }
    @Test void connectionCountUsesExistingConfigurationBudgetWithoutPartiallyPublishing() {
        var router = new ClusterRouter(() -> {}); assertEquals("configured", router.configure(config(Map.of()), LIMITS));
        var before = router.write(); var links = new LinkedHashMap<String, ClusterConfiguration.Connection>();
        for (int i = 0; i < 9; i++) links.put("link" + i, new ClusterConfiguration.Connection("A", "to" + i, "B", "worker", true));
        assertEquals("configuration_limit", router.configure(config(links), LIMITS)); assertEquals(before, router.write());
    }
    @Test void versionOneRestoresAsNoConnectionsAndVersionTwoRequiresItsConnectionTable() {
        var state = ClusterState.empty().configure(new ClusterConfiguration(MEMBERS, Map.of()));
        var fields = new LinkedHashMap<>(ClusterNbtCodec.value(state).fields());
        var cfg = new LinkedHashMap<>(((DataValue.RecordValue)fields.get("configuration")).fields()); cfg.remove("connections"); cfg.remove("wires");
        fields.put("configuration", new DataValue.RecordValue(cfg)); fields.put("version", new DataValue.IntegerValue(1));
        assertEquals(state, ClusterNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE));
        fields.put("version", new DataValue.IntegerValue(2));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ClusterNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
    }
    @Test void publicAndInternalOutputsShareTheSamePerComputerAttachmentBudget() {
        var ports = new LinkedHashMap<String, ClusterConfiguration.Port>();
        for (int i = 0; i < 8; i++) ports.put("public" + i, new ClusterConfiguration.Port("A", "worker", "out" + i, "external", true));
        var router = new ClusterRouter(() -> {});
        assertEquals("configuration_limit", router.configure(new ClusterConfiguration(MEMBERS, ports, Map.of("extra", LINK)), LIMITS));
        assertTrue(router.snapshot(LIMITS).orElseThrow().configuration().members().isEmpty());
    }
    static ClusterConfiguration config(Map<String, ClusterConfiguration.Connection> links) { return new ClusterConfiguration(MEMBERS, Map.of(), links); }
}
