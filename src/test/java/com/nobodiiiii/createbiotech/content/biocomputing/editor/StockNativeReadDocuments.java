package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

public class StockNativeReadDocuments {
    public static ProgramDocument build(String role) throws Exception {
        boolean sample=role.equals("sample");
        var inputs=sample?Map.<String,DataType>of("reader",SerialExchangeDriverDocuments.TARGET):Map.<String,DataType>of();
        var outputs=sample?Map.<String,DataType>of():Map.<String,DataType>of("sample",ANY,"input",ANY,"result",ANY);
        var in=sample?Map.of("reader","reader"):Map.<String,String>of();
        var out=sample?Map.<String,String>of():Map.of("sample","sample","input","input","result","result");
        var key=new ModuleDefinition.Key("i",1);
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("run",key,new FlowCallBindings(in,out)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,out,FlowBlockRules.NONE,Optional.of(new AssemblySource(                Files.readString(Path.of("examples/biocomputing/stock_native_"+role+".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var draft=new ProgramWorkspace();
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,sample?record("reader",record("x",0,"y",0,"z",0)):record())));
        if(!sample)draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("input","result","sample")));
        return draft.snapshot();
    }
    @Test void nativeReaderExamplesRemainPublicDocumentsOnTheExistingHardware() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        for(String role:List.of("sample","inspect")) {
            var document=build(role);
            System.out.println("NATIVE_READ_"+role+" "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            PreparedProgram.prepare(document,limits);
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/stock_native_"+role+".bcp");
            var doc=Path.of("examples/biocomputing/stock_native_"+role+".bcp");
            if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_READ"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
                Files.writeString(asset,encoded); Files.writeString(doc,encoded);
            } else {
                assertEquals(Files.readString(doc),Files.readString(asset));
                assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
}
