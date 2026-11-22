package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

class EndpointControlTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(100000,1000000,10000000);
    @Test void appendedActionsAndTypedConfigurationUseBoundedWire() {
        assertEquals(11, ComputerControlSession.Action.CLEAR_PERIOD.ordinal());
        assertEquals(12, ComputerControlSession.Action.CONFIGURE_ENDPOINT.ordinal());
        assertEquals(17, ComputerControlSession.Action.READ_COMMUNICATION.ordinal());
        var data = ProgramEditorWire.encodeValue(new EndpointConfiguration("A\t§b",true).value(), LIMITS);
        for (var action : ComputerControlSession.Action.values()) if (action.endpoint() && !action.wiring()) {
            var bytes = action == ComputerControlSession.Action.CONFIGURE_ENDPOINT ? data : new byte[0];
            var request = new ProgramControlRequest(1,UUID.randomUUID(),2,3,action,bytes);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try { request.write(wire); var decoded = new ProgramControlRequest(wire);
                assertEquals(action,decoded.action()); assertArrayEquals(bytes,decoded.inputs());
                if (bytes.length != 0) assertEquals(new EndpointConfiguration("A\t§b",true),
                    EndpointConfiguration.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(decoded.inputs(),LIMITS)));
            } finally { wire.release(); }
            if (bytes.length == 0) assertThrows(IllegalArgumentException.class,
                () -> new ProgramControlRequest(1,UUID.randomUUID(),1,1,action,data));
        }
    }
    @Test void endpointReplyNeverIncludesHugeRamOrCapturedInputsAndOversizeIsExplicit() {
        var draft = new ProgramDraft(); String node = draft.add(ProgramDraftTest.number(1),0,0); draft.output("out",node);
        var ram = new DataValue.RecordValue(Map.of("private",new DataValue.TextValue("x".repeat(40000))));
        var checkpoint = new MachineCheckpoint(ram,ComputationMachine.Status.IDLE,false,0,0,Optional.empty(),Optional.empty(),List.of());
        var snapshot = new ComputerRuntime.Snapshot(UUID.randomUUID(),draft.snapshot(),checkpoint,Optional.empty(),Optional.empty());
        var view = new DataValue.RecordValue(Map.of("endpoint",new EndpointConfiguration("A",true).value(),"endpoint_diagnostic",DataValue.NullValue.INSTANCE));
        var answer = new ComputerControlSession.Answer("ok",3,"",Optional.of(snapshot),Optional.empty(),Optional.of(view));
        var response = ProgramStatusResponse.fromAnswer(1,UUID.randomUUID(),1,answer,LIMITS);
        assertEquals("ok",response.code()); assertEquals(view,ProgramEditorWire.decodeValue(response.status(),LIMITS));
        var overflow = new ComputerControlSession.Answer("ok",3,"",Optional.empty(),Optional.empty(),Optional.of(ram));
        var failed = ProgramStatusResponse.fromAnswer(1,UUID.randomUUID(),2,overflow,LIMITS);
        assertEquals("too_large",failed.code()); assertEquals(0,failed.status().length);
    }
    @Test void discoveryLimitsHaveIndependentServerDefaults() {
        var builder = new ModConfigSpec.Builder();
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(builder); builder.build();
        assertEquals(new CommunicationNetworkLimits(2048,16),config.communication(false));
        assertEquals(8,config.peripherals(false).maxReaders());
        assertThrows(IllegalArgumentException.class,()->new CommunicationNetworkLimits(0,16));
        assertThrows(IllegalArgumentException.class,()->new CommunicationNetworkLimits(2048,0));
    }
}
