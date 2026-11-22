package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Authors editable software, never a runtime storage or order service. */
public final class StockNativeArchiveDocuments {
    public static final DataType.RecordType REQUEST = new DataType.RecordType(Map.of("v", ANY, "expected", ANY));
    public static final DataType.RecordType VERSION = new DataType.RecordType(Map.of("medium", TEXT, "entry", TEXT, "revision", INTEGER));
    public static final DataType.RecordType BINDING = new DataType.RecordType(Map.of("s", StockNativeIdentityDocuments.INPUT, "j", TEXT));
    public static final DataType.ListType ENTRIES = new DataType.ListType(BINDING);
    public static final DataType.RecordType SNAPSHOT = new DataType.RecordType(Map.of("kind", TEXT, "value", ENTRIES, "version", VERSION));
    public static final DataType.RecordType LEDGER_REQUEST = new DataType.RecordType(Map.of("s", StockNativeIdentityDocuments.INPUT, "book", SNAPSHOT));
    public static ProgramDocument reader() throws Exception {
        var inputs=Map.<String,DataType>of("commands",NativeLibraryReadDocuments.ROWS);
        var outputs=Map.<String,DataType>of("result",NativeLibraryReadDocuments.OUTCOME);
        var returns=Map.of("result","result");var key=new ModuleDefinition.Key("load",1);
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(Map.of("commands","commands"),returns)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(                Files.readString(Path.of("examples/biocomputing/stock_native_archive_read.asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,
            record("commands",NativeLibraryReadDocuments.build("a").flow().orElseThrow().initial().field("commands")))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("result")));return workspace.snapshot();
    }
    public static ProgramDocument ledger() throws Exception {
        return ledger("identity");
    }
    public static ProgramDocument ledger(String role) throws Exception {
        if(!Set.of("check","identity").contains(role))throw new IllegalArgumentException(role);
        boolean check=role.equals("check");
        var inputs=check?Map.<String,DataType>of():Map.<String,DataType>of("capacity",INTEGER);var outputs=Map.<String,DataType>of("reply",StockNativeIdentityDocuments.REPLY);
        var key=new ModuleDefinition.Key("bind",1);var returns=Map.of("reply","reply");
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(check?Map.of():Map.of("capacity","capacity"),returns)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(                Files.readString(Path.of("examples/biocomputing/stock_native_"+(check?"archive_check":"archived_identity")+".asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,check?record():record("capacity",8))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));return workspace.snapshot();
    }
    public static ProgramDocument build() throws Exception {
        var inputs = Map.<String,DataType>of("volume", TEXT, "entry", TEXT);
        var outputs = Map.<String,DataType>of("result", NativeLibraryReadDocuments.OUTCOME);
        var binding = Map.of("volume", "volume", "entry", "entry"); var returns = Map.of("result", "result");
        var key = new ModuleDefinition.Key("archive", 1);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("start", key, new FlowCallBindings(binding, returns)),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, returns, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/stock_native_archive_store.asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, record("volume", "plant", "entry", "reading"))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("result")));
        return workspace.snapshot();
    }
    @org.junit.jupiter.api.Test void publicArchiveStoreFitsAndPreservesItsEditableSource() throws Exception {
        var document=build(); var limits=StockApplicationDriverDocuments.hardware();
        System.out.println("ARCHIVE_STORE "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
        com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document,limits);
        var doc=Path.of("examples/biocomputing/stock_native_archive_store.bcp");
        var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/stock_native_archive_store.bcp");
        if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_ARCHIVE"))) {
            var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
            Files.writeString(doc,encoded);Files.writeString(asset,encoded);
        } else {
            org.junit.jupiter.api.Assertions.assertEquals(Files.readString(doc),Files.readString(asset));
            org.junit.jupiter.api.Assertions.assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
        }
    }
    @org.junit.jupiter.api.Test void checkedIdentityProgramsFitAndPublishTheirExactSources() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        for(var role:List.of("check","identity","read")) {
            var document=role.equals("read")?reader():ledger(role);String name=role.equals("identity")?"stock_native_archived_identity":"stock_native_archive_"+role;
            System.out.println(name+" "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document,limits);
            var doc=Path.of("examples/biocomputing",name+".bcp");var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name+".bcp");
            if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_ARCHIVE"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";Files.writeString(doc,encoded);Files.writeString(asset,encoded);
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(Files.readString(doc),Files.readString(asset));
                org.junit.jupiter.api.Assertions.assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
}
