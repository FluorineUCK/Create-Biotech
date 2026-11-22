package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
/** Maintainer-only authoring; runtime loads public .bcp bytes, not this class. */
public final class NativeLibraryRecoveryDocuments {
 static final String COMMON=PACKET_TYPE+"""
 TYPE outcome_type RECORD ok BOOLEAN value ANY
 REG packet packet_type
 REG tx outcome_type
 REG reply ANY
 REG code ANY
 REG flag BOOLEAN
 REG kind TEXT
 REG op TEXT
 REG seq ANY
 REG data ANY
 REG request TEXT
 REG error ANY
 CONST kind "library_command"
 CONST data null
 CONST error null
 """;
 public static final String INSPECT=COMMON+"""
 REG status_reply ANY
 REG result_reply ANY
 REG observed_sequence ANY
 REG pending TEXT
 REG status TEXT
 CONST status_reply null
 CONST result_reply null
 CONST observed_sequence null
 CONST seq 0
 CONST op "status"
 CONST status "status"
 CONST pending "result_pending"
 send:
 CORRELATION request
 RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
 XBUS.WRITE x0 packet
 XBUS.READ tx x0
 FIELD flag tx ok
 FIELD reply tx value
 JFALSE flag fail
 VALUE.EQUALS flag op status
 JFALSE flag result
 MOV status_reply reply
 FIELD observed_sequence reply sequence
 FIELD code reply code
 VALUE.EQUALS flag code pending
 JFALSE flag done
 MOV seq observed_sequence
 CONST op "result"
 JMP send
 result:
 MOV result_reply reply
 JMP done
 fail:
 MOV error reply
 done:
 """;
 public static final String ACK=COMMON+"""
 REG ack_reply ANY
 REG acknowledged BOOLEAN
 REG expected TEXT
 REG zero INTEGER
 CONST ack_reply null
 CONST acknowledged false
 CONST expected "acknowledged"
 CONST zero 0
 LESS_INTEGER flag sequence zero
 JFALSE flag send
 CONST error "invalid_sequence"
 JMP done
 send:
 MOV seq sequence
 CONST op "ack"
 CORRELATION request
 RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
 XBUS.WRITE x0 packet
 XBUS.READ tx x0
 FIELD flag tx ok
 FIELD reply tx value
 JFALSE flag fail
 MOV ack_reply reply
 FIELD code reply code
 VALUE.EQUALS flag code expected
 JFALSE flag done
 FIELD code reply sequence
 VALUE.EQUALS acknowledged code sequence
 JMP done
 fail:
 MOV error reply
 done:
 """;
 public static ProgramDocument build(String role){
  boolean inspect=role.equals("inspect");if(!inspect&&!role.equals("ack"))throw new IllegalArgumentException(role);
  var key=new ModuleDefinition.Key("library_"+role+"_a",1);
  Map<String,DataType> inputs=inspect?Map.of("target",TARGET):Map.of("target",TARGET,"sequence",INTEGER);
  Map<String,DataType> outputs=inspect?Map.of("status_reply",ANY,"result_reply",ANY,"observed_sequence",ANY,"error",ANY):Map.of("ack_reply",ANY,"acknowledged",BOOLEAN,"error",ANY);
  var bindings=new LinkedHashMap<String,String>();inputs.keySet().forEach(k->bindings.put(k,k));var returns=new LinkedHashMap<String,String>();outputs.keySet().forEach(k->returns.put(k,k));
  var steps=List.<FlowProgram.Step>of(new FlowProgram.Invoke("recover",key,new FlowCallBindings(bindings,returns)),new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(inspect?INSPECT:ACK))),new FlowProgram.EndProcedure("implementation_end"));
  var initial=inspect?record("target",record("x",0,"y",0,"z",0)):record("target",record("x",0,"y",0,"z",0),"sequence",-1);
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs,steps).withAssembly(),initial)));workspace.setTerminal(Optional.of(inspect?NativeLibraryResultLayouts.fields("observed_sequence","status_reply","result_reply","error"):NativeLibraryResultLayouts.fields("acknowledged","ack_reply","error")));return workspace.snapshot();
 }
 @org.junit.jupiter.api.Test void publicBytesMatchAuthoring()throws Exception {
  for(var role:List.of("inspect","ack")){
   var path=java.nio.file.Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/library_"+role+"_a.bcp");
   var doc=build(role);if("1".equals(System.getenv("GENERATE_RECOVERY")))java.nio.file.Files.writeString(path,Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(doc,new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000)))+"\n");
   org.junit.jupiter.api.Assertions.assertEquals(doc,ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(path).trim()),new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000)));
  }
 }
}

