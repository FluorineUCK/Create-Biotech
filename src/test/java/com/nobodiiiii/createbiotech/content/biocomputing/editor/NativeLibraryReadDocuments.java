package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
/** Maintainer-only authoring. Runtime loads ordinary public documents, never this class. */
public final class NativeLibraryReadDocuments {
 public static final DataType.RecordType TARGET=new DataType.RecordType(Map.of("x",INTEGER,"y",INTEGER,"z",INTEGER));
 public static final DataType.RecordType ROW=new DataType.RecordType(Map.of("o",TEXT,"d",ANY,"e",TEXT,"l",BOOLEAN));
 public static final DataType.ListType ROWS=new DataType.ListType(ROW);
 public static final DataType.RecordType PACKET=new DataType.RecordType(Map.of("kind",TEXT,"target",TARGET,"request",TEXT,"operation",TEXT,"sequence",ANY,"data",ANY));
 public static final DataType.RecordType OUTCOME=new DataType.RecordType(Map.of("ok",BOOLEAN,"value",ANY));
 public static final DataType.RecordType CONTEXT=new DataType.RecordType(Map.of("p",PACKET,"r",ANY));
 public static final DataType.RecordType MATCH=new DataType.RecordType(Map.of("m",BOOLEAN));
 public static final String PACKET_TYPE="TYPE target_type RECORD x INTEGER y INTEGER z INTEGER\nTYPE packet_type RECORD kind TEXT target target_type request TEXT operation TEXT sequence ANY data ANY\n";
 public static final String A=PACKET_TYPE+"""
 TYPE outcome_type RECORD ok BOOLEAN value ANY
 REG i INTEGER
 REG seq ANY
 REG one INTEGER
 REG success BOOLEAN
 REG read_result ANY
 REG error ANY
 REG kind TEXT
 REG read TEXT
 REG result TEXT
 REG busy TEXT
 REG row ELEMENT commands
 REG packet packet_type
 REG tx outcome_type
 REG op TEXT
 REG data ANY
 REG request TEXT
 REG reply ANY
 REG code ANY
 REG expected TEXT
 REG flag BOOLEAN
 REG last BOOLEAN
 CONST i 0
 CONST seq 0
 CONST one 1
 CONST success false
 CONST read_result null
 CONST error null
 CONST kind "library_command"
 CONST read "read"
 CONST result "result"
 CONST busy "busy"
 prepare:
 LIST.GET row commands i
 FIELD op row o
 FIELD data row d
 CORRELATION request
 RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
 XBUS.WRITE x0 packet
 XBUS.READ tx x0
 FIELD flag tx ok
 FIELD reply tx value
 JFALSE flag fail
 FIELD seq reply sequence
 FIELD code reply code
 FIELD expected row e
 VALUE.EQUALS flag code expected
 JFALSE flag unexpected
 VALUE.EQUALS flag code read
 JFALSE flag advance
 FIELD read_result reply value
 advance:
 FIELD last row l
 JFALSE last next
 MOV success last
 JMP done
 next:
 ADD_INTEGER i i one
 JMP prepare
 unexpected:
 VALUE.EQUALS flag op result
 JFALSE flag fail
 VALUE.EQUALS flag code busy
 JFALSE flag fail
 JMP prepare
 fail:
 MOV error reply
 done:
 """;
 public static final String B=PACKET_TYPE+"""
 TYPE outcome_type RECORD ok BOOLEAN value ANY
 TYPE context_type RECORD p packet_type r ANY
 TYPE match_type RECORD m BOOLEAN
 REG outcome outcome_type
 REG sent SERIAL_SEND
 REG received SERIAL_RECEIVE
 REG serial SERIAL_STATUS
 REG context context_type
 REG match match_type
 REG reply ANY
 REG success BOOLEAN
 REG matched BOOLEAN
 REG got BOOLEAN
 REG flag BOOLEAN
 REG check BOOLEAN
 REG empty TEXT
 REG ready TEXT
 REG clear TEXT
 REG state ANY
 CONST success false
 CONST empty "empty"
 CONST ready "ready"
 CONST clear ""
 SERIAL.SEND s0 packet sent
 MOV reply sent
 FIELD flag sent ok
 JFALSE flag done
 poll:
 SERIAL.RECEIVE received s0
 FIELD got received ok
 JFALSE got receive_status
 FIELD reply received value
 RECORD.MAKE context p packet r reply
 XBUS.WRITE x1 context
 XBUS.READ match x1
 FIELD matched match m
 JFALSE matched connection
 SLEEP interval
 MOV success matched
 JMP done
 receive_status:
 MOV reply received
 FIELD state received status
 VALUE.EQUALS flag state empty
 JFALSE flag wire_failure
 connection:
 SERIAL.STATUS serial s0
 MOV reply serial
 FIELD state serial connection
 VALUE.EQUALS flag state ready
 JFALSE flag wire_failure
 FIELD state serial failure
 VALUE.EQUALS flag state clear
 JFALSE flag wire_failure
 SLEEP interval
 JMP poll
 wire_failure:
 SLEEP interval
 done:
 RECORD.MAKE outcome ok success value reply
 """;
 public static final String C=PACKET_TYPE+"""
 TYPE match_type RECORD m BOOLEAN
 REG packet packet_type
 REG reply ANY
 REG keys LIST_KEYS
 REG key TEXT
 REG index INTEGER
 REG one INTEGER
 REG size INTEGER
 REG matched BOOLEAN
 REG flag BOOLEAN
 REG left ANY
 REG right ANY
 REG kind TEXT
 REG outcome match_type
 FIELD packet context p
 FIELD reply context r
 CONST matched false
 CONST keys ["kind","target","request","code","sequence","value"]
 CONST index 0
 CONST one 1
 LIST.LENGTH size keys
 guard:
 LIST.GET key keys index
 HAS.FIELD flag reply key
 JFALSE flag done
 ADD_INTEGER index index one
 LESS_INTEGER flag index size
 JFALSE flag compare
 JMP guard
 compare:
 FIELD left reply kind
 CONST kind "library_reply"
 VALUE.EQUALS flag left kind
 JFALSE flag done
 FIELD left reply target
 FIELD right packet target
 VALUE.EQUALS flag left right
 JFALSE flag done
 FIELD left reply request
 FIELD right packet request
 VALUE.EQUALS matched left right
 done:
 RECORD.MAKE outcome m matched
 """;
 // Local alias is explicitly typed rather than a dummy caller argument.
 public static String matcherSource(){return "TYPE LIST_KEYS LIST TEXT\n"+C;}
 public static ProgramDocument build(String role) {
  var key=new ModuleDefinition.Key("library_read_"+role,role.equals("b")?2:1);
  Map<String,DataType> inputs,outputs;Map<String,String> returns;DataValue.RecordValue initial;String source;
  var steps=new ArrayList<FlowProgram.Step>();
  switch(role){
   case "a" -> {inputs=Map.of("target",TARGET,"commands",ROWS);outputs=Map.of("success",BOOLEAN,"read_result",ANY,"error",ANY);returns=Map.of("success","success","read_result","read_result","error","error");initial=new DataValue.RecordValue(Map.of("target",record("x",0,"y",0,"z",0),"commands",new DataValue.ListValue(List.of(row("status",null,"idle",false),row("seek",record("x",1,"y",0,"z",0),"accepted",false),row("result",null,"positioned",false),row("ack",null,"acknowledged",false),row("select",2,"accepted",false),row("result",null,"selected",false),row("ack",null,"acknowledged",false),row("read","reading","accepted",false),row("result",null,"read",false),row("ack",null,"acknowledged",true)))));source=A;steps.add(new FlowProgram.Invoke("query",key,new FlowCallBindings(Map.of("target","target","commands","commands"),returns)));}
   case "b" -> {inputs=Map.of("interval",INTEGER);outputs=Map.of("outcome",OUTCOME);returns=Map.of("outcome","outcome");initial=record("interval",20);source=B;steps.add(new FlowProgram.Forever("service"));steps.add(new FlowProgram.XBusStatus("sender_status","x0","bus_status"));steps.add(new FlowProgram.If("sender_gate",new FlowInput.Expression("sender_present","value",Map.of("status","bus_status"))));steps.add(new FlowProgram.Read("packet","x0","packet",PACKET));steps.add(new FlowProgram.Invoke("transaction",key,new FlowCallBindings(Map.of("packet","packet","interval","interval"),Map.of("outcome","outcome"))));steps.add(new FlowProgram.Write("response","x0","outcome"));steps.add(new FlowProgram.EndIf("sender_gate_end"));steps.add(new FlowProgram.Sleep("service_yield","interval"));steps.add(new FlowProgram.EndForever("service_end"));}
   case "c" -> {inputs=Map.of("interval",INTEGER);outputs=Map.of("outcome",MATCH);returns=Map.of("outcome","outcome");initial=record("interval",20);source=matcherSource();steps.add(new FlowProgram.Forever("service"));steps.add(new FlowProgram.XBusStatus("sender_status","x0","bus_status"));steps.add(new FlowProgram.If("sender_gate",new FlowInput.Expression("sender_present","value",Map.of("status","bus_status"))));steps.add(new FlowProgram.Read("context","x0","context",CONTEXT));steps.add(new FlowProgram.Invoke("match",key,new FlowCallBindings(Map.of("context","context"),Map.of("outcome","outcome"))));steps.add(new FlowProgram.Write("response","x0","outcome"));steps.add(new FlowProgram.EndIf("sender_gate_end"));steps.add(new FlowProgram.Sleep("service_yield","interval"));steps.add(new FlowProgram.EndForever("service_end"));}
   default -> throw new IllegalArgumentException(role);
  }
  Map<String,DataType> args=role.equals("a")?inputs:role.equals("b")?Map.of("packet",PACKET,"interval",INTEGER):Map.of("context",CONTEXT);
  steps.add(new FlowProgram.Procedure("implementation",key,args,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(source))));steps.add(new FlowProgram.EndProcedure("implementation_end"));
  var body=FlowDocumentBody.fromProgram(new FlowProgram(inputs,steps).withAssembly(),initial);
  if(!role.equals("a")) {
   var guard=new ComputationGraph(Map.of("status",INTEGER),Map.of("status",new ComputationGraph.Input("status"),"sender",new ComputationGraph.Constant(new DataValue.IntegerValue(15)),"present",new ComputationGraph.Scalar(ScalarOperation.EQUAL_INTEGER,Map.of("left","status","right","sender"))),Map.of("value","present"));
   body=new FlowDocumentBody(body.inputs(),body.steps(),body.initial(),body.canvas(),Map.of("sender_gate",guard),body.execution());
  }
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(body));if(role.equals("a"))workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("success","read_result","error")));return workspace.snapshot();
 }
 static DataValue value(Object v){if(v==null)return DataValue.NullValue.INSTANCE;if(v instanceof DataValue d)return d;if(v instanceof String s)return new DataValue.TextValue(s);if(v instanceof Boolean f)return new DataValue.BooleanValue(f);return new DataValue.IntegerValue(((Number)v).longValue());}
 public static DataValue.RecordValue record(Object... pairs){var m=new LinkedHashMap<String,DataValue>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],value(pairs[i+1]));return new DataValue.RecordValue(m);}
 static DataValue row(String o,Object d,String e,boolean l){return record("o",o,"d",d,"e",e,"l",l);}
}
