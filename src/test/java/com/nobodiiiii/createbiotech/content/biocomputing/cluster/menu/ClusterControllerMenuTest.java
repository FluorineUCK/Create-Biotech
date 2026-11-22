package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import java.util.*;
import net.minecraft.core.BlockPos;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClusterControllerMenuTest {
    @Test void signalReplyPreflightReservesEveryCurrentAvailabilityReason() {
        var members = Map.of("A", new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO),
            "B", new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO.east()));
        var signal = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire.Pin("A", "left", "p0"),
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire.Pin("B", "right", "p0"), true);
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of(), Map.of("s", signal));
        var reasons = Arrays.stream(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork.Availability.values())
            .filter(value -> value != com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork.Availability.AVAILABLE)
            .map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().collect(java.util.stream.Collectors.joining("+"));
        var view = new ClusterControlView(config, List.of(), "ready", 0, 0, Map.of("s", "signal_" + reasons));
        var generous = new ArchiveLimits(1000, 100000, 100000);
        long units = ProgramEditorWire.decodeValue(ClusterControlWire.encodeView(view, generous), generous).storageUnits();
        var tight = new ArchiveLimits(1000, 100000, units - 1);
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.encodeView(view, tight));
        assertEquals(Optional.of("too_large"), ClusterControllerMenu.preflight(config, List.of(), "ready", 0, 0, tight));
    }

    @Test void bothEndsOfAnEnabledWireRequireTheirExactOpenGrant() {
        var a = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(UUID.randomUUID()), List.of("left"), true, true, "ready");
        var b = new ClusterControlView.Candidate(new BlockPos(1, 0, 0), Optional.of(UUID.randomUUID()), List.of("right"), true, true, "ready");
        var wire = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("A", "left", "x0"),
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("B", "right", "x1"), true);
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(a.identity().orElseThrow(), a.position()),
            "B", new ClusterConfiguration.Member(b.identity().orElseThrow(), b.position())), Map.of(), Map.of(), Map.of("data", wire));
        assertTrue(ClusterControllerMenu.configurationFailure(config, List.of(a, b)).isEmpty());
        var closed = new ClusterControlView.Candidate(a.position(), a.identity(), List.of(), true, true, "ready");
        assertEquals(Optional.of("closed"), ClusterControllerMenu.configurationFailure(config, List.of(closed, b)));
        var changed = new ClusterControlView.Candidate(b.position(), b.identity(), List.of("renamed"), true, true, "ready");
        assertEquals(Optional.of("endpoint_changed"), ClusterControllerMenu.configurationFailure(config, List.of(a, changed)));
        var disabled = new ClusterConfiguration(config.members(), Map.of(), Map.of(), Map.of("data", new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire(wire.left(), wire.right(), false)));
        assertTrue(ClusterControllerMenu.configurationFailure(disabled, List.of(closed, changed)).isEmpty());
    }
    @Test void pooledPlaceholderMustNotUnderestimateDifferentWireStatusesAtThePacketBoundary() {
        var members = Map.of("A", new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO),
            "B", new ClusterConfiguration.Member(UUID.randomUUID(), new BlockPos(1, 0, 0)));
        var wire = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("A", "left", "x0"),
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("B", "right", "x1"), true);
        var wires = new LinkedHashMap<String, com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire>();
        var statuses = new LinkedHashMap<String, String>();
        var modes = List.of("read", "write", "idle", "complete", "readable", "failed");
        var generous = new ArchiveLimits(100000, 10000000, 10000000);
        for (int i = 0; i < 128; i++) {
            String name = String.format("wire%03d", i) + "n".repeat(57);
            wires.put(name, wire); statuses.put(name, "xbus_" + modes.get(i % modes.size()) + "_" + modes.get(i / modes.size() % modes.size()));
            var config = new ClusterConfiguration(members, Map.of(), Map.of(), wires);
            if (ClusterControllerMenu.preflight(config, List.of(), "ready", 0, 0, generous).isEmpty())
                assertDoesNotThrow(() -> ClusterControlWire.encodeView(new ClusterControlView(config, List.of(), "ready", 0, 0, statuses), generous),
                    "Published configuration must leave room for distinct status values, not only one pooled placeholder");
        }
    }
    @Test void preflightReservesWireDiagnosticsBeforePublishingTheConfiguration() {
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO),
            "B", new ClusterConfiguration.Member(UUID.randomUUID(), new BlockPos(1, 0, 0))), Map.of(), Map.of(), Map.of("wire",
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire(
                new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("A", "left", "x0"),
                new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("B", "right", "x1"), true)));
        var generous = new ArchiveLimits(1000, 100000, 100000);
        var view = new ClusterControlView(config, List.of(), "ready", 0, 0);
        long units = ProgramEditorWire.decodeValue(ClusterControlWire.encodeView(view, generous), generous).storageUnits();
        var tight = new ArchiveLimits(1000, 100000, units);
        assertDoesNotThrow(() -> ClusterControlWire.encodeView(view, tight));
        assertEquals(Optional.of("too_large"), ClusterControllerMenu.preflight(config, List.of(), "ready", 0, 0, tight));
    }
    private static final ArchiveLimits LIMITS = new ArchiveLimits(32, 1_024, 4_096);

    @Test void enabledInternalConnectionValidatesTargetInputNotSourceAdvertisement() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var config = new ClusterConfiguration(Map.of(
            "source", new ClusterConfiguration.Member(a, BlockPos.ZERO),
            "target", new ClusterConfiguration.Member(b, new BlockPos(1, 0, 0))), Map.of(),
            Map.of("internal", new ClusterConfiguration.Connection("source", "send", "target", "messages", true)));
        var source = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(a), List.of(), true, true, "ready");
        var target = new ClusterControlView.Candidate(new BlockPos(1, 0, 0), Optional.of(b), List.of("messages"), true, true, "ready");
        assertTrue(ClusterControllerMenu.configurationFailure(config, List.of(source, target)).isEmpty());
        var changed = new ClusterControlView.Candidate(target.position(), Optional.of(b), List.of("other"), true, true, "ready");
        assertEquals(Optional.of("endpoint_changed"), ClusterControllerMenu.configurationFailure(config, List.of(source, changed)));
        var closed = new ClusterControlView.Candidate(target.position(), Optional.of(b), List.of(), true, true, "ready");
        assertEquals(Optional.of("closed"), ClusterControllerMenu.configurationFailure(config, List.of(source, closed)));
        var disabled = new ClusterConfiguration(config.members(), Map.of(),
            Map.of("internal", new ClusterConfiguration.Connection("source", "send", "target", "messages", false)));
        assertTrue(ClusterControllerMenu.configurationFailure(disabled, List.of(source, closed)).isEmpty());
        var denied = new ClusterControlView.Candidate(target.position(), Optional.of(b), List.of(), true, false, "access_denied");
        assertEquals(Optional.of("access_denied"), ClusterControllerMenu.configurationFailure(disabled, List.of(source, denied)));
    }

    @Test void connectionsParticipateInFullViewPreflightBudget() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var members = Map.of("source", new ClusterConfiguration.Member(a, BlockPos.ZERO),
            "target", new ClusterConfiguration.Member(b, new BlockPos(1, 0, 0)));
        var connections = new LinkedHashMap<String, ClusterConfiguration.Connection>();
        for (int i = 0; i < 20; i++) connections.put("connection" + i,
            new ClusterConfiguration.Connection("source", fixed("send" + i), "target", fixed("messages"), true));
        assertTrue(ClusterControllerMenu.preflight(new ClusterConfiguration(members, Map.of()), List.of(), "ready", 0, 0, LIMITS).isEmpty());
        assertEquals(Optional.of("too_large"), ClusterControllerMenu.preflight(
            new ClusterConfiguration(members, Map.of(), connections), List.of(), "ready", 0, 0, LIMITS));
    }

    @Test void responseMustMatchContainerNonceAndLatestIntentExactly() {
        UUID nonce = UUID.randomUUID();
        var exact = new ClusterControlResponse(4, nonce, 7, 2, "ready", "", LIMITS, new byte[0]);
        assertTrue(ClusterControllerMenu.acceptsResponse(4, nonce, 7, exact));
        assertFalse(ClusterControllerMenu.acceptsResponse(3, nonce, 7, exact));
        assertFalse(ClusterControllerMenu.acceptsResponse(4, UUID.randomUUID(), 7, exact));
        assertFalse(ClusterControllerMenu.acceptsResponse(4, nonce, 6, exact));
    }

    @Test void disabledRouteCanBeSavedWhileClosedEndpointStillRejectsEnabledRoute() {
        UUID identity=UUID.randomUUID();var member=new ClusterConfiguration.Member(identity,BlockPos.ZERO);
        var candidate=new ClusterControlView.Candidate(BlockPos.ZERO,Optional.of(identity),List.of(), true, true, "ready");
        var disabled=new ClusterConfiguration(Map.of("worker",member),Map.of("public",new ClusterConfiguration.Port("worker","closed","reply","outside",false)));
        assertTrue(ClusterControllerMenu.configurationFailure(disabled,List.of(candidate)).isEmpty());
        var enabled=new ClusterConfiguration(disabled.members(),Map.of("public",new ClusterConfiguration.Port("worker","closed","reply","outside",true)));
        assertEquals(Optional.of("closed"),ClusterControllerMenu.configurationFailure(enabled,List.of(candidate)));
    }

    @Test void configurationThatFitsAloneIsRejectedBeforeApplyWhenRequiredPublicViewCannotFit() {
        var members=new LinkedHashMap<String,ClusterConfiguration.Member>();var ports=new LinkedHashMap<String,ClusterConfiguration.Port>();
        var candidates=new ArrayList<ClusterControlView.Candidate>();
        for(int i=0;i<16;i++){
            String member=String.format("m%02d",i),port=String.format("p%02d",i),input=fixed("input"+i),output=fixed("output"+i),external=fixed("external"+i);
            UUID identity=UUID.randomUUID();BlockPos position=new BlockPos(i,2,3);members.put(member,new ClusterConfiguration.Member(identity,position));
            ports.put(port,new ClusterConfiguration.Port(member,input,output,external,true));
            candidates.add(new ClusterControlView.Candidate(position,Optional.of(identity),List.of(input), true, true, "ready"));
        }
        // Configured members alone now fit after wire pooling. Unconfigured geometric members
        // still belong in the public view and must be budgeted before applying the configuration.
        for (int i = 16; i < 80; i++) candidates.add(new ClusterControlView.Candidate(new BlockPos(i,2,3),
            Optional.of(new UUID(0, i)), List.of(fixed("unconfigured" + i)), true, true, "ready"));
        var config=new ClusterConfiguration(members,ports);var limits=new ArchiveLimits(4096,100_000,100_000);
        byte[] encoded=ClusterControlWire.encodeConfiguration(config,limits);assertTrue(encoded.length<ProgramEditorWire.MAX_BYTES);
        // These 80 highly repetitive names used to exceed the *raw* limit. They now really fit.
        var repetitive = new ClusterControlView(config,candidates,"ready",0,0);
        assertEquals(repetitive,ClusterControlWire.decodeView(ClusterControlWire.encodeView(repetitive,limits),limits));
        assertTrue(ClusterControllerMenu.preflight(config,candidates,"ready",0,0,limits).isEmpty());

        // A fitting configuration must still not be published if its required reply is too large
        // even after compression. Vary geometry/identities and use valid, non-repetitive endpoint names.
        var random = new Random(37);
        for (int i=80; i<336; i++) {
            var name = new StringBuilder();
            for (int n=0; n<64; n++) name.append((char)(0x4e00+random.nextInt(16000)));
            candidates.add(new ClusterControlView.Candidate(new BlockPos(i,2,3),Optional.of(new UUID(random.nextLong(),random.nextLong())),
                List.of(name.toString()),true,true,"ready"));
        }
        var envelope = new ArchiveLimits(100000,1000000,1000000);
        var error = assertThrows(IllegalArgumentException.class,()->ClusterControlWire.encodeView(new ClusterControlView(config,candidates,"ready",0,0),envelope));
        assertEquals("editor value exceeds editor wire capacity",error.getMessage());
        assertEquals(Optional.of("too_large"),ClusterControllerMenu.preflight(config,candidates,"ready",0,0,envelope));
    }
    private static String fixed(String prefix){return prefix+"x".repeat(64-prefix.length());}
}
