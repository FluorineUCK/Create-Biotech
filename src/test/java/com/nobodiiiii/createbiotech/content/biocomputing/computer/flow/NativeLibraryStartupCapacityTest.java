package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class NativeLibraryStartupCapacityTest {
 @Test void authoredServiceGuardsFitIndependentHardwareAndEmitOnlyChangedPublicDocuments() throws Exception {
  var measurements=new StringBuilder();
  var output=java.nio.file.Path.of(".superpowers/cache/assembly-library-write");
  java.nio.file.Files.createDirectories(output);
  for(String role:List.of("a","b","c")) {
   var limits=NativeLibraryOwnersTest.limits(role.equals("a")?"mason":role.equals("b")?"librarian":"armorer",role.equals("c")?4:5).hardware();
   var doc=NativeLibraryReadDocuments.build(role);doc.validate(limits);
   var body=doc.flow().orElseThrow();var image=body.program(doc.archive()).compile(limits).assembly().orElseThrow();
   assertTrue(image.codeWords()<=limits.programUnits(),"Each member must fit its own hardware");
   measurements.append(role+" words="+image.codeWords()+" constants="+image.constantUnits()+" registers="+image.registerTypes().size()+" budget="+limits+"\n");
   if(role.equals("b"))java.nio.file.Files.writeString(output.resolve("library_read_"+role+".bcp"),Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(doc,SerialFlowHostTest.ARCHIVE))+"\n");
  }
  java.nio.file.Files.writeString(output.resolve("capacity.txt"),measurements);
 }
}
