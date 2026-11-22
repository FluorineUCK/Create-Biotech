package com.nobodiiiii.createbiotech.content.biocomputing.terminal;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TerminalCodecTest {
 static DataValue.TextValue t(String s){return new DataValue.TextValue(s);}
 static DataValue.RecordValue r(Map<String,DataValue> m){return new DataValue.RecordValue(m);}
 static DataValue.RecordValue replace(DataValue.RecordValue r,String key,DataValue value){var m=new LinkedHashMap<>(r.fields());m.put(key,value);return r(m);}
 static DataValue.RecordValue fixture(DataValue.RecordValue node){return r(Map.of("version",new DataValue.IntegerValue(1),"root",t("r"),"nodes",r(Map.of("r",node))));}
 static DataValue.RecordValue literal(){return r(Map.of("kind",t("literal"),"value",DataValue.NullValue.INSTANCE));}
 @Test void roundTripsEveryNodeAndBindingAndFlatDeepTree(){
  var nodes=new LinkedHashMap<String,TerminalDefinition.Node>();
  nodes.put("r",new TerminalDefinition.Column(List.of("row","grid","repeat")));
  nodes.put("row",new TerminalDefinition.Row(List.of("text")));
  nodes.put("text",new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.ErrorValue("x","visible"))));
  nodes.put("grid",new TerminalDefinition.Grid(3,List.of("item")));
  nodes.put("item",new TerminalDefinition.Item(new TerminalBinding.Path(new ValuePath("output",List.of()).key("a.[中文]").index(2))));
  nodes.put("repeat",new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("memory",List.of()).key("list")),"template"));
  nodes.put("template",new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("row",List.of()))));
  var definition=new TerminalDefinition("r",nodes);assertEquals(definition,TerminalCodec.decode(TerminalCodec.encode(definition)));
  var deep=TerminalDefinitionTest.deep(2000);assertEquals(deep,assertDoesNotThrow(()->TerminalCodec.decode(TerminalCodec.encode(deep))));
  assertEquals(Set.of("version","root","nodes"),TerminalCodec.encode(definition).fields().keySet());
 }
 @Test void rejectsFutureVersionAndUnknownRootFieldsBesideLegalControl(){
  var valid=fixture(r(Map.of("kind",t("text"),"value",literal())));assertDoesNotThrow(()->TerminalCodec.decode(valid));
  assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,assertThrows(ArchiveException.class,()->TerminalCodec.decode(replace(valid,"version",new DataValue.IntegerValue(5)))).code());
  assertThrows(ArchiveException.class,()->TerminalCodec.decode(replace(valid,"extra",t("x"))));
  assertThrows(ArchiveException.class,()->TerminalCodec.decode(replace(valid,"version",t("1"))));
  assertThrows(ArchiveException.class,()->TerminalCodec.decode(replace(valid,"root",t("missing"))));
  assertThrows(ArchiveException.class,()->TerminalCodec.decode(DataValue.NullValue.INSTANCE));
 }
 @Test void closesEveryNodeBindingAndPathStepSchema(){
  var key=r(Map.of("kind",t("key"),"name",t("中文[.]")));var index=r(Map.of("kind",t("index"),"index",new DataValue.IntegerValue(0)));
  var path=r(Map.of("kind",t("path"),"input",t("memory"),"steps",new DataValue.ListValue(List.of(key,index))));
  for(var binding:List.of(literal(),path)){
   var node=r(Map.of("kind",t("text"),"value",binding));assertDoesNotThrow(()->TerminalCodec.decode(fixture(node)));
   assertThrows(ArchiveException.class,()->TerminalCodec.decode(fixture(replace(node,"unknown",t("x")))));
   assertThrows(ArchiveException.class,()->TerminalCodec.decode(fixture(replace(node,"value",replace(binding,"unknown",t("x"))))));
  }
  for(var step:List.of(key,index)){
   var bad=replace(path,"steps",new DataValue.ListValue(List.of(replace(step,"unknown",t("x")))));
   assertThrows(ArchiveException.class,()->TerminalCodec.decode(fixture(r(Map.of("kind",t("text"),"value",bad)))));
  }
  for(var node:List.of(r(Map.of("kind",t("alien"))),r(Map.of("kind",t("text"))),r(Map.of("kind",t("grid"),"columns",new DataValue.IntegerValue(0),"children",new DataValue.ListValue(List.of())))))
   assertThrows(ArchiveException.class,()->TerminalCodec.decode(fixture(node)));
 }
}
