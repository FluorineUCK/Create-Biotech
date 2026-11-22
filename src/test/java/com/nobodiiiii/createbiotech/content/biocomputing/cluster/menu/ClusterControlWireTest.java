package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ClusterControlWireTest {
    @Test void compressedMenusKeepPacketAndArchiveLimitsAndCannotBeConfusedWithDocuments() {
        var limits = new ArchiveLimits(5000, 200000, 200000);
        var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
        var wires = new LinkedHashMap<String,XBusWire>();
        var candidates = new ArrayList<ClusterControlView.Candidate>();
        for (int i=0; i<29; i++) {
            var name = "worker"+i;
            var member = new ClusterConfiguration.Member(new UUID(i,~(long)i),new BlockPos(i,0,1));
            members.put(name,member);
            candidates.add(new ClusterControlView.Candidate(member.position(),Optional.of(member.identity()),List.of(name),true,true,"ready"));
            if (i>0) wires.put("bus"+i,new XBusWire(
                new XBusWire.Pin("worker"+(i-1),"worker"+(i-1),"x1"),
                new XBusWire.Pin(name,name,"x0"),true));
        }
        var config = new ClusterConfiguration(members,Map.of(),Map.of(),wires);
        var view = new ClusterControlView(config,candidates,"ready",0,0);
        var encoded = ClusterControlWire.encodeView(view,limits);
        assertEquals(0x42435601,java.nio.ByteBuffer.wrap(encoded).getInt());
        assertTrue(encoded.length <= ProgramEditorWire.MAX_BYTES);
        assertEquals(view,ClusterControlWire.decodeView(encoded,limits));
        assertThrows(IllegalArgumentException.class,()->ClusterControlWire.decodeConfiguration(encoded,limits));
        assertThrows(IllegalArgumentException.class,()->ProgramDocumentWire.decode(encoded,limits));
        assertThrows(IllegalArgumentException.class,()->ClusterControlWire.decodeView(encoded,new ArchiveLimits(1,1,1)));
        assertThrows(IllegalArgumentException.class,()->ClusterControlWire.decodeView(Arrays.copyOf(encoded,encoded.length+1),limits));
    }

    @Test void computerOpeningUsesBoundedExactPositionPayloadAndRoundTripsItsAction() {
        var position = new BlockPos(-20, 64, 30); var nonce = UUID.randomUUID();
        var data = ClusterControlWire.encodeComputerPosition(position, LIMITS);
        assertEquals(position, ClusterControlWire.decodeComputerPosition(data, LIMITS));
        var request = new ClusterControlRequest(3, nonce, 4, 5, ClusterControlSession.Action.OPEN_COMPUTER, data);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            request.write(buffer); var restored = new ClusterControlRequest(buffer);
            assertEquals(ClusterControlSession.Action.OPEN_COMPUTER, restored.action());
            assertEquals(nonce, restored.nonce()); assertEquals(position, ClusterControlWire.decodeComputerPosition(restored.value(), LIMITS));
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new ClusterControlRequest(3, nonce, 4, 5, ClusterControlSession.Action.OPEN_COMPUTER, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeComputerPosition(ClusterControlWire.encodeConfiguration(ClusterConfiguration.empty(), LIMITS), LIMITS));
    }
    @Test void geometricMembershipRoundTripsIndependentlyOfReadinessAndOldViewIsRejected() {
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(
            new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, "no_program")), "ready", 0, 0);
        var bytes = ClusterControlWire.encodeView(view, LIMITS);
        assertEquals(view, ClusterControlWire.decodeView(bytes, LIMITS));
        var root = (com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue)
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.decodeValue(bytes, LIMITS);
        var oldCandidate = new LinkedHashMap<>(((com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue)
            ((com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.ListValue)root.field("candidates")).values().getFirst()).fields());
        oldCandidate.remove("member");
        var oldRoot = new LinkedHashMap<>(root.fields());
        oldRoot.put("candidates", new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.ListValue(List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(oldCandidate))));
        byte[] oldBytes = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.encodeValue(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(oldRoot), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeView(oldBytes, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new ClusterControlView.Candidate(
            BlockPos.ZERO, Optional.of(UUID.randomUUID()), List.of(), false, true, "ready"));
    }
    private static final ArchiveLimits LIMITS = new ArchiveLimits(256, 8_192, 32_768);

    @Test void connectionConfigurationAndViewRoundTripsPreserveBothDirectionsAndDisabledFields() {
        UUID source = UUID.randomUUID(), target = UUID.randomUUID();
        var config = new ClusterConfiguration(Map.of(
            "source", new ClusterConfiguration.Member(source, BlockPos.ZERO),
            "target", new ClusterConfiguration.Member(target, new BlockPos(1, 0, 0))), Map.of(), Map.of(
            "forward", new ClusterConfiguration.Connection("source", "send", "target", "messages", true),
            "return", new ClusterConfiguration.Connection("target", "reply", "source", "closed_responses", false)));
        assertEquals(config, ClusterControlWire.decodeConfiguration(ClusterControlWire.encodeConfiguration(config, LIMITS), LIMITS));
        var view = new ClusterControlView(config, List.of(
            new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(source), List.of(), true, true, "ready"),
            new ClusterControlView.Candidate(new BlockPos(1, 0, 0), Optional.of(target), List.of("messages"), true, true, "ready")), "configured", 0, 0);
        assertEquals(view, ClusterControlWire.decodeView(ClusterControlWire.encodeView(view, LIMITS), LIMITS));
    }

    @Test void configurationWireWithoutConnectionsIsRejectedRatherThanSilentlyErasingExistingRoutes() {
        var legacy = new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of(
            "members", new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of()),
            "ports", new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of())));
        byte[] bytes = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.encodeValue(legacy, LIMITS);
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeConfiguration(bytes, LIMITS));
    }

    @Test void configurationRoundTripPreservesExplicitMemberIdentityAndAllRouteFields() {
        UUID identity = UUID.randomUUID();
        var config = new ClusterConfiguration(Map.of("worker", new ClusterConfiguration.Member(identity, new BlockPos(2, 3, 4))),
            Map.of("public", new ClusterConfiguration.Port("worker", "in", "reply", "outside", false)));
        assertEquals(config, ClusterControlWire.decodeConfiguration(ClusterControlWire.encodeConfiguration(config, LIMITS), LIMITS));
    }

    @Test void viewRoundTripCarriesOnlyConfigurationCandidatesDiagnosticsAndQueueCounts() {
        UUID identity = UUID.randomUUID();
        var config = new ClusterConfiguration(Map.of("worker", new ClusterConfiguration.Member(identity, new BlockPos(1, 2, 3))), Map.of());
        var view = new ClusterControlView(config, List.of(
            new ClusterControlView.Candidate(new BlockPos(1, 2, 3), Optional.of(identity), List.of("input"), true, true, "ready"),
            new ClusterControlView.Candidate(new BlockPos(7, 8, 9), Optional.empty(), List.of(), false, false, "not_contacting")),
            "busy", 3, 2);
        assertEquals(view, ClusterControlWire.decodeView(ClusterControlWire.encodeView(view, LIMITS), LIMITS));
    }

    @Test void packetPayloadsAreCopiedAndOversizedLengthsAreRejectedBeforeValueParsing() {
        UUID nonce = UUID.randomUUID(); byte[] data = {1, 2};
        var request = new ClusterControlRequest(3, nonce, 4, 5, ClusterControlSession.Action.CONFIGURE, data);
        data[0] = 9; assertArrayEquals(new byte[]{1, 2}, request.value());
        data[0] = 1;
        var response = new ClusterControlResponse(3, nonce, 4, 5, "ready", "", LIMITS, data);
        data[1] = 9; assertArrayEquals(new byte[]{1, 2}, response.state());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            request.write(wire); assertArrayEquals(new byte[]{1, 2}, new ClusterControlRequest(wire).value());
            wire.clear(); wire.writeVarInt(3); wire.writeUUID(nonce); wire.writeVarLong(4); wire.writeVarLong(5);
            wire.writeVarInt(ClusterControlSession.Action.CONFIGURE.ordinal()); wire.writeVarInt(ProgramEditorWire.MAX_BYTES + 1);
            assertThrows(RuntimeException.class, () -> new ClusterControlRequest(wire));
        } finally { wire.release(); }
    }

    @Test void malformedTrailingOrStructurallyInvalidValuesAreNeverAcceptedAsEmptyConfiguration() {
        var empty = ClusterControlWire.encodeConfiguration(ClusterConfiguration.empty(), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeConfiguration(Arrays.copyOf(empty, empty.length + 1), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeConfiguration(new byte[0], LIMITS));
    }

    @Test void responseRoundTripRetainsServerLimitsAndRejectsUnboundedOrUnsafeStatusCodes() {
        UUID nonce=UUID.randomUUID();var response=new ClusterControlResponse(2,nonce,3,4,"endpoint_changed","detail",LIMITS,new byte[]{4,5});
        var wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{response.write(wire);var copy=new ClusterControlResponse(wire);assertEquals(LIMITS,copy.archiveLimits());assertEquals("endpoint_changed",copy.code());assertArrayEquals(new byte[]{4,5},copy.state());}
        finally{wire.release();}
        assertThrows(IllegalArgumentException.class,()->new ClusterControlResponse(2,nonce,3,4,"Bad Code","",LIMITS,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new ClusterControlResponse(2,nonce,3,4,"x".repeat(33),"",LIMITS,new byte[0]));
    }
}
