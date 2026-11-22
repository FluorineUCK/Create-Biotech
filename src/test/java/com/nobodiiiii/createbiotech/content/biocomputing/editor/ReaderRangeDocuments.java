package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

/** Resource authoring, never a host application executor. Existing B/C are reused verbatim. */
public final class ReaderRangeDocuments {
    private ReaderRangeDocuments(){}
    public static ProgramDocument build(){
        var old=ReaderDriverDocuments.build("a");var definition=old.flow().orElseThrow().program(old.archive()).steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow();
        String source=definition.assembly().orElseThrow().text()
            .replace("TYPE component_list LIST TEXT\n","")
            .replace("TYPE sample_type RECORD device ANY session ANY components component_list","TYPE sample_type RECORD device ANY session ANY component TEXT offset INTEGER count INTEGER")
            .replace("RECORD.MAKE sample_arguments device device session session components components","RECORD.MAKE sample_arguments device device session session component component offset offset count count")
            .replace("CONST operation \"sample\"","CONST operation \"sample_range\"")
            // Same two-stage handshake: pending=true enters construction; false falls out after its reply.
            .replace("REG sampled BOOLEAN","REG range_pending BOOLEAN")
            .replace("CONST sampled false","CONST range_pending true")
            .replace("CONST sampled true","CONST range_pending false")
            .replace("JFALSE sampled prepare_sample\nJMP done\nprepare_sample:\n","JFALSE range_pending done\n");
        var key=new ModuleDefinition.Key("sample_range",1);var inputs=Map.<String,DataType>of("target",TARGET,"component",TEXT,"offset",INTEGER,"count",INTEGER);var outputs=Map.<String,DataType>of("ok",BOOLEAN,"reply",ANY);
        var steps=List.<FlowProgram.Step>of(new FlowProgram.Invoke("range_once",key,new FlowCallBindings(Map.of("target","target","component","component","offset","offset","count","count"),Map.of("ok","ok","reply","reply"))),new FlowProgram.Procedure("range_definition",key,inputs,outputs,Map.of("ok","flag","reply","reply"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("range_end"));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs,steps).withAssembly(),record("target",record("x",0,"y",0,"z",0),"component","create_biotech:inventory_item_tags","offset",0,"count",1))));workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("ok","reply")));return workspace.snapshot();
    }
    @org.junit.jupiter.api.Test void completeCallableRangeDocumentFitsUnchangedDefaultBudgetBeforePublication()throws Exception{
        var document=build();var program=document.flow().orElseThrow().program(document.archive());var diagnostic=new ResourceLimits(4096,65536,8);var image=FlowXBus.image(program);
        System.out.println("READER_RANGE_A_FULL "+program.usage(diagnostic)+" families="+image.instructionFamilies());
        java.nio.file.Files.writeString(java.nio.file.Path.of(".superpowers/cache/reader-range/player-source.asm"),program.steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow().assembly().orElseThrow().text());
        java.nio.file.Files.writeString(java.nio.file.Path.of(".superpowers/cache/reader-range/reader_sample_range_a.bcp"),Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,ReaderDriverDocumentTest.ARCHIVE))+"\n");
        document.validate(ReaderDriverDocumentTest.HARDWARE);assertEquals(document,ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,ReaderDriverDocumentTest.ARCHIVE),ReaderDriverDocumentTest.ARCHIVE));assertTrue(image.instructionFamilies().contains(InstructionFamily.ISA_1B));
    }
}
