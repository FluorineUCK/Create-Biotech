package com.nobodiiiii.createbiotech.content.biocomputing.terminal;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TerminalDefinitionTest {
 static TerminalDefinition.Text text() { return new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("hello"))); }
 static TerminalDefinition deep(int depth) {
  var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
  for (int i=0;i<depth;i++) nodes.put("n"+i,new TerminalDefinition.Column(List.of("n"+(i+1))));
  nodes.put("n"+depth,text()); return new TerminalDefinition("n0",nodes);
 }
 @Test void copiesNodeMapAndChildLists() {
  var children = new ArrayList<>(List.of("leaf")); var nodes = new LinkedHashMap<String,TerminalDefinition.Node>();
  nodes.put("root",new TerminalDefinition.Row(children)); nodes.put("leaf",text());
  var definition = new TerminalDefinition("root",nodes); children.clear(); nodes.clear();
  assertEquals(List.of("leaf"),((TerminalDefinition.Row)definition.nodes().get("root")).children());
  assertEquals(2,definition.nodes().size()); assertThrows(UnsupportedOperationException.class,()->definition.nodes().clear());
  assertThrows(UnsupportedOperationException.class,()->((TerminalDefinition.Row)definition.nodes().get("root")).children().clear());
  assertEquals(new TerminalDefinition.Column(List.of()),TerminalDefinition.empty().nodes().get("root"));
 }
 @Test void rejectsInvalidTreesBesideLegalControl() {
  assertDoesNotThrow(()->new TerminalDefinition("r",Map.of("r",new TerminalDefinition.Column(List.of("t")),"t",text())));
  var invalid=List.of(
   Map.<String,TerminalDefinition.Node>of("r",new TerminalDefinition.Column(List.of("missing"))),
   Map.<String,TerminalDefinition.Node>of("r",new TerminalDefinition.Column(List.of("r"))),
   Map.<String,TerminalDefinition.Node>of("r",new TerminalDefinition.Column(List.of("t","t")),"t",text()),
   Map.<String,TerminalDefinition.Node>of("r",text(),"orphan",text()),
   Map.<String,TerminalDefinition.Node>of("r",text(),"a",new TerminalDefinition.Row(List.of("b")),"b",new TerminalDefinition.Row(List.of("a"))));
  for(var nodes:invalid) assertThrows(IllegalArgumentException.class,()->new TerminalDefinition("r",nodes));
  assertThrows(IllegalArgumentException.class,()->new TerminalDefinition("absent",Map.of("r",text())));
  assertThrows(IllegalArgumentException.class,()->new TerminalDefinition(" ",Map.of(" ",text())));
  assertDoesNotThrow(()->new TerminalDefinition.Grid(Integer.MAX_VALUE,List.of()));
  assertThrows(IllegalArgumentException.class,()->new TerminalDefinition.Grid(0,List.of()));
  assertThrows(IllegalArgumentException.class,()->new TerminalDefinition.Grid(-1,List.of()));
 }
 @Test void restrictsPathRootsNotSpecialCharacterKeys() {
  for(String root:List.of("memory","output","row")) assertDoesNotThrow(()->new TerminalBinding.Path(new ValuePath(root,List.of()).key("中文.[\"/键")));
  assertThrows(IllegalArgumentException.class,()->new TerminalBinding.Path(new ValuePath("world",List.of())));
 }
 @Test void validatesDeepFlatTreeWithoutJavaRecursion() { assertEquals(2001,assertDoesNotThrow(()->deep(2000)).nodes().size()); }
}

