package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class MixedAuthoredDocumentCodecTest {
 static final ArchiveLimits LIMITS=new ArchiveLimits(100000,1000000,1000000);
 static ProgramDocument document(boolean authored){var steps=new ArrayList<FlowProgram.Step>();steps.add(new FlowProgram.SerialReceive("receive","s0","received"));if(authored){steps.add(new FlowProgram.Procedure("definition",new ModuleDefinition.Key("plain",1),Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource("RET"))));steps.add(new FlowProgram.EndProcedure("end"));}var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(),steps).withAssembly(),new DataValue.RecordValue(Map.of()))));return workspace.snapshot();}
 @Test void schema31OrdinaryReceiveAndVersion4DefinitionRoundTripThroughPublicWire(){var d=document(true);assertEquals(new DataValue.IntegerValue(31),ProgramDocumentCodec.encode(d).field("version"));assertEquals(d,ProgramDocumentWire.decode(ProgramDocumentWire.encode(d,LIMITS),LIMITS));}
 @Test void oldSchema30ReceiveKeepsItsMeaningAndNeitherCapabilityCanBeDowngraded(){var old=document(false);assertEquals(new DataValue.IntegerValue(30),ProgramDocumentCodec.encode(old).field("version"));assertEquals(old,ProgramDocumentWire.decode(ProgramDocumentWire.encode(old,LIMITS),LIMITS));for(var pair:List.of(Map.entry(old,29L),Map.entry(document(true),30L))){var fields=new LinkedHashMap<>(ProgramDocumentCodec.encode(pair.getKey()).fields());fields.put("version",new DataValue.IntegerValue(pair.getValue()));assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));}}
}
