package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Authoring only. Installed devices execute the same editable public assembly as players. */
public final class LibrarySegmentedWriteDocuments {
    private LibrarySegmentedWriteDocuments() {}
    @org.junit.jupiter.api.Test void fragmentParameterAccountsForTheWholeTypedFrame() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        int published=Math.toIntExact(((DataValue.IntegerValue)build("pump").flow().orElseThrow().initial().field("chunk_bytes")).value());
        for(int count:List.of(published,96)) {
            var bytes=new DataValue.NbtValue(7,new DataValue.ListValue(Collections.nCopies(count,new DataValue.IntegerValue(127))));
            var frame=record("kind","library_command","target",record("x",Integer.MAX_VALUE,"y",Integer.MAX_VALUE,"z",Integer.MAX_VALUE),
                "request","00000000-0000-0000-0000-000000000000:"+Long.MAX_VALUE,"operation","write_chunk","sequence",Long.MAX_VALUE,
                "data",record("offset",Long.MAX_VALUE,"bytes",bytes));
            int size=encodedSize(frame,limits.archive());
            System.out.println("SEGMENTED_FRAME raw="+count+" encoded="+size);
            if(count==published)org.junit.jupiter.api.Assertions.assertTrue(size<=4096,"The published fragment must fit the complete hardware frame");
            if(count==96)org.junit.jupiter.api.Assertions.assertTrue(size>4096,"Raw fragment size alone cannot stand in for the encoded frame budget");
        }
        int readCount=Math.toIntExact(((DataValue.IntegerValue)build("read_stream").flow().orElseThrow().initial().field("chunk_bytes")).value());
        var distinct=new ArrayList<DataValue>();for(int i=0;i<readCount;i++)distinct.add(new DataValue.IntegerValue((byte)(i-128)));
        var readReply=record("kind","library_reply","target",record("x",Integer.MAX_VALUE,"y",Integer.MAX_VALUE-1,"z",Integer.MAX_VALUE-2),
            "request","00000000-0000-0000-0000-000000000000:"+Long.MAX_VALUE,"code","read_chunk","sequence",Long.MAX_VALUE-3,
            "value",record("offset",Long.MAX_VALUE-5,"total",Long.MAX_VALUE-4,"eof",false,"bytes",new DataValue.NbtValue(7,new DataValue.ListValue(distinct))));
        // Library uses SerialDevice.prepareReply / SerialRecordCodec, not the public CPU encoding path.
        int readSize=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(readReply,
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(65536,1048576,limits.archive())).length;
        System.out.println("SEGMENTED_READ_FRAME raw="+readCount+" encoded="+readSize);
        org.junit.jupiter.api.Assertions.assertTrue(readSize<=4096,"The published range must fit the complete device reply with distinct bytes");
    }
    public static int encodedSize(DataValue.RecordValue frame,com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits archive) {
        var encoder=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialEncoding.begin(frame,
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(65536,1048576,archive));
        for(int work=0;!encoder.done();work++) {
            org.junit.jupiter.api.Assertions.assertTrue(work<1000000,"Encoding must finish within the fixture bound");
            encoder=encoder.advance();
        }
        org.junit.jupiter.api.Assertions.assertNull(encoder.encoded().error());
        return encoder.encoded().size();
    }
    @org.junit.jupiter.api.Test void publishedDriversFitDefaultHardwareAndKeepEditableSources() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        for(var role:List.of("store","coordinator","buffer","pump","adapter","decode",
                "read_coordinator","read_decode","read_collect","read_verify","read_stream","read_adapter",
                "compact_reply","archive_store","archive_coordinator","archive_read_decode","archive_read_collect",
                "session_read","session_coordinator","session_store","session_buffer","session_slice","session_read_decode","step_pump","step_slice",
                "step_read_stream","step_read_verify","range_reply")) {
            var document=build(role);
            System.out.println("SEGMENTED_PUBLIC "+role+" "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document,limits);
            String name="library_segmented_"+role;
            var doc=Path.of("examples/biocomputing",name+".bcp");
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name+".bcp");
            var source=document.flow().orElseThrow().program(document.archive()).steps().stream()
                .filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast)
                .findFirst().orElseThrow().assembly().orElseThrow().text();
            var generate = System.getenv("GENERATE_LIBRARY_SEGMENTED_WRITE");
            if("1".equals(generate) || role.equals(generate)) {
                String encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
                Files.writeString(doc,encoded);Files.writeString(asset,encoded);
                if(Set.of("adapter","coordinator","read_adapter").contains(role))Files.writeString(doc.resolveSibling(name+".asm"),source);
            }
            org.junit.jupiter.api.Assertions.assertEquals(Files.readString(doc),Files.readString(asset));
            org.junit.jupiter.api.Assertions.assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            org.junit.jupiter.api.Assertions.assertEquals(source,Files.readString(doc.resolveSibling(name+".asm")));
        }
    }
    public static ProgramDocument build(String role) throws Exception {
        if(role.equals("session_read_decode"))return managed(Map.of("capacity",DataType.Scalar.INTEGER,"workspace",DataType.Scalar.INTEGER),
            record("capacity",8192,"workspace",12288),Files.readString(Path.of("examples/biocomputing/library_segmented_session_read_decode.asm")),14);
        if(Set.of("session_buffer","session_slice").contains(role))return managed(Map.of(),record(),
            Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm")),14);
        if(role.equals("session_store")) {
            var original=build("archive_store");var body=original.flow().orElseThrow();
            var old=body.program(original.archive()).steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow();
            var inputs=new LinkedHashMap<>(old.inputs());inputs.put("c",NativeLibraryReadDocuments.ROWS);
            var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));var key=new ModuleDefinition.Key("i",1);
            var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(args,old.returns())),
                new FlowProgram.Procedure("implementation",key,inputs,old.outputs(),old.returns(),old.rules(),
                    Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/library_segmented_session_store.asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
            var initial=new LinkedHashMap<>(body.initial().fields());initial.put("c",build("archive_coordinator").flow().orElseThrow().initial().field("commands"));
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(initial))));return workspace.snapshot();
        }
        if(role.equals("session_coordinator"))return coordinatedTasks(
            Files.readString(Path.of("examples/biocomputing/library_segmented_session_coordinator.asm")));
        if(role.equals("session_read")) {
            var inputs=Map.<String,DataType>of("c",NativeLibraryReadDocuments.ROWS);var outputs=Map.<String,DataType>of("result",NativeLibraryReadDocuments.OUTCOME);
            var key=new ModuleDefinition.Key("i",1);var returns=Map.of("result","result");
            var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(Map.of("c","c"),returns)),
                new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,
                    Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/library_segmented_session_read.asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
            var initial=record("c",build("read_coordinator").flow().orElseThrow().initial().field("commands"));
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));return workspace.snapshot();
        }
        if(Set.of("compact_reply","range_reply").contains(role))return managed(Map.of(),record(),
            Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm")),14);
        if(Set.of("step_pump","step_slice","step_read_stream","step_read_verify").contains(role)) {
            boolean read=role.equals("step_read_stream"), verify=role.equals("step_read_verify"), pump=!role.equals("step_slice")&&!verify;
            var state=new DataType.RecordType(pump?Map.of("n",DataType.Scalar.INTEGER,"o",DataType.Scalar.INTEGER,"q",DataType.Scalar.ANY)
                :verify?Map.of("n",DataType.Scalar.INTEGER,"o",DataType.Scalar.INTEGER):Map.of("n",DataType.Scalar.INTEGER,"b",DataType.Scalar.NBT));
            var inputs=pump?Map.<String,DataType>of("s",state,"c",DataType.Scalar.INTEGER):Map.<String,DataType>of("s",state);
            var outputs=state.fields();var key=new ModuleDefinition.Key("i",1);
            var program=new FlowProgram(inputs,List.of(new FlowProgram.Forever("service"),
                new FlowProgram.TaskStart("command",key,pump?Map.of("s","s","c","c"):Map.of("s","s"),"task"),
                new FlowProgram.TaskJoin("claim",key,"task","outcome"),
                new FlowProgram.Evaluate("continuation","continuation",new FlowCallBindings(Map.of("result","outcome"),Map.of("s","s"))),
                new FlowProgram.EndForever("service_end"),
                new FlowProgram.Procedure("implementation",key,inputs,outputs,pump?Map.of("n","total","o","offset","q",read?"seq":"sequence"):verify?Map.of("n","total","o","offset"):Map.of("n","phase","b","bytes"),FlowBlockRules.NONE,
                    Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm"))))),
                new FlowProgram.EndProcedure("end"))).withAssembly();
            var graph=new ComputationGraph(Map.of("result",com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.type(outputs)),Map.of(
                "result",new ComputationGraph.Input("result"),"value",new ComputationGraph.Field("result","value"),
                "required",new ComputationGraph.Scalar(ScalarOperation.REQUIRE_VALUE,Map.of("value","value"))),Map.of("s","required"));
            var body=FlowDocumentBody.fromProgram(program,pump?record("s",record("n",-1,"o",0,"q",0),"c",80)
                :verify?record("s",record("n",-1,"o",0)):record("s",record("n",0,"b",new DataValue.NbtValue(7,new DataValue.ListValue(List.of())))));
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),body.initial(),body.canvas(),Map.of("continuation",graph),body.execution(),FlowProgram.Compilation.COMPACT_V1)));return workspace.snapshot();
        }
        if(role.startsWith("archive_")) {
            String source=Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm"));
            if(role.equals("archive_store")) {
                var original=build("store");var body=original.flow().orElseThrow();var steps=new ArrayList<FlowProgram.Step>();
                for(var step:body.program(original.archive()).steps()) {
                    if(step instanceof FlowProgram.Procedure p)steps.add(new FlowProgram.Procedure(p.sourceId(),p.key(),p.inputs(),p.outputs(),p.returns(),p.rules(),Optional.of(new AssemblySource(source))));
                    else steps.add(step);
                }
                var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(body.inputs(),steps).withAssembly(),body.initial())));return workspace.snapshot();
            }
            if(role.equals("archive_coordinator")) {
                var old=NativeLibraryWriteDocuments.build("a").flow().orElseThrow().initial();
                var rows=new ArrayList<DataValue>(((DataValue.ListValue)old.field("commands")).values().subList(0,13));
                rows.add(NativeLibraryReadDocuments.row("read_begin","reading","accepted",false));
                rows.add(NativeLibraryReadDocuments.row("read_info",null,"read_ready",false));
                rows.add(NativeLibraryReadDocuments.row("download",null,"download",false));
                rows.add(NativeLibraryReadDocuments.row("read_close",null,"acknowledged",true));
                return signalOwner(Map.of("commands",NativeLibraryReadDocuments.ROWS),record("commands",new DataValue.ListValue(rows)),source);
            }
            var inputs=Map.<String,DataType>of();var values=record();
            if(role.equals("archive_read_decode")){inputs=Map.of("capacity",DataType.Scalar.INTEGER,"workspace",DataType.Scalar.INTEGER);values=record("capacity",8192,"workspace",8192);}
            return role.equals("archive_read_decode")?signalOwner(inputs,values,source):managed(inputs,values,source,14);
        }
        if(role.startsWith("read_")) {
            if(role.equals("read_adapter")) {
                var old=build("adapter");var body=old.flow().orElseThrow();var steps=new ArrayList<FlowProgram.Step>();
                for(var step:body.program(old.archive()).steps()) {
                    if(step instanceof FlowProgram.Procedure p) {
                        String text=p.assembly().orElseThrow().text().replace("REG status TEXT","REG status TEXT\nREG info TEXT")
                            .replace("CONST status \"status\"","CONST status \"status\"\nCONST info \"read_info\"")
                            .replace("VALUE.EQUALS flag op status\nJFALSE flag reply\nwait_busy:","VALUE.EQUALS flag op status\nJFALSE flag check_info\nJMP wait_busy\ncheck_info:\nVALUE.EQUALS flag op info\nJFALSE flag reply\nwait_busy:");
                        steps.add(new FlowProgram.Procedure(p.sourceId(),p.key(),p.inputs(),p.outputs(),p.returns(),p.rules(),Optional.of(new AssemblySource(text))));
                    }else steps.add(step);
                }
                var next=FlowDocumentBody.fromProgram(new FlowProgram(body.inputs(),steps).withAssembly(),body.initial());
                var w=new ProgramWorkspace();w.setFlow(Optional.of(new FlowDocumentBody(next.inputs(),next.steps(),next.initial(),next.canvas(),body.inline(),next.execution())));return w.snapshot();
            }
            String source=Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm"));
            if(role.equals("read_coordinator")) {
                var inputs=Map.<String,DataType>of("commands",NativeLibraryReadDocuments.ROWS);
                var rows=new ArrayList<DataValue>();
                var original=(DataValue.ListValue)NativeLibraryWriteDocuments.build("a").flow().orElseThrow().initial().field("commands");
                rows.addAll(original.values().subList(0,7));
                rows.add(NativeLibraryReadDocuments.row("read_begin","reading","accepted",false));
                rows.add(NativeLibraryReadDocuments.row("read_info",null,"read_ready",false));
                rows.add(NativeLibraryReadDocuments.row("download",null,"download",false));
                rows.add(NativeLibraryReadDocuments.row("read_close",null,"acknowledged",true));
                var key=new ModuleDefinition.Key("i",1);var outputs=Map.<String,DataType>of("result",NativeLibraryReadDocuments.OUTCOME);
                var returns=Map.of("result","result");
                var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("read",key,new FlowCallBindings(Map.of("commands","commands"),returns)),
                    new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("end"))).withAssembly();
                var w=new ProgramWorkspace();w.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,record("commands",new DataValue.ListValue(rows)))));return w.snapshot();
            }
            var inputs=new TreeMap<String,DataType>();var values=new TreeMap<String,DataValue>();
            if(role.equals("read_decode")){inputs.put("capacity",DataType.Scalar.INTEGER);inputs.put("workspace",DataType.Scalar.INTEGER);values.putAll(record("capacity",8192,"workspace",8192).fields());}
            if(role.equals("read_stream")){inputs.put("chunk_bytes",DataType.Scalar.INTEGER);values.putAll(record("chunk_bytes",80).fields());}
            return managed(inputs,new DataValue.RecordValue(values),source,14);
        }
        if(role.equals("coordinator")) {
            var original=NativeClusterJournalDocuments.build(role);var body=original.flow().orElseThrow();var steps=new ArrayList<FlowProgram.Step>();
            for(var step:body.program(original.archive()).steps()) {
                if(step instanceof FlowProgram.Procedure p) {
                    String source=p.assembly().orElseThrow().text().replace("TYPE mutation_type RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY",
                        "TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER\nTYPE mutation_type RECORD h Header b NBT");
                    steps.add(new FlowProgram.Procedure(p.sourceId(),p.key(),p.inputs(),p.outputs(),p.returns(),p.rules(),Optional.of(new AssemblySource(source))));
                }else steps.add(step);
            }
            var updated=FlowDocumentBody.fromProgram(new FlowProgram(body.inputs(),steps).withAssembly(),body.initial());
            updated=new FlowDocumentBody(updated.inputs(),updated.steps(),updated.initial(),updated.canvas(),body.inline(),updated.execution());
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(updated));return workspace.snapshot();
        }
        if(role.equals("decode")) {
            return managed(Map.of("capacity",DataType.Scalar.INTEGER,"workspace",DataType.Scalar.INTEGER),
                record("capacity",8192,"workspace",8192),Files.readString(Path.of("examples/biocomputing/library_segmented_decode.asm")),14);
        }

        if(role.equals("adapter")) {
            var original=NativeClusterJournalDocuments.build("adapter"); var body=original.flow().orElseThrow();
            var procedure=body.program(original.archive()).steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow();
            String source=procedure.assembly().orElseThrow().text().replace("REG busy TEXT", "REG busy TEXT\nREG status TEXT")
                .replace("CONST busy \"busy\"", "CONST busy \"busy\"\nCONST status \"status\"")
                .replace("VALUE.EQUALS flag op result\nJFALSE flag reply", "VALUE.EQUALS flag op result\nJFALSE flag check_status\nJMP wait_busy\ncheck_status:\nVALUE.EQUALS flag op status\nJFALSE flag reply\nwait_busy:");
            var inputs=Map.<String,DataType>of("target",NativeLibraryReadDocuments.TARGET); var key=new ModuleDefinition.Key("i",1);
            var program=new FlowProgram(inputs,List.of(new FlowProgram.Forever("service"),
                new FlowProgram.TaskStart("transaction",key,Map.of("target","target"),"task"),
                new FlowProgram.TaskJoin("collect",key,"task","result"),
                new FlowProgram.Evaluate("require_result","require_result",new FlowCallBindings(Map.of("result","result"),Map.of())),
                new FlowProgram.EndForever("service_end"),
                new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
                new FlowProgram.EndProcedure("end"))).withAssembly();
            var updated=FlowDocumentBody.fromProgram(program,record("target",body.initial().field("target")));
            updated=new FlowDocumentBody(updated.inputs(),updated.steps(),updated.initial(),updated.canvas(),Map.of("require_result",body.inline().get("require_result")),updated.execution());
            var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(updated));return workspace.snapshot();
        }
        if(!Set.of("store","buffer","pump").contains(role))throw new IllegalArgumentException(role);
        var inputs=new LinkedHashMap<String,DataType>();var initial=new LinkedHashMap<String,DataValue>();
        if(role.equals("store")){inputs.put("volume",DataType.Scalar.TEXT);inputs.put("entry",DataType.Scalar.TEXT);inputs.put("capacity",DataType.Scalar.INTEGER);initial.putAll(record("volume","plant","entry","reading","capacity",8192).fields());}
        if(role.equals("pump")){inputs.put("chunk_bytes",DataType.Scalar.INTEGER);initial.putAll(record("chunk_bytes",80).fields());}
        var arguments=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->arguments.put(n,n));
        var outputs=role.equals("store")?Map.<String,DataType>of("result",NativeLibraryReadDocuments.OUTCOME):Map.<String,DataType>of();
        var returns=role.equals("store")?Map.of("result","result"):Map.<String,String>of();
        var key=new ModuleDefinition.Key(role.equals("pump")?"i":"segmented_"+role,1);
        var steps=new ArrayList<FlowProgram.Step>();
        if(role.equals("pump"))steps.addAll(List.of(new FlowProgram.Forever("service"),
            new FlowProgram.TaskStart("transaction",key,arguments,"task"),new FlowProgram.TaskJoin("collect",key,"task","result"),
            new FlowProgram.Evaluate("require_result","require_result",new FlowCallBindings(Map.of("result","result"),Map.of())),
            new FlowProgram.EndForever("service_end")));
        else steps.add(new FlowProgram.Invoke("start",key,new FlowCallBindings(arguments,returns)));
        steps.add(new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/library_segmented_"+role+".asm"))))));
        steps.add(new FlowProgram.EndProcedure("end"));
        var program=new FlowProgram(inputs,steps).withAssembly();
        var body=FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(initial));
        if(role.equals("pump"))body=new FlowDocumentBody(body.inputs(),body.steps(),body.initial(),body.canvas(),
            Map.of("require_result",NativeClusterJournalDocuments.build("adapter").flow().orElseThrow().inline().get("require_result")),body.execution());
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(body));return workspace.snapshot();
    }
    /** Public program composition: root owns the output, supervisor claims finite communications.
     * The selector is this Computer's ordinary public variable, not another task's private RAM. */
    private static ProgramDocument coordinatedTasks(String source) throws Exception {
        var worker = new ModuleDefinition.Key("i", 1);
        var supervisor = new ModuleDefinition.Key("s", 1);
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
                Map.of("selector", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
        var program = new FlowProgram(Map.of("signal_ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Reference("address", FlowProgram.ReferenceKind.ADDRESS, "selector", "cell", Optional.empty()),
            new FlowProgram.TaskStart("supervise", supervisor, Map.of(), "supervisor"),
            new FlowProgram.Forever("monitor"),
            new FlowProgram.Reference("sample", FlowProgram.ReferenceKind.READ, "cell", "level", Optional.empty()),
            new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.Sleep("yield", "signal_ticks"),
            new FlowProgram.EndForever("monitor_end"),
            new FlowProgram.Procedure("worker", worker, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
                Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("worker_end"),
            new FlowProgram.Procedure("supervisor", supervisor, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Forever("service"),
            new FlowProgram.TaskStart("transaction", worker, Map.of(), "task"),
            new FlowProgram.TaskJoin("collect", worker, "task", "result"),
            new FlowProgram.Evaluate("require_result", "require_result", new FlowCallBindings(Map.of("result", "result"), Map.of())),
            new FlowProgram.EndForever("service_end"),
            new FlowProgram.EndProcedure("supervisor_end")), archive).withAssembly();
        var body = FlowDocumentBody.fromProgram(program, record("signal_ticks", 1));
        body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(),
            Map.of("require_result", NativeClusterJournalDocuments.build("adapter").flow().orElseThrow().inline().get("require_result")), body.execution());
        return new ProgramDocument(archive, Map.of(ProgramDocument.graphId(null), Map.of()), Optional.empty(), Optional.of(body));
    }
    /** Physical SIO outputs still belong to the root; input-only programs can use managed tasks. */
    private static ProgramDocument signalOwner(Map<String,DataType> inputs,DataValue.RecordValue initial,String source) {
        var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));
        var key=new ModuleDefinition.Key("i",1);
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Forever("service"),
            new FlowProgram.Invoke("transaction",key,new FlowCallBindings(args,Map.of())),
            new FlowProgram.EndForever("service_end"),
            new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));return workspace.snapshot();
    }
    static ProgramDocument managed(Map<String,DataType> inputs,DataValue.RecordValue initial,String source,int version) {
        var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));
        var key=new ModuleDefinition.Key("i",1);
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Forever("service"),
            new FlowProgram.TaskStart("transaction",key,args,"task"),new FlowProgram.TaskJoin("collect",key,"task","result"),
            new FlowProgram.Evaluate("require_result","require_result",new FlowCallBindings(Map.of("result","result"),Map.of())),
            new FlowProgram.EndForever("service_end"),
            new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var body=FlowDocumentBody.fromProgram(program,initial);
        body=new FlowDocumentBody(body.inputs(),body.steps(),body.initial(),body.canvas(),
            Map.of("require_result",NativeClusterJournalDocuments.build("adapter").flow().orElseThrow().inline().get("require_result")),body.execution());
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(body));return workspace.snapshot();
    }

}
