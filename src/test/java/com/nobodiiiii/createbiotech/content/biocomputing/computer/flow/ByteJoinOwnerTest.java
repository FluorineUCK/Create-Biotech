package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** The public read driver needs linear, resumable reassembly rather than repeated whole-list copies. */
class ByteJoinOwnerTest {
    static final DataType PARTS=new DataType.ListType(DataType.Scalar.NBT);
    static DataValue.NbtValue bytes(int... values) {
        return new DataValue.NbtValue(7,new DataValue.ListValue(Arrays.stream(values).mapToObj(v->(DataValue)new DataValue.IntegerValue(v)).toList()));
    }
    static FlowProgram program(int version) {
        var key=new ModuleDefinition.Key("join",1);
        var inputs=Map.of("parts",PARTS,"capacity",DataType.Scalar.INTEGER);
        return new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("parts","parts","capacity","capacity"),Map.of("value","joined"))),
            new FlowProgram.Procedure("join",key,inputs,Map.of("value",DataType.Scalar.NBT),Map.of("value","value"),FlowBlockRules.NONE,
                Optional.of(new AssemblySource(version,"REG value NBT\nBYTES.JOIN value parts capacity\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    record Run(FlowRuntime.Checkpoint state,int reloads) {}
    static Run run(DataValue.ListValue parts,int capacity,boolean reload) {
        var limits=StockApplicationDriverDocuments.hardware();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program(AssemblySource.VERSION),record("parts",parts,"capacity",capacity))));
        var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits.archive()),limits.archive());
        var host=new ComputerBlockHost(()->{});
        host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        host.setPaused(false,limits,true);int restored=0,joining=0;
        for(int tick=0;tick<5000;tick++) {
            host.tick(limits,true);var snapshot=host.flowSnapshot().orElseThrow();var state=snapshot.state();
            if(state.status()==FlowRuntime.Status.COMPLETE || state.status()==FlowRuntime.Status.FAILED)return new Run(state,restored);
            var build=state.assembly().orElseThrow().build();
            if(build.isPresent() && new DataValue.TextValue("bytes_join").equals(build.orElseThrow().value().fields().get("kind"))) {
                assertFalse(state.locals().fields().containsKey("joined"));joining++;
                if(reload && restored<2 && joining==(restored+1)*5) {
                    host.setPaused(true,limits,true);var saved=host.flowSnapshot().orElseThrow();
                    var cold=new ComputerBlockHost(()->{});cold.read(host.write());
                    cold.setPaused(true,limits,true);
                    assertEquals(saved,cold.flowSnapshot().orElseThrow());
                    cold.setPaused(false,limits,true);host=cold;restored++;
                }
            }
        }
        throw new AssertionError("Bounded byte join did not finish");
    }
    @Test void publicSegmentsJoinInOrderWithBilledRecoveryWithoutChangingTheirSource() {
        var expected=new ArrayList<DataValue>();var parts=new ArrayList<DataValue>();parts.add(bytes());
        for(int p=0;p<36;p++) {
            int[] next=new int[80];for(int i=0;i<80;i++) {next[i]=(byte)(p*80+i);expected.add(new DataValue.IntegerValue(next[i]));}
            parts.add(bytes(next));
        }
        var input=new DataValue.ListValue(parts);var normal=run(input,4096,false);var cold=run(input,4096,true);
        for(var result:List.of(normal,cold)) {
            assertEquals(FlowRuntime.Status.COMPLETE,result.state.status(),result.state.diagnostic().toString());
            assertEquals(new DataValue.NbtValue(7,new DataValue.ListValue(expected)),result.state.locals().field("joined"));
            assertEquals(input,result.state.locals().field("parts"));
        }
        assertEquals(2,cold.reloads);assertTrue(cold.state.work()>normal.state.work());
        assertTrue(normal.state.work()<expected.size()*5L+parts.size()*5L+100,"Joining must not repeatedly copy the growing prefix");
        var families=program(AssemblySource.VERSION).compile(StockApplicationDriverDocuments.hardware().hardware()).assembly().orElseThrow().instructionFamilies();
        assertTrue(families.contains(InstructionFamily.ISA_1A));assertFalse(families.contains(InstructionFamily.ISA_1B));
        assertThrows(IllegalArgumentException.class,()->program(13).validate(StockApplicationDriverDocuments.hardware().hardware()));
    }
    @Test void invalidSegmentsAndInsufficientCapacityNeverPublishPartialBytes() {
        for(var input:List.of(new DataValue.ListValue(List.of(bytes(1,2),bytes(3))),
                new DataValue.ListValue(List.of(bytes(1),new DataValue.NbtValue(8,new DataValue.TextValue("not bytes")))))) {
            var result=run(input,2,false).state;
            assertEquals(FlowRuntime.Status.FAILED,result.status());assertFalse(result.locals().fields().containsKey("joined"));
            assertEquals(input,result.locals().field("parts"));
        }
    }
    @Test void publishedLibraryDecoderUsesPublicJoinAndDecodeAndReturnsOriginalMetadata() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        var order=com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.snapshot(
            net.minecraft.nbt.TagParser.parseTag("{ordered_stacks:{entries:[{item_stack:{id:\"minecraft:paper\",count:2,components:{\"!minecraft:rarity\":{}}},count:7}]},ordered_crafts:[]}"),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(65536,limits.archive().maxValueUnits(),limits.archive()));
        var head=record("kind","native_order","sequence",1,"player",UUID.randomUUID().toString(),"address","alternate-supply","order",order);
        var scope=record("source",UUID.randomUUID().toString(),"device",UUID.randomUUID().toString(),"revision",7,
            "target",record("x",2,"y",0,"z",4),"address","alternate-supply");
        var value=new DataValue.ListValue(List.of(record("s",record("d",UUID.randomUUID().toString(),"h",head,"r",scope),"j",UUID.randomUUID().toString()+":1")));
        runPublished(value,"ok");
        runPublished(value,"decode_limit",4);
    }
    @Test void publishedDecoderReportsWorkspaceLimitWithoutTruncatingLargeComponents() throws Exception {
        var value=record("count",7,"components",new DataValue.NbtValue(10,record(
            "name",new DataValue.NbtValue(8,new DataValue.TextValue("original component ".repeat(100))),
            "bits",new DataValue.NbtValue(6,new DataValue.IntegerValue(0x7ff8000000000123L)))));
        // Encoded byte capacity is not a promise that the separate parser workspace can decode every fitting payload.
        // This unchanged large component reaches 8416 live parser units before completion, above the public 8192.
        // Refusal is the expected resource boundary, not permission to truncate the original component.
        runPublished(value,"decode_limit");
    }
    private static void runPublished(DataValue value,String code) throws Exception {
        runPublished(value,code,8192);
    }
    private static void runPublished(DataValue value,String code,int workspaceUnits) throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        var wireLimits=new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(8192,65536,limits.archive());
        var encoding=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialEncoding.begin(record("value",value),wireLimits);
        for(int work=0;!encoding.done();work++){assertTrue(work<1000000);encoding=encoding.advance();}
        var encoded=encoding.encoded();assertNull(encoded.error());
        var segments=new ArrayList<DataValue>();
        for(int at=0;at<encoded.size();at+=64) {
            int[] part=new int[Math.min(64,encoded.size()-at)];for(int i=0;i<part.length;i++)part[i]=(byte)encoded.byteAt(at+i);segments.add(bytes(part));
        }
        var version=record("medium",UUID.randomUUID().toString(),"revision",3);
        var request=record("p",new DataValue.ListValue(segments),"k","DATA","v",version,"s",19);
        var requestType=new DataType.RecordType(Map.of("p",PARTS,"k",DataType.Scalar.TEXT,"v",DataType.Scalar.ANY,"s",DataType.Scalar.ANY));
        var caller=new ComputerBlockHost(()->{});
        caller.installFlow(new FlowProgram(Map.of("request",requestType),List.of(new FlowProgram.Write("segments","x0","request"),
            new FlowProgram.Read("decoded","x0","reply",NativeLibraryReadDocuments.OUTCOME))).withAssembly(),record("request",request),limits,true);
        var decoder=new ComputerBlockHost(()->{});
        var authored=LibrarySegmentedWriteDocuments.build("decode");
        if(workspaceUnits!=8192) {
            var body=authored.flow().orElseThrow();var initial=new TreeMap<>(body.initial().fields());initial.put("workspace",new DataValue.IntegerValue(workspaceUnits));
            var draft=new ProgramWorkspace(authored);draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(initial),body.canvas(),body.inline(),body.execution())));
            authored=draft.snapshot();
        }
        var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(authored,limits.archive()),limits.archive());
        decoder.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        caller.setPaused(false,limits,true);decoder.setPaused(false,limits,true);
        var isa=document.flow().orElseThrow().program(document.archive()).compile(limits.hardware()).assembly().orElseThrow();
        var exchange=XBusExchange.Status.NOT_READY;int decoding=0,restored=0;
        for(int tick=0;tick<5000;tick++) {
            var before=decoder.flowSnapshot().orElseThrow();
            caller.tick(limits,true);decoder.tick(limits,true);exchange=caller.exchange("x0",limits,true,decoder,"x0",limits,true);
            var root=decoder.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED,root.status(),()->root.diagnostic()+" "+((DataValue.RecordValue)root.locals().field("result")).fields()+" previous="+before.tasks().contexts().stream().map(t->t.controlUnits()+"/"+t.assembly().map(a->a.pc()+"/"+isa.code().get(a.pc()).instruction()+"/"+a.build().map(b->b.ownedUnits()+"/"+b.paidWork())).orElse("none")).toList()+" limit="+limits.hardware().memoryUnits());
            for(var task:decoder.flowSnapshot().orElseThrow().tasks().contexts())assertNotEquals(FlowRuntime.Status.FAILED,task.status(),task.diagnostic().toString());
            if(code.equals("ok") && restored<2) {
                boolean active=decoder.flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(t->t.assembly().flatMap(a->a.build()).filter(b->
                    new DataValue.TextValue("value_decode").equals(b.value().fields().get("kind")) && new DataValue.IntegerValue(2).equals(b.value().fields().get("version"))).isPresent());
                if(active && ++decoding==(restored+1)*5) {decoder=NativeLibraryOwnersTest.reload(decoder,limits,null);restored++;}
            }
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
        }
        var state=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,state.status(),"exchange="+exchange+" owner="+decoder.diagnostic()+" tasks="+decoder.flowSnapshot().orElseThrow().tasks().contexts().stream().map(t->t.status()+"/"+t.assembly().map(a->a.pc()+"/"+a.build().map(b->EditorValueFormat.format(b.value()))).orElse("none")+"/"+t.diagnostic()).toList());
        var expected=code.equals("ok")?record("ok",true,"value",record("sequence",19,"value",record("kind","DATA","version",version,"value",value)))
            :record("ok",false,"value",record("code",code,"value",DataValue.NullValue.INSTANCE));
        assertEquals(expected,state.locals().field("reply"),()->EditorValueFormat.format(state.locals().field("reply")));

        assertEquals(request,state.locals().field("request"));
        if(code.equals("ok"))assertEquals(2,restored);
    }
}
