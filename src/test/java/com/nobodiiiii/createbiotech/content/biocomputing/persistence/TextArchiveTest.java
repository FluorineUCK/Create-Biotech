package com.nobodiiiii.createbiotech.content.biocomputing.persistence;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
class TextArchiveTest {
    @Test void realHostNbtReloadPreservesNonzeroTextProgressAndResumesWithoutObservationWork(){
        var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(10000,1000000,1),new ArchiveLimits(100000,1000000,10000000)));
        var archive=TextProgramFixtures.archive(TextOperation.CONCAT,Map.of(
            "left",new DataValue.TextValue("😀A"),"right",new DataValue.TextValue("中!")));
        var host=new ComputerBlockHost(()->{});
        host.install(archive,ComputerRuntime.MemoryPolicy.RESET,limits,true);
        host.begin(new DataValue.RecordValue(Map.of()),limits,true);
        MachineCheckpoint.TextState progressed=null;
        for(int guard=0;guard<100;guard++){
            var machine=host.snapshot().orElseThrow().machine();
            assertEquals(ComputationMachine.Status.RUNNING,machine.status());
            var textState=machine.frames().stream().filter(MachineCheckpoint.TextState.class::isInstance)
                .map(MachineCheckpoint.TextState.class::cast).findFirst();
            if(textState.isPresent()){
                var progress=textState.orElseThrow().progress();
                if(((DataValue.IntegerValue)progress.field("offset")).value()>0
                    &&!((DataValue.TextValue)progress.field("buffer")).value().isEmpty()){
                    progressed=textState.orElseThrow();break;
                }
            }
            host.tick(limits,true);
        }
        assertNotNull(progressed,"must save after actual TextTask advancement, not merely frame creation");
        assertEquals(new DataValue.IntegerValue(2),progressed.progress().field("offset"));
        assertEquals(new DataValue.TextValue("😀"),progressed.progress().field("buffer"));
        var before=host.snapshot().orElseThrow();var saved=host.write();assertNotNull(saved);
        var restored=new ComputerBlockHost(()->{});restored.read(saved);
        assertTrue(restored.snapshot().isEmpty(),"read must not restore or execute before authorized observation");
        var observed=restored.observe(limits,true);
        assertEquals(before,observed);
        assertEquals(before.machine().workUnits(),observed.machine().workUnits());
        var restoredText=observed.machine().frames().stream().filter(MachineCheckpoint.TextState.class::isInstance)
            .map(MachineCheckpoint.TextState.class::cast).findFirst().orElseThrow();
        assertEquals(progressed.progress(),restoredText.progress());
        assertEquals(observed,restored.observe(limits,true),"repeated observation is also inert");
        for(int guard=0;restored.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;guard++){
            assertTrue(guard<100,"resumed computation must finish in bounded ticks");restored.tick(limits,true);
        }
        var result=restored.snapshot().orElseThrow().machine();
        assertEquals(ComputationMachine.Status.SUCCEEDED,result.status());
        assertEquals(new DataValue.TextValue("😀A中!"),result.output().orElseThrow().field("result"));
        assertTrue(result.diagnostic().isEmpty());assertTrue(restored.diagnostic().isEmpty());
        assertTrue(result.workUnits()>before.machine().workUnits());
    }
    @Test void allTextOperationsRoundTripAtCurrentProgramVersion(){
        for(var example:TextProgramFixtures.examples()){
            var archive=TextProgramFixtures.archive(example.operation(),example.arguments());var encoded=ProgramArchiveCodec.encode(archive);
            assertEquals(new DataValue.IntegerValue(example.operation()==TextOperation.LETTER_OF?28:12),encoded.field("version"));assertEquals(archive,ProgramArchiveCodec.decode(encoded));
        }
    }
    @Test void oldRootModuleAndEntryGatesHaveIndependentLegalControls(){
        for(int version=1;version<8;version++){
            int v=version;
            assertDoesNotThrow(()->ProgramArchiveCodec.decode(fixture(v,plain(),obj(),empty())));
            rejects(fixture(version,textGraph(),obj(),empty()));
            var module=obj("key",obj("id","container","revision",1),"graph",textGraph());
            assertDoesNotThrow(()->ProgramArchiveCodec.decode(fixture(8,plain(),obj(),new DataValue.ListValue(List.of(module)))));
            assertDoesNotThrow(()->ProgramArchiveCodec.decode(fixture(v,plain(),obj(),new DataValue.ListValue(List.of(obj("key",obj("id","container","revision",1),"graph",plain()))))));
            rejects(fixture(version,plain(),obj(),new DataValue.ListValue(List.of(module))));
            if(version>=3){
                var entry=obj("nested",obj("graph",textGraph(),"writes",empty(),"sends",empty()));
                assertDoesNotThrow(()->ProgramArchiveCodec.decode(fixture(v,plain(),obj("nested",obj("graph",plain(),"writes",empty(),"sends",empty())),empty())));
                assertDoesNotThrow(()->ProgramArchiveCodec.decode(fixture(8,plain(),entry,empty())));
                rejects(fixture(version,plain(),entry,empty()));
            }
        }
    }
    @Test void outerComputerGateIsIndependentOfNestedProgramEightAndCheckpointRoundTrips(){
        var limits=new ArchiveLimits(100000,1000000,10000000);
        var e=TextProgramFixtures.examples().getFirst();
        var runtime=new ComputerRuntime(UUID.randomUUID(),TextProgramFixtures.archive(e.operation(),e.arguments()),new ResourceLimits(10000,1000000,1));
        runtime.begin(new DataValue.RecordValue(Map.of()));
        for(int i=0;runtime.snapshot().machine().frames().stream().noneMatch(MachineCheckpoint.TextState.class::isInstance);i++){assertTrue(i<20);runtime.step();}
        var encoded=ComputerNbtCodec.encode(runtime.snapshot(),limits);
        assertEquals(runtime.snapshot(),ComputerNbtCodec.decode(encoded,limits));
        var data=(DataValue.RecordValue)ValueNbtCodec.decode(encoded,limits);
        assertEquals(new DataValue.IntegerValue(10),data.field("version"));
        for(int version=1;version<8;version++){
            var fields=new LinkedHashMap<>(data.fields());fields.put("version",new DataValue.IntegerValue(version));
            var error=assertThrows(ArchiveException.class,()->ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),limits),limits));
            assertEquals("Text frames are unavailable in this archive schema",error.getMessage());
            var machine=new LinkedHashMap<>(((DataValue.RecordValue)fields.get("machine")).fields());
            var frames=new ArrayList<>(((DataValue.ListValue)machine.get("frames")).values());frames.removeLast();
            machine.put("frames",new DataValue.ListValue(frames));fields.put("machine",new DataValue.RecordValue(machine));
            assertDoesNotThrow(()->ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),limits),limits));
        }
    }
    private static void rejects(DataValue value){var e=assertThrows(ArchiveException.class,()->ProgramArchiveCodec.decode(value));assertEquals("Text nodes are unavailable in this archive schema",e.getMessage());}
    private static DataValue.RecordValue fixture(int version,DataValue graph,DataValue entries,DataValue modules){
        var fields=new LinkedHashMap<>(obj("version",version,"graph",graph,"cells",obj(),"writes",empty(),"modules",modules).fields());
        if(version>=2)fields.put("sends",empty());if(version>=3)fields.put("entries",entries);return new DataValue.RecordValue(fields);
    }
    private static DataValue.RecordValue plain(){return obj("inputs",obj(),"nodes",obj("value",obj("kind","constant","value",0)),"outputs",obj("value","value"));}
    private static DataValue.RecordValue textGraph(){return obj("inputs",obj(),"nodes",obj("source",obj("kind","constant","value","A😀中"),"result",obj("kind","text","operation","LENGTH","arguments",obj("text","source"))),"outputs",obj("result","result"));}
    private static DataValue.ListValue empty(){return new DataValue.ListValue(List.of());}
}
