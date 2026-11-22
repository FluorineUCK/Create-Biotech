package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowAuthoredLibraryReadTest {
 @Test void publicDocumentsAssembleWithinIndependentDefaultResidents() throws Exception {
  var limits=new ResourceLimits(256,65536,8);
  var measurements=new StringBuilder();
  for(String role:List.of("a","b","c")) {
   var doc=NativeLibraryOwnersTest.document(role);
   assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.build(role),doc);
   var body=doc.flow().orElseThrow();var p=body.program(doc.archive());var image=p.compile(limits).assembly().orElseThrow();
   measurements.append(role+" words="+image.codeWords()+" constants="+image.constantUnits()+" registers="+image.registerTypes().size()+"\n");
   assertTrue(image.codeWords()<=256);doc.validate(limits);
   var path=java.nio.file.Path.of(".superpowers/cache/assembly-library-read/generated/library_read_"+role+".bcp");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,Base64.getEncoder().encodeToString(com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire.encode(doc,SerialFlowHostTest.ARCHIVE))+"\n");
  }
  java.nio.file.Files.writeString(java.nio.file.Path.of(".superpowers/cache/assembly-library-read/capacity.txt"),measurements);
 }

 @Test void versionedAuthoredPresenceUsesTypedDataAndPaidNativeProgress() {
  var image=ProcedureAssembler.assemble(new AssemblySource("""
   TYPE bag RECORD present NULL
   REG value bag
   REG key TEXT
   REG yes BOOLEAN
   CONST value {"present":null}
   CONST key "present"
   HAS.FIELD yes value key
   """),Map.of(),Map.of("result",DataType.Scalar.BOOLEAN),Map.of("result","yes"),24000,256).program().forTask("local",1);
  var state=image.begin(new DataValue.RecordValue(Map.of())); long work=0;
  while(!image.complete(state)){assertTrue(work<100);state=image.step(state,Map.of(),Map.of(),++work).registers();image.validate(state,work,false);}
  assertTrue(work>7); assertEquals(new DataValue.BooleanValue(true),state.registers().values().stream().filter(DataValue.BooleanValue.class::isInstance).findFirst().orElseThrow());
 }
}


