package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Authoring of application software only; not a Java order/archive runtime. */
public final class StockScopedArchiveDocuments {
    public static final DataType.RecordType SCOPE=new DataType.RecordType(Map.of("source",TEXT,"device",TEXT,"revision",INTEGER,
        "target",SerialExchangeDriverDocuments.TARGET,"address",TEXT));
    public static final DataType.RecordType INPUT=new DataType.RecordType(Map.of("d",TEXT,"h",StockNativeIdentityDocuments.HEAD,"r",SCOPE));
    public static final DataType.ListType ENTRIES=new DataType.ListType(new DataType.RecordType(Map.of("s",INPUT,"j",TEXT)));
    public static final DataType.RecordType SNAPSHOT=new DataType.RecordType(Map.of("kind",TEXT,"value",ENTRIES,"version",StockNativeArchiveDocuments.VERSION));
    public static final DataType.RecordType REQUEST=new DataType.RecordType(Map.of("s",INPUT,"book",SNAPSHOT));
    public static final List<String> ROLES=List.of("check","identity","read","archive","submit","live","handoff","finish");
    public static ProgramDocument build(String role) throws Exception {
        Map<String,DataType> inputs,outputs;DataValue.RecordValue initial;
        switch(role) {
            case "live","handoff" -> {inputs=Map.of("target",SerialExchangeDriverDocuments.TARGET);outputs=Map.of("reply",ANY);initial=record("target",record("x",0,"y",0,"z",0));}
            case "finish" -> {inputs=Map.of("poll_ticks",INTEGER,"poll_limit",INTEGER);outputs=Map.of();initial=record("poll_ticks",20,"poll_limit",80);}
            case "check","archive" -> {inputs=Map.of();outputs=Map.of("reply",StockNativeIdentityDocuments.REPLY);initial=record();}
            case "identity" -> {inputs=Map.of("capacity",INTEGER);outputs=Map.of("reply",StockNativeIdentityDocuments.REPLY);initial=record("capacity",8);}
            case "read" -> {inputs=Map.of("commands",NativeLibraryReadDocuments.ROWS);outputs=Map.of("result",NativeLibraryReadDocuments.OUTCOME);
                initial=record("commands",NativeLibraryReadDocuments.build("a").flow().orElseThrow().initial().field("commands"));}
            case "submit" -> {inputs=Map.of("source",StockNativeIdentityDocuments.INPUT,"items",ANY,"target",SerialExchangeDriverDocuments.TARGET);
                outputs=Map.of("reply",ANY,"job",TEXT);initial=record("source",StockNativeIdentityDocuments.input("",0,""),"items",new DataValue.ListValue(List.of()),"target",record("x",0,"y",0,"z",0));}
            default -> throw new IllegalArgumentException(role);
        }
        var bindings=new LinkedHashMap<String,String>();inputs.keySet().forEach(k->bindings.put(k,k));
        var returns=new LinkedHashMap<String,String>();outputs.keySet().forEach(k->returns.put(k,k));var key=new ModuleDefinition.Key("scope_"+role,role.equals("archive")?3:2);
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(bindings,returns)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(                Files.readString(Path.of("examples/biocomputing/stock_native_scope_"+role+".asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields(outputs.keySet().stream().sorted().toArray(String[]::new))));return workspace.snapshot();
    }
    @org.junit.jupiter.api.Test void scopedApplicationDocumentsFitAndPublish() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();var documents=new LinkedHashMap<String,ProgramDocument>();
        for(var role:ROLES){var document=build(role);System.out.println("SCOPED_ARCHIVE_"+role+" "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document,limits);documents.put(role,document);}
        for(var entry:documents.entrySet()) {
            String name="stock_native_scope_"+entry.getKey()+".bcp";var doc=Path.of("examples/biocomputing",name);
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
            if("1".equals(System.getenv("GENERATE_STOCK_SCOPED_ARCHIVE"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(entry.getValue(),limits.archive()))+"\n";Files.writeString(doc,encoded);Files.writeString(asset,encoded);
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(Files.readString(doc),Files.readString(asset));
                org.junit.jupiter.api.Assertions.assertEquals(entry.getValue(),ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
}
