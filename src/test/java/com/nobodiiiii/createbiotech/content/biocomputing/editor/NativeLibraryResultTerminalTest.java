package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeLibraryResultTerminalTest {
 static final ArchiveLimits LIMITS = new ArchiveLimits(10000,100000,1000000);
 static ProgramDocument published(String role) throws Exception {
  return ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/library_"+role+".bcp")).trim()),LIMITS);
 }
 @Test void publicResultLayoutsAreReadOnlyEditableAndSurviveExportImport() throws Exception {
  for (String role : List.of("read_a","write_a","inspect_a","ack_a")) {
   var doc=published(role);
   var terminal=doc.terminal().orElseThrow(()->new AssertionError(role+" needs a public result terminal"));
   assertTrue(terminal.readOnly());
   var expected=role.equals("inspect_a")?List.of("observed_sequence","status_reply","result_reply","error"):
    role.equals("ack_a")?List.of("acknowledged","ack_reply","error"):List.of("success","read_result","error");
   for(String name:expected) assertEquals(new TerminalBinding.Path(new ValuePath("output",List.of()).key(name)),((TerminalDefinition.Text)terminal.nodes().get(name)).value());
   var workspace=new ProgramWorkspace(doc); var nodes=new LinkedHashMap<>(terminal.nodes());
   nodes.put(expected.getFirst()+"_label",new TerminalDefinition.Text(new TerminalBinding.Literal(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.TextValue("Player label"))));
   workspace.setTerminal(Optional.of(new TerminalDefinition(terminal.root(),nodes)));
   var edited=workspace.snapshot(); assertNotEquals(doc.terminal(),edited.terminal());
   assertEquals(edited,ProgramDocumentWire.decode(ProgramDocumentWire.encode(edited,LIMITS),LIMITS));
   assertEquals(doc.flow(),edited.flow()); assertEquals(doc.archive(),edited.archive());
  }
 }
 @Test void publicLayoutsPreserveEveryIndependentMemberCodeBudget() throws Exception {
  var words=Map.of("read_a",256,"write_a",241,"inspect_a",212,"ack_a",204,"read_b",250,"read_c",185,"write_d",237);
  for(var entry:words.entrySet()) {
   var doc=published(entry.getKey());
   var limits=com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryOwnersTest.limits(entry.getKey().equals("read_b")?"librarian":"mason",entry.getKey().equals("read_c")?4:5);
   doc.validate(limits.hardware());
   assertEquals(entry.getValue().intValue(),doc.flow().orElseThrow().program(doc.archive()).usage(limits.hardware()).programUnits());
  }
 }
 @Test void authoringMatchesPublishedDocuments() throws Exception {
  for(String role:List.of("read_a","write_a","inspect_a","ack_a")) {
   var doc=switch(role){case "read_a"->NativeLibraryReadDocuments.build("a");case "write_a"->NativeLibraryWriteDocuments.build("a");case "inspect_a"->NativeLibraryRecoveryDocuments.build("inspect");default->NativeLibraryRecoveryDocuments.build("ack");};
   if("1".equals(System.getenv("GENERATE_TERMINALS"))) {
    String encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(doc,LIMITS))+"\n";
    for(String dir:List.of("src/main/resources/assets/create_biotech/biocomputing/examples/","examples/biocomputing/")) Files.writeString(Path.of(dir+"library_"+role+".bcp"),encoded);
   }
   assertEquals(doc,published(role));
  }
 }
}



