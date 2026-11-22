package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClusterWiringSourcesTest {
    @Test void directoryPreservesLiteralNamesPhysicalPositionAndRemovedScopesWithBoundedEncoding() {
        var source=new ClusterWiringSources(5,new BlockPos(-10,20,30),Map.of("io§c",true,"old",false));
        var limits=new ArchiveLimits(200,4000,10000);
        assertEquals(source,ClusterWiringSources.decode(ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(source.encode(),limits),limits)));
        assertThrows(IllegalArgumentException.class,()->ProgramEditorWire.encodeValue(source.encode(),new ArchiveLimits(1,1,1)));
        var fields=new LinkedHashMap<>(source.encode().fields()); fields.put("device",new DataValue.TextValue(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,()->ClusterWiringSources.decode(new DataValue.RecordValue(fields)));
        fields.remove("device");fields.put("position",new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(Long.MAX_VALUE),"y",new DataValue.IntegerValue(0),"z",new DataValue.IntegerValue(0))));
        assertThrows(IllegalArgumentException.class,()->ClusterWiringSources.decode(new DataValue.RecordValue(fields)));
    }
    @Test void appendedActionsKeepOldOrdinalsAndEnforceEmptyVersusValueRequests() {
        assertEquals(0,ClusterControlSession.Action.REFRESH.ordinal()); assertEquals(1,ClusterControlSession.Action.CONFIGURE.ordinal()); assertEquals(2,ClusterControlSession.Action.OPEN_COMPUTER.ordinal());
        int ordinal=3;
        for(String name:List.of("READ_WIRING","ADD_WIRING","REMOVE_WIRING","LIST_WIRING")){
            var action=ClusterControlSession.Action.valueOf(name);assertEquals(ordinal++,action.ordinal()); assertTrue(action.wiring());
            boolean value=action!=ClusterControlSession.Action.LIST_WIRING;
            assertDoesNotThrow(()->new ClusterControlRequest(1,UUID.randomUUID(),1,0,action,value?new byte[]{1}:new byte[0]));
            assertThrows(IllegalArgumentException.class,()->new ClusterControlRequest(1,UUID.randomUUID(),1,0,action,value?new byte[0]:new byte[]{1}));
        }
    }
}
