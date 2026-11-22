package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Maintainer-only authoring, never runtime orchestration or Java request/route evaluation. */
public class RoutedProductionDocuments {
 public static final DataType.RecordType RESULT=new DataType.RecordType(Map.of("c",DataType.Scalar.TEXT,"e",DataType.Scalar.TEXT,"a",DataType.Scalar.ANY,"o",DataType.Scalar.ANY,"r",DataType.Scalar.ANY));
 public static String source(String role)throws Exception{try(var in=RoutedProductionDocuments.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/routed_production_"+role+".asm")){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
 public static ProgramDocument published(String role)throws Exception{try(var in=RoutedProductionDocuments.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/routed_production_"+role+".bcp")){return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()),FoundationRecipeTest.LIMITS);}}
 public static ProgramDocument build(String role)throws Exception{
  boolean caller=role.equals("a");var inputs=new LinkedHashMap<String,DataType>();var initial=new LinkedHashMap<String,DataValue>();
  if(caller){
   inputs.put("target",NativeLibraryReadDocuments.TARGET);inputs.put("job",DataType.Scalar.TEXT);inputs.put("expected_address",DataType.Scalar.TEXT);inputs.put("items",new DataType.ListType(DataType.Scalar.ANY));
   initial.put("target",NativeLibraryReadDocuments.record("x",0,"y",0,"z",0));initial.put("job",new DataValue.TextValue("routed-order-1"));initial.put("expected_address",new DataValue.TextValue("workshop"));
   initial.put("items",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("item",new DataValue.TextValue("minecraft:stone"),"count",new DataValue.IntegerValue(1),"components",new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of())))))));
  }
  inputs.put("poll_ticks",DataType.Scalar.INTEGER);initial.put("poll_ticks",new DataValue.IntegerValue(20));
  var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));var outputs=caller?Map.<String,DataType>of("result",RESULT):Map.<String,DataType>of();var returns=caller?Map.of("result","result"):Map.<String,String>of();var key=new ModuleDefinition.Key("routed_production_"+role,role.equals("e")?2:1);
  var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("route",key,new FlowCallBindings(args,returns)),new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(Set.of("e","f").contains(role)?7:6,source(role)))),new FlowProgram.EndProcedure("end"))).withAssembly();
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(initial))));
  var nodes=new LinkedHashMap<String,TerminalDefinition.Node>();var rows=new ArrayList<String>();
  nodes.put("route_label",new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(caller?"路线检查，不代表制造完成 | checked_reply 仅表示路线匹配；查看原始回执 code/phase/diagnostic/milestone":"路线服务 "+role+"｜明确接线与启动，用后明确暂停；数据检查不是设备授权，故障不重试"))));rows.add("route_label");
  if(caller){var labels=Map.of("c","本地检查","e","期望地址","a","当前地址（观察值）","o","订单原地址","r","原始回执");for(var field:List.of("c","e","a","o","r")){String label="label_"+field,value="value_"+field,row="row_"+field;nodes.put(label,new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(labels.get(field)))));nodes.put(value,new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("output",List.of()).key("result").key(field))));nodes.put(row,new TerminalDefinition.Row(List.of(label,value)));rows.add(row);}}
  nodes.put("route_root",new TerminalDefinition.Column(rows));workspace.setTerminal(Optional.of(new TerminalDefinition("route_root",nodes)));return workspace.snapshot();
 }
 @Test void publicCompleteDocumentsMatchEditableSourceAndDefaultPerComputerCapacity()throws Exception{
  for(var role:List.of("a","e","d","f")){var document=build(role);document.validate(new ResourceLimits(256,65536,8));long words=document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(256,65536,8)).programUnits();assertEquals(Map.of("a",243L,"e",254L,"d",249L,"f",217L).get(role),words);
   byte[] bytes=ProgramDocumentWire.encode(document,FoundationRecipeTest.LIMITS);
   for(var root:List.of("src/main/resources/assets/create_biotech/biocomputing/examples/","examples/biocomputing/")){var path=Path.of(root+"routed_production_"+role+".bcp");if("1".equals(System.getenv("GENERATE_ROUTED_PRODUCTION"))&&Set.of("e","f").contains(role))Files.writeString(path,Base64.getEncoder().encodeToString(bytes)+"\n");else assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(path).strip()),FoundationRecipeTest.LIMITS));}
   assertEquals(source(role),Files.readString(Path.of("examples/biocomputing/routed_production_"+role+".asm")));System.out.println("ROUTED_PUBLIC role="+role+" words="+words);
  }
 }
}
