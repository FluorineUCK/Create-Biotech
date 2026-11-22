package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClusterControlDraftTest {
    @Test void legacyMemberAndPortEditsPreserveConnectionsAndRenameBothDirections() {
        var config = connectedConfiguration(); var draft = new ClusterControlDraft(config);
        draft.addMember("third", new ClusterControlView.Candidate(new BlockPos(3, 0, 0), Optional.of(UUID.randomUUID()), List.of("in"), true, true, "ready"));
        assertEquals(config.connections(), draft.configuration().connections());
        draft.putPort(null, "public", "source", "in", "external_send", "outside", true);
        assertEquals(config.connections(), draft.configuration().connections());
        draft.removePort("public");
        assertEquals(config.connections(), draft.configuration().connections());
        draft.renameMember("source", "renamed");
        assertEquals(new ClusterConfiguration.Connection("renamed", "send", "target", "in", true), draft.configuration().connections().get("forward"));
        assertEquals(new ClusterConfiguration.Connection("target", "reply", "renamed", "responses", false), draft.configuration().connections().get("return"));
    }

    @Test void explicitlyRemovingMemberRemovesOnlyConnectionsIncidentToIt() {
        var draft = new ClusterControlDraft(connectedConfiguration());
        draft.addMember("third", new ClusterControlView.Candidate(new BlockPos(3, 0, 0), Optional.of(UUID.randomUUID()), List.of("in"), true, true, "ready"));
        draft.removeMember("third");
        assertEquals(Set.of("forward", "return"), draft.configuration().connections().keySet());
        draft.removeMember("source");
        assertTrue(draft.configuration().connections().isEmpty());
        assertEquals(Set.of("target"), draft.configuration().members().keySet());
    }

    private static ClusterConfiguration connectedConfiguration() {
        return new ClusterConfiguration(Map.of(
            "source", new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO),
            "target", new ClusterConfiguration.Member(UUID.randomUUID(), new BlockPos(1, 0, 0))), Map.of(), Map.of(
            "forward", new ClusterConfiguration.Connection("source", "send", "target", "in", true),
            "return", new ClusterConfiguration.Connection("target", "reply", "source", "responses", false)));
    }

    @Test void renamingMemberRetargetsRoutesAndRemovingMemberCascadesItsRoutes() {
        UUID id = UUID.randomUUID();
        var draft = new ClusterControlDraft(new ClusterConfiguration(
            Map.of("old", new ClusterConfiguration.Member(id, new BlockPos(1, 2, 3))),
            Map.of("public", new ClusterConfiguration.Port("old", "input", "reply", "outside", true))));
        draft.renameMember("old", "worker");
        assertEquals("worker", draft.configuration().ports().get("public").member());
        draft.removeMember("worker");
        assertEquals(ClusterConfiguration.empty(), draft.configuration());
    }

    @Test void onlySelectableIdentityBearingCandidateCanBecomeExplicitMember() {
        UUID id = UUID.randomUUID(); var draft = new ClusterControlDraft(ClusterConfiguration.empty());
        var denied = new ClusterControlView.Candidate(new BlockPos(1, 1, 1), Optional.of(id), List.of("in"), false, false, "not_contacting");
        assertThrows(IllegalArgumentException.class, () -> draft.addMember("worker", denied));
        var missing = new ClusterControlView.Candidate(new BlockPos(1, 1, 1), Optional.empty(), List.of(), true, true, "no_program");
        assertThrows(IllegalArgumentException.class, () -> draft.addMember("worker", missing));
        draft.addMember("worker", new ClusterControlView.Candidate(new BlockPos(1, 1, 1), Optional.of(id), List.of("in"), true, true, "ready"));
        assertEquals(id, draft.configuration().members().get("worker").identity());
    }

    @Test void editingRouteIsAtomicWhenNewNamesConflictOrTargetMissingMember() {
        UUID id = UUID.randomUUID();
        var config = new ClusterConfiguration(Map.of("worker", new ClusterConfiguration.Member(id, BlockPos.ZERO)),
            Map.of("one", new ClusterConfiguration.Port("worker", "in", "out1", "ext1", true),
                "two", new ClusterConfiguration.Port("worker", "in", "out2", "ext2", false)));
        var draft = new ClusterControlDraft(config);
        assertThrows(IllegalArgumentException.class, () -> draft.putPort("one", "two", "worker", "in", "new", "ext", true));
        assertThrows(IllegalArgumentException.class, () -> draft.putPort("one", "renamed", "absent", "in", "new", "ext", true));
        assertEquals(config, draft.configuration());
        draft.putPort("one", "renamed", "worker", "input2", "reply2", "outside2", false);
        assertFalse(draft.configuration().ports().containsKey("one"));
        assertEquals(new ClusterConfiguration.Port("worker", "input2", "reply2", "outside2", false),
            draft.configuration().ports().get("renamed"));
    }
}
