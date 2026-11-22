package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Maintainer-only authoring of ordinary public Library software. */
public final class LibraryEntryDocuments {
    private LibraryEntryDocuments() {}
    public static final DataType.RecordType REQUEST = new DataType.RecordType(Map.of(
        "entry", DataType.Scalar.TEXT, "v", DataType.Scalar.ANY, "expected", DataType.Scalar.ANY));
    private static AssemblySource source(String role) throws Exception {
        return new AssemblySource(Files.readString(Path.of("examples/biocomputing/library_entry_"+role+".asm")));
    }
    public static ProgramDocument store() throws Exception {
        var inputs=Map.<String,DataType>of("volume",DataType.Scalar.TEXT);
        var key=new ModuleDefinition.Key("entry_store",1);
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("start",key,new FlowCallBindings(Map.of("volume","volume"),Map.of())),
            new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(source("store"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,record("volume","plant"))));
        return workspace.snapshot();
    }
    public static ProgramDocument coordinator() throws Exception {
        var old=NativeClusterJournalDocuments.build("coordinator");var body=old.flow().orElseThrow();
        var inputs=Map.of("commands",body.inputs().get("commands"));
        var key=new ModuleDefinition.Key("entry_drive",1);
        // This is one sequential mechanical service. Device waits suspend its task directly;
        // a second managed task and a polling wrapper add no concurrency to this workflow.
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("start",key,new FlowCallBindings(Map.of("commands","commands"),Map.of())),
            new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(source("coordinator"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,record("commands",body.initial().field("commands")))));
        return workspace.snapshot();
    }
    @org.junit.jupiter.api.Test void publicNamedEntryProgramsFitTheOriginalHardware() throws Exception {
        for(var role:List.of("store","coordinator")) {
            var document=role.equals("store")?store():coordinator();var limits=StockApplicationDriverDocuments.hardware();
            System.out.println("LIBRARY_ENTRY_"+role+" "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document,limits);
            var name="library_entry_"+role+".bcp";
            var doc=Path.of("examples/biocomputing",name);
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
            if("1".equals(System.getenv("GENERATE_LIBRARY_ENTRY"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
                Files.writeString(doc,encoded);Files.writeString(asset,encoded);
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(Files.readString(doc),Files.readString(asset));
                org.junit.jupiter.api.Assertions.assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
}
