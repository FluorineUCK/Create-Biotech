package com.nobodiiiii.createbiotech.content.biocomputing.terminal;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TerminalProjectionTest {
 @Test void outputAbsenceIsPresentationMetadataAndNeverAnEmptyListOrSuccessfulNull(){
  var binding=new TerminalBinding.Path(new ValuePath("output",List.of()).key("result"));
  var definition=repeated(binding);
  var roots=r(Map.of("memory",r(Map.of()),"output",DataValue.NullValue.INSTANCE));
  var phases=new LinkedHashMap<String,String>();
  for(String phase:List.of("IDLE","RUNNING","READY","COMPUTING","WAITING_READ","WAITING_WRITE","WAITING_READABLE","SLEEPING")) phases.put(phase,"output_pending");
  phases.put("FAILED","output_failed");phases.put("NO_PROGRAM","output_no_program");
  phases.put("COMPLETE","output_unavailable");phases.put("SUCCEEDED","output_unavailable");phases.put("UNKNOWN","output_unavailable");
  phases.forEach((phase,code)->{
   for(boolean paused:List.of(false,true)){
    var view=TerminalProjection.project(new TerminalStatusView(Optional.of(definition),roots,phase,paused),Map.of(),2,1);
    assertEquals(new TerminalProjection.Problem(code,phase),view.problem().orElseThrow());
    assertTrue(view.value().isEmpty());assertTrue(view.page().isEmpty());assertTrue(view.children().isEmpty());
   }
  });
  assertEquals("type_mismatch",project(definition,roots).problem().orElseThrow().code(),"Raw bindings/previews remain strict");
  assertThrows(ValuePath.PathException.class,()->binding.resolve(roots));
 }
 @Test void availableOutputRetainsNullErrorsInvalidPathsAndTheExactViewBudget(){
  var output=r(Map.of("empty",DataValue.NullValue.INSTANCE,"error",new DataValue.ErrorValue("denied","No access"),"items",new DataValue.ListValue(List.of(n(1)))));
  var roots=r(Map.of("memory",r(Map.of()),"output",output));
  for(String phase:List.of("COMPLETE","SUCCEEDED","RUNNING")){
   for(String key:List.of("empty","error")){
    var d=single(new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("output",List.of()).key(key))));
    var v=TerminalProjection.project(new TerminalStatusView(Optional.of(d),roots,phase,false),Map.of(),2,1);
    assertTrue(v.problem().isEmpty());assertEquals(output.field(key),v.value().orElseThrow());
   }
   var badPaths=List.of(new ValuePath("output",List.of()).key("absent"),new ValuePath("output",List.of()).key("empty").key("x"),new ValuePath("output",List.of()).key("items").index(3));
   var codes=List.of("missing_field","type_mismatch","index_out_of_bounds");
   for(int i=0;i<badPaths.size();i++){
    var d=single(new TerminalDefinition.Text(new TerminalBinding.Path(badPaths.get(i))));
    assertEquals(codes.get(i),TerminalProjection.project(new TerminalStatusView(Optional.of(d),roots,phase,false),Map.of(),2,1).problem().orElseThrow().code());
   }
  }
  var d=new TerminalDefinition("r",Map.of("r",new TerminalDefinition.Row(List.of("bad","waiting")),"bad",new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("memory",List.of()).key("absent"))),"waiting",new TerminalDefinition.Item(path("output"))));
  var snapshot=new TerminalStatusView(Optional.of(d),r(Map.of("memory",r(Map.of()),"output",DataValue.NullValue.INSTANCE)),"RUNNING",false);
  var view=TerminalProjection.project(snapshot,Map.of(),2,3);
  assertEquals("missing_field",view.children().getFirst().problem().orElseThrow().code());
  assertEquals("output_pending",view.children().getLast().problem().orElseThrow().code());
  assertThrows(IllegalArgumentException.class,()->TerminalProjection.project(snapshot,Map.of(),2,2));
 }
 @Test void installedControlsAwaitOutputButDoNotHideEarlierBindingErrorsOrRelaxServerInvocation(){
  var roots=r(Map.of("memory",r(Map.of()),"output",DataValue.NullValue.INSTANCE));
  var title=new TerminalBinding.Literal(new DataValue.TextValue("Send"));
  var enabled=new TerminalBinding.Path(new ValuePath("output",List.of()).key("enabled"));
  var inputs=new TerminalBinding.Literal(r(Map.of()));
  var button=new TerminalDefinition.Button(title,enabled,"main",inputs);
  var input=new TerminalDefinition.Input(title,enabled,"main","text",inputs);
  for(var node:List.of(button,input)){
   var definition=single(node);
   var view=TerminalProjection.project(new TerminalStatusView(Optional.of(definition),roots,"RUNNING",false),Map.of(),2,1);
   assertEquals("output_pending",view.problem().orElseThrow().code());
   assertTrue(view.button().isEmpty());assertTrue(view.input().isEmpty());
   assertThrows(IllegalArgumentException.class,()->{
    if(node instanceof TerminalDefinition.Button) TerminalInteraction.resolve(definition,roots,"1:r");
    else TerminalInteraction.resolveSubmit(definition,roots,"1:r","typed");
   });
  }
  var invalid=single(new TerminalDefinition.Button(new TerminalBinding.Literal(n(3)),enabled,"main",inputs));
  assertEquals("invalid_button_title",TerminalProjection.project(new TerminalStatusView(Optional.of(invalid),roots,"RUNNING",false),Map.of(),2,1).problem().orElseThrow().code());
 }
 static DataValue.RecordValue r(Map<String,DataValue> m){return new DataValue.RecordValue(m);}
 static DataValue.IntegerValue n(long n){return new DataValue.IntegerValue(n);}
 static TerminalBinding.Path path(String root){return new TerminalBinding.Path(new ValuePath(root,List.of()));}
 static TerminalDefinition single(TerminalDefinition.Node node){return new TerminalDefinition("r",Map.of("r",node));}
 static TerminalProjection.View project(TerminalDefinition d,DataValue.RecordValue roots){return TerminalProjection.project(d,roots,Map.of(),2,100);}
 static TerminalDefinition repeated(TerminalBinding binding){return new TerminalDefinition("r",Map.of("r",new TerminalDefinition.Repeat(binding,"t/[0]:中文"),"t/[0]:中文",new TerminalDefinition.Text(path("row"))));}
 @Test void resolvesSpecialKeysRamOutputAndLiteralWithoutChangingValues(){
  var memory=r(Map.of("中文.[\"/键",new DataValue.ListValue(List.of(n(7)))));var output=new DataValue.ErrorValue("user_error","remain visible");
  var roots=r(Map.of("memory",memory,"output",output));var before=r(new LinkedHashMap<>(roots.fields()));
  var binding=new TerminalBinding.Path(new ValuePath("memory",List.of()).key("中文.[\"/键").index(0));
  assertEquals(n(7),project(single(new TerminalDefinition.Text(binding)),roots).value().orElseThrow());
  assertEquals(output,project(single(new TerminalDefinition.Text(path("output"))),roots).value().orElseThrow());
  assertEquals(DataValue.NullValue.INSTANCE,project(single(new TerminalDefinition.Text(new TerminalBinding.Literal(DataValue.NullValue.INSTANCE))),roots).value().orElseThrow());
  assertEquals(before,roots);
 }
 @Test void reportsMissingTypeBoundsAndInvalidItemsWithoutHidingSibling(){
  var roots=r(Map.of("memory",new DataValue.ListValue(List.of(n(1)))));
  assertEquals(n(1),project(single(new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("memory",List.of()).index(0)))),roots).value().orElseThrow());
  var bindings=List.of(path("row"),new TerminalBinding.Path(new ValuePath("memory",List.of()).key("x")),new TerminalBinding.Path(new ValuePath("memory",List.of()).index(5)));
  var codes=List.of("missing_field","type_mismatch","index_out_of_bounds");
  for(int i=0;i<bindings.size();i++){
   var d=new TerminalDefinition("r",Map.of("r",new TerminalDefinition.Row(List.of("bad","ok")),"bad",new TerminalDefinition.Text(bindings.get(i)),"ok",TerminalDefinitionTest.text()));
   var view=project(d,roots);assertEquals(codes.get(i),view.children().getFirst().problem().orElseThrow().code());
   assertTrue(view.children().getFirst().value().isEmpty());assertEquals(new DataValue.TextValue("hello"),view.children().getLast().value().orElseThrow());
  }
  var valid=r(Map.of("item",new DataValue.TextValue("unknown:still_structure_valid"),"count",n(0),"components",r(Map.of())));
  assertTrue(project(single(new TerminalDefinition.Item(new TerminalBinding.Literal(valid))),roots).problem().isEmpty());
  for(DataValue bad:List.of(n(2),r(Map.of("item",n(1),"count",n(2))),r(Map.of("item",new DataValue.TextValue("x"),"count",n(-1))),r(Map.of("item",new DataValue.TextValue("x")))))
   assertEquals("invalid_item",project(single(new TerminalDefinition.Item(new TerminalBinding.Literal(bad))),roots).problem().orElseThrow().code());
  assertEquals("type_mismatch",project(repeated(new TerminalBinding.Literal(n(1))),roots).problem().orElseThrow().code());
 }
 @Test void pathFailuresRemainIllegalArgumentCompatibleAndMachineReadable(){
  var roots=r(Map.of("memory",new DataValue.ListValue(List.of(n(1)))));
  assertEquals(n(1),new ValuePath("memory",List.of()).index(0).resolve(roots));
  assertEquals(ValuePath.Failure.TYPE_MISMATCH,assertThrows(ValuePath.PathException.class,()->new ValuePath("memory",List.of()).key("x").resolve(roots)).failure());
  assertEquals(ValuePath.Failure.TYPE_MISMATCH,assertThrows(ValuePath.PathException.class,()->new ValuePath("memory",List.of()).index(0).index(0).resolve(roots)).failure());
  assertEquals(ValuePath.Failure.INDEX_OUT_OF_BOUNDS,assertThrows(ValuePath.PathException.class,()->new ValuePath("memory",List.of()).index(1).resolve(roots)).failure());
  assertThrows(IllegalArgumentException.class,()->new ValuePath("memory",List.of()).index(1).resolve(roots));
  assertEquals(ComputationException.Code.MISSING_FIELD,assertThrows(ComputationException.class,()->new ValuePath("output",List.of()).resolve(roots)).code());
 }
 @Test void emptyAndLargeRepeatsOnlyMaterializeVisiblePageAndClamp(){
  var roots=r(Map.of());var empty=project(repeated(new TerminalBinding.Literal(new DataValue.ListValue(List.of()))),roots);
  assertEquals(new TerminalProjection.Page(0,1,0),empty.page().orElseThrow());assertTrue(empty.children().isEmpty());
  var values=new ArrayList<DataValue>();for(int i=0;i<10001;i++)values.add(n(i));var source=new DataValue.ListValue(values);
  var d=repeated(new TerminalBinding.Literal(source));var first=TerminalProjection.project(d,roots,Map.of(),2,3);
  assertEquals(new TerminalProjection.Page(0,5001,10001),first.page().orElseThrow());assertEquals(2,first.children().size());
  var last=TerminalProjection.project(d,roots,Map.of(first.instance(),Integer.MAX_VALUE),2,2);
  assertEquals(new TerminalProjection.Page(5000,5001,10001),last.page().orElseThrow());assertEquals(n(10000),last.children().getFirst().value().orElseThrow());
  assertEquals(0,TerminalProjection.project(d,roots,Map.of(first.instance(),-5),2,3).page().orElseThrow().index());
  assertEquals(values,source.values());
  assertThrows(IllegalArgumentException.class,()->TerminalProjection.project(d,roots,Map.of(),2,2));
  assertThrows(IllegalArgumentException.class,()->TerminalProjection.project(d,roots,Map.of(),0,3));
  assertThrows(IllegalArgumentException.class,()->TerminalProjection.project(d,roots,Map.of(),2,0));
 }
 @Test void nestedRepeatsUseCollisionFreeInstancePagesAndShadowRow(){
  var innerA=new DataValue.ListValue(List.of(n(0),n(1),n(2)));var innerB=new DataValue.ListValue(List.of(n(10),n(11),n(12)));
  var source=new DataValue.ListValue(List.of(innerA,innerB));var nodes=new LinkedHashMap<String,TerminalDefinition.Node>();
  nodes.put("r/:0",new TerminalDefinition.Repeat(new TerminalBinding.Literal(source),"t[0]/:中文"));
  nodes.put("t[0]/:中文",new TerminalDefinition.Repeat(path("row"),"leaf:/:1"));nodes.put("leaf:/:1",new TerminalDefinition.Text(path("row")));
  var d=new TerminalDefinition("r/:0",nodes);var first=project(d,r(Map.of()));var a=first.children().get(0);var b=first.children().get(1);
  assertNotEquals(a.instance(),b.instance());
  var paged=TerminalProjection.project(d,r(Map.of()),Map.of(a.instance(),1),2,20);
  assertEquals(n(2),paged.children().get(0).children().getFirst().value().orElseThrow());
  assertEquals(n(10),paged.children().get(1).children().getFirst().value().orElseThrow());
  var ids=new HashSet<String>();var stack=new ArrayDeque<TerminalProjection.View>();stack.push(first);
  while(!stack.isEmpty()){var v=stack.pop();assertTrue(ids.add(v.instance()));stack.addAll(v.children());}
  assertEquals(new DataValue.ListValue(List.of(innerA,innerB)),source);
 }
 @Test void projectsDeepLayoutIterativelyAndEnforcesExactBudget(){
  var d=TerminalDefinitionTest.deep(2000);var view=assertDoesNotThrow(()->TerminalProjection.project(d,r(Map.of()),Map.of(),1,2001));
  int depth=0;while(!view.children().isEmpty()){view=view.children().getFirst();depth++;}assertEquals(2000,depth);
  assertThrows(IllegalArgumentException.class,()->TerminalProjection.project(d,r(Map.of()),Map.of(),1,2000));
 }
}
