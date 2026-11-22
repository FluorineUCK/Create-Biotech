package com.nobodiiiii.createbiotech.content.biocomputing.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;
class ListLengthArchiveTest {
 @Test void sharedLengthDeclarationUsesItsOwnNarrowGraphAndProgramCapabilityFence() {
  var graph=new ComputationGraph(Map.of("list",new DataType.ListType(DataType.Scalar.INTEGER)),Map.of("input",new ComputationGraph.Input("list"),"size",new ComputationGraph.Scalar(ScalarOperation.LIST_LENGTH,Map.of("value","input"))),Map.of("value","size"));
  assertEquals(20,GraphArchiveCodec.encodingVersion(List.of(graph)));var encoded=GraphArchiveCodec.encode(graph);
  assertEquals(graph,GraphArchiveCodec.decode(encoded,20));assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(encoded,19));
  assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(encoded,Integer.MAX_VALUE)).code());
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("length",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
  var data=ProgramArchiveCodec.encode(archive);assertEquals(new DataValue.IntegerValue(22),data.field("version"));assertEquals(archive,ProgramArchiveCodec.decode(data));
  var fields=new TreeMap<>(data.fields());fields.put("version",new DataValue.IntegerValue(21));assertThrows(ArchiveException.class,()->ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
  assertEquals(11,GraphArchiveCodec.encodingVersion(List.of(new ComputationGraph(Map.of(),Map.of(),Map.of()))));
 }
}
