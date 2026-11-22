package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Only software projection, not transport authorization or native player-path evidence. */
class LibraryReplyProjectionTest {
    @Test void projectsOnlySuccessfulTransportMetadataAndPreservesFailuresAndValues() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        var value=record("components",new DataValue.NbtValue(10,record("custom",new DataValue.NbtValue(8,new DataValue.TextValue("unchanged")))));
        var success=record("ok",true,"value",record("kind","library_reply","target",record("x",2,"y",0,"z",0),
            "request","already-correlated","sequence",71,"code","read_chunk","value",value));
        var failure=record("ok",false,"value",record("code","permission_denied","request","retained-failure","details",value));
        var context=record("r",record("o","status","d",null,"e","idle","l",false),"s",0,"d",null);
        var contextType=new DataType.RecordType(Map.of("r",NativeLibraryReadDocuments.ROWS.element(),"s",DataType.Scalar.ANY,"d",DataType.Scalar.ANY));
        // Exercise the editable program delivered to players, not just the Java fixture generator.
        ProgramDocument doc;
        try (var in = getClass().getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_segmented_compact_reply.bcp")) {
            assertNotNull(in);
            doc = ProgramDocumentWire.decode(Base64.getDecoder().decode(
                new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip()), limits.archive());
        }
        for(var response:List.of(success,failure)) {
            var caller=new ComputerBlockHost(()->{});var adapter=new ComputerBlockHost(()->{});var projection=new ComputerBlockHost(()->{});
            caller.installFlow(new FlowProgram(Map.of("request",contextType),List.of(new FlowProgram.Write("send","x0","request"),
                new FlowProgram.Read("result","x0","reply",NativeLibraryReadDocuments.OUTCOME))).withAssembly(),record("request",context),limits,true);
            adapter.installFlow(new FlowProgram(Map.of("response",NativeLibraryReadDocuments.OUTCOME),List.of(
                new FlowProgram.Read("receive","x0","received",contextType),new FlowProgram.Write("send","x0","response"))).withAssembly(),record("response",response),limits,true);
            projection.installProgram(PreparedProgram.prepare(doc,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            for(var host:List.of(caller,adapter,projection))host.setPaused(false,limits,true);
            for(int tick=0;tick<1000;tick++) {
                for(var host:List.of(caller,adapter,projection)){host.tick(limits,true);assertTrue(host.loaded(),()->host.diagnostic().toString());}
                caller.exchange("x0",limits,true,projection,"x0",limits,true);
                projection.exchange("x1",limits,true,adapter,"x0",limits,true);
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            var result=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,result.status());
            // Both generations of the public driver use this reply: legacy callers read sequence,
            // step_pump reads its short alias s. No target/request metadata is forwarded on success.
            var expected = response == success
                ? record("ok",true,"value",record("sequence",71,"s",71,"code","read_chunk","value",value))
                : failure;
            assertEquals(expected,result.locals().field("reply"));
            assertEquals(context,adapter.flowSnapshot().orElseThrow().state().locals().field("received"));
            assertEquals(response,adapter.flowSnapshot().orElseThrow().state().locals().field("response"));
        }
    }
}
