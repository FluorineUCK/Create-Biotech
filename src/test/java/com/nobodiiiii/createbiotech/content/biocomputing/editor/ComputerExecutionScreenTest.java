package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;import java.lang.reflect.Field;import java.util.*;import net.minecraft.client.Minecraft;import net.minecraft.client.gui.Font;import net.minecraft.client.gui.components.EditBox;import net.minecraft.network.chat.*;import net.minecraft.network.chat.contents.TranslatableContents;import net.minecraft.resources.ResourceLocation;import org.junit.jupiter.api.*;
class ComputerExecutionScreenTest {

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"en_us,Set total,Unknown", "zh_cn,将 total 设为,未知"})
 void debuggerUsesInstalledAssignmentAndKeepsRawFaultLocationInAdvanced(String locale, String assignment, String unknown) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var screen=screen(640,360);
     var menu=(ComputerProgramMenu)get(screen,"menu");
     var workspace=new ProgramWorkspace();
     var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
     var bindings=new FlowCallBindings(Map.of(),Map.of("value","total"));
     var graph=new ComputationGraph(Map.of(),Map.of(
         "twenty",new ComputationGraph.Constant(new DataValue.IntegerValue(20)),
         "two",new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
         "zero",new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
         "sum",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","twenty","right","two")),
         "ratio",new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER,Map.of("left","sum","right","zero"))),
         Map.of("value","ratio"));
     draft.append(new FlowProgram.Evaluate("assign_calc","unused",bindings));
     draft.editInline(new FlowProgram.Evaluate("assign_calc","unused",bindings),graph,draft.snapshot().canvas().values());
     workspace.setFlow(Optional.of(draft.snapshot()));
     var nonce=UUID.randomUUID(); var limits=SubgraphWorkspaceTest.LIMITS;
     set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"latestIntent",1L);
     var hardware=new ResourceLimits(1000,65_536,32);
     menu.accept(new ProgramEditorResponse(1,nonce,1,7,"ready","",hardware,limits,ProgramDocumentWire.encode(workspace.snapshot(),limits)));
     draft.editInline("assign_calc",graph,new FlowCallBindings(Map.of(),Map.of("value","draft_only")));
     workspace.setFlow(Optional.of(draft.snapshot()));
     // An uninstalled editor document has the same source id, but must not provide the observed variable name.
     set(menu,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,7,"ready","",hardware,limits,ProgramDocumentWire.encode(workspace.snapshot(),limits))));
     set(menu,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,1,7,"ok","",new byte[0])));
     set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of(
       "mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),
       "phase",new DataValue.TextValue("FAILED"),"source",new DataValue.TextValue("assign_calc"),
       "diagnostic",new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("DIVISION_BY_ZERO"),
         "location",new DataValue.TextValue("assign_calc"),"message",new DataValue.TextValue("Integer division by zero")))))));
     screen.init();
     String lines=String.join("",(List<String>)get(screen,"statusLines"));
     assertTrue(lines.contains(assignment) && lines.contains("(20 + 2) ÷ 0"),lines);
     assertTrue(lines.contains("DIVISION_BY_ZERO"),lines);
     assertEquals(lines.indexOf(assignment), lines.lastIndexOf(assignment), "Do not repeat the current block in its own error");
     assertFalse(lines.contains("assign_calc") || lines.contains("draft_only"),lines);
     set(screen,"observationTab",2);
     screen.init();
     assertTrue(String.join("",(List<String>)get(screen,"statusLines")).contains("assign_calc"));
     set(screen,"observationTab",0);
     set(menu,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,2,8,"ok","",new byte[0])));
     screen.init();
     lines=String.join("",(List<String>)get(screen,"statusLines"));
     assertFalse(lines.contains(assignment) || lines.contains("draft_only"),lines);
     assertTrue(lines.contains(unknown),lines);
   } finally { net.minecraft.locale.Language.inject(old); }
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"en_us,Wait,seconds,Nested expression","zh_cn,等待,秒,嵌套表达式"})
 void debuggerNamesInlineOperandsFromTheInstalledScratchBlock(String locale, String verb, String unit, String expression) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var screen=screen(640,360);
     var menu=(ComputerProgramMenu)get(screen,"menu");
     var workspace=new ProgramWorkspace();
     workspace.root().putCell("machine_delay", DataType.Scalar.INTEGER, new DataValue.IntegerValue(45));
     var draft=new FlowDraft(new FlowDocumentBody(Map.of("selected_delay", DataType.Scalar.INTEGER), List.of(),
       new DataValue.RecordValue(Map.of("selected_delay", new DataValue.IntegerValue(40))),
       FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1));
     var number=new ComputationGraph.Constant(new DataValue.IntegerValue(180));
     var nodes=Map.<String,Map<String,ComputationGraph.Node>>of(
       "wait_value",Map.of("root",number),
       "wait_private",Map.of("root",new ComputationGraph.Input("saved_port")),
       "wait_public",Map.of("root",new ComputationGraph.ReadMemory("machine_delay")),
       "wait_expression",Map.of("x",number,"y",number,"root",
         new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left","x","right","y"))));
     for (var entry : nodes.entrySet()) {
       String id=entry.getKey(); boolean privateValue=id.equals("wait_private");
       draft.append(new FlowProgram.Sleep(id,new FlowInput.Literal(new DataValue.IntegerValue(1)),Optional.of(FlowProgram.WaitUnit.SECONDS)));
       var input=privateValue ? Map.<String,DataType>of("saved_port",DataType.Scalar.INTEGER) : Map.<String,DataType>of();
       var arguments=privateValue ? Map.of("saved_port","selected_delay") : Map.<String,String>of();
       draft.editInline(id,new ComputationGraph(input,entry.getValue(),Map.of("value","root")),new FlowCallBindings(arguments,Map.of()));
     }
     workspace.setFlow(Optional.of(draft.snapshot()));
     var nonce=UUID.randomUUID(); var limits=SubgraphWorkspaceTest.LIMITS;
     set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"latestIntent",1L);
     menu.accept(new ProgramEditorResponse(1,nonce,1,7,"ready","",new ResourceLimits(1000,65_536,32),
         limits,ProgramDocumentWire.encode(workspace.snapshot(),limits)));
     set(menu,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,1,7,"ok","",new byte[0])));
     var sourceLabels=Map.of("wait_value","180","wait_private","selected_delay","wait_expression",expression,
       "wait_public",Component.translatable("create_biotech.biocomputing.flow_editor.inline_public","machine_delay").getString());
     for (var entry : sourceLabels.entrySet()) {
       set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),
         "available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("SLEEPING"),
         "source",new DataValue.TextValue(entry.getKey())))));
       screen.init();
       String lines=String.join("",(List<String>)get(screen,"statusLines"));
       assertTrue(lines.contains(verb+" ["+entry.getValue()+"] "+unit),lines);
       assertFalse(lines.contains("inline_"+entry.getKey()) || lines.contains("saved_port"),lines);
     }
   } finally { net.minecraft.locale.Language.inject(old); }
 }
 @Test void debuggerNamesTheInstalledExtensionHatEvenInsideItsFixedPredicate() throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
   net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
   var s=screen(640,360); var menu=(ComputerProgramMenu)get(s,"menu"); var nonce=UUID.randomUUID();
   var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
   draft.setExtension("create_biotech:signals",1L);
   var key=new com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key("cb_extension:create_biotech:signals:when_signal",1);
   var binding=com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(key).orElseThrow();
   var hat=ScratchExtensionMouth.prototype(draft,binding);
   draft.place(hat.steps(),null,new ProgramDocument.Position(16,50),hat.graphs());
   draft.ensureTemplate(binding.template("extension"));
   var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
   var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
   set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"latestIntent",1L);
   menu.accept(new ProgramEditorResponse(1,nonce,1,7,"ready","",new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,8),
     limits,ProgramDocumentWire.encode(workspace.snapshot(),limits)));
   set(menu,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,1,7,"ok","",new byte[0])));
   var fixedSource=FlowProcedureTools.definitions(draft.snapshot()).stream().filter(def->def.key().equals(key)).findFirst().orElseThrow().sourceId();
   for(String source:List.of(hat.steps().get(1).sourceId(),fixedSource)) {
     set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),
       "available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("COMPUTING"),"source",new DataValue.TextValue(source)))));
     s.init();
     String overview=String.join("",(List<String>)get(s,"statusLines"));
     assertTrue(overview.contains(Component.translatable(binding.command().labelKey()).getString()),overview);
     assertFalse(overview.contains(key.id()),overview);
   }
   } finally { net.minecraft.locale.Language.inject(old); }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints = {320, 640})
 void defaultViewHasSingleLeftControlGroupAndNoInputForm(int width) throws Exception {
   var s=screen(width,width==320?240:360);
   assertTrue(boxes(s).stream().noneMatch(b->b.visible), "Input editing is opt-in");
   var buttons=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.visible).toList();
   var primary=buttons.stream().filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".unavailable")).toList();
   assertEquals(1,primary.size(), "Only one primary control");
   int split=getInt(s,"leftPos")+getInt(s,"imageWidth")/2;
   for(String key:List.of("read_peripherals","read_network","communication","periodic")) {
     var control=buttons.stream().filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith("."+key)).findFirst().orElseThrow();
     assertTrue(control.getX()+control.getWidth()<=split, key+" stays in operation column");
   }
   assertTrue(buttons.stream().anyMatch(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".overview")));
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
 void taskInspectorIsReachableWithoutChangingInputsAndFitsTheWindow(int width) throws Exception {
   var screen=screen(width,240); var menu=(ComputerProgramMenu)get(screen,"menu");
   set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),
     "available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("WAITING_READ")))));
   var inputs=(Map<String,DataValue>)get(screen,"inputs");inputs.put("editing",new DataValue.TextValue("keep me"));
   set(screen,"observationTab",1);
   net.minecraft.client.gui.screens.Screen[] opened={null};
   set(screen,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(next)->opened[0]=next);
   screen.init(); click(screen,"task_debug");
   assertInstanceOf(ComputerTaskDebugScreen.class,opened[0]);
   assertEquals(new DataValue.RecordValue(inputs),get(menu,"debuggerInputs"));
   var inspector=(ComputerTaskDebugScreen)opened[0];
   set(inspector,"width",width);set(inspector,"height",240);set(inspector,"font",get(screen,"font"));set(inspector,"minecraft",get(screen,"minecraft"));
   set(inspector,"rows",List.of(new DataValue.RecordValue(Map.of("procedure",new DataValue.TextValue("long_task_name".repeat(10)),
     "revision",new DataValue.IntegerValue(1),"phase",new DataValue.TextValue("WAITING_READ"),"paused",new DataValue.BooleanValue(false)))));
   set(inspector,"view",7L);set(inspector,"requested",true);
   var actions=new ArrayList<ComputerControlSession.Action>();var requests=new ArrayList<DataValue.RecordValue>();
   set(inspector,"sender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(action,value)->{actions.add(action);requests.add(value);});
   inspector.init();
   var buttons=inspector.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.visible).toList();
   for(var b:buttons){assertTrue(b.getX()>=0&&b.getY()>=0);assertTrue(b.getX()+b.getWidth()<=width);assertTrue(b.getY()+b.getHeight()<=240);}
   for(int i=0;i<buttons.size();i++)for(int j=i+1;j<buttons.size();j++){var x=buttons.get(i);var y=buttons.get(j);
     assertFalse(x.getX()<y.getX()+y.getWidth()&&x.getX()+x.getWidth()>y.getX()&&x.getY()<y.getY()+y.getHeight()&&x.getY()+x.getHeight()>y.getY());}
   buttons.getFirst().onClick(0,0);
   assertEquals(List.of(ComputerControlSession.Action.READ_TASK_DEBUG),actions);
   assertEquals(TaskDebugView.taskRequest(7,0),requests.getFirst());
   var frozen=new DataValue.RecordValue(Map.of("procedure",new DataValue.TextValue("long_task_name"),
     "revision",new DataValue.IntegerValue(1),"phase",new DataValue.TextValue("WAITING_READ"),
     "paused",new DataValue.BooleanValue(false),"locals",new DataValue.RecordValue(Map.of("message",new DataValue.TextValue("snapshot")))));
   set(menu,"latestControlAction",ComputerControlSession.Action.READ_TASK_DEBUG);set(menu,"latestControlIntent",8L);
   set(menu,"controlResponse",Optional.of(new ProgramStatusResponse(1,UUID.randomUUID(),8,1,"ok","",new byte[0])));
   set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of("debug_task",frozen))));set(menu,"controlResponseVersion",1L);
   inspector.containerTick();
   assertInstanceOf(ComputerValueTreeScreen.class,opened[0]);
   assertNull(get(opened[0],"generate"),"Task observation cannot generate editing or world-writing operations");
   var tree=(ValueTree)get(opened[0],"tree");
   var observed=tree.inputs();
   frozen.fields().forEach((key,value)->assertEquals(value,observed.field(key)));
   assertTrue(observed.fields().containsKey(Component.translatable("create_biotech.biocomputing.debug.task_current_block").getString()));
   set(menu,"status",Optional.of(new DataValue.RecordValue(Map.of())));assertEquals(observed,tree.inputs());
   assertEquals(1,actions.size(),"Opening and retaining a frozen tree does not poll, run or claim a task");
   assertEquals(new DataValue.RecordValue(inputs),get(menu,"debuggerInputs"));
 }

 @Test void mainControlDistinguishesUnstartedPausedRunningWaitingAndTerminalStates() throws Exception {
   var s=screen(640,360); var m=(ComputerProgramMenu)get(s,"menu");
   for (var row:List.of(
     List.of("READY",true,0L,"start",true), List.of("COMPUTING",true,12L,"resume",true),
     List.of("READY",true,12L,"resume",true), List.of("COMPUTING",false,12L,"pause",true),
     List.of("WAITING_READ",false,12L,"pause",true), List.of("WAITING_TASK",false,12L,"pause",true),
     List.of("COMPLETE",true,12L,"resume_scheduling",true), List.of("FAILED",true,12L,"resume_scheduling",true),
     List.of("UNAVAILABLE",false,0L,"not_installed",false))) {
     set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),
       "phase",new DataValue.TextValue((String)row.get(0)),"paused",new DataValue.BooleanValue((boolean)row.get(1)),"work",new DataValue.IntegerValue((long)row.get(2))))));
     s.init(); var b=(CreateTextButton)get(s,"mainControl");
     assertTrue(b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith("."+row.get(3)), row.toString());
     assertEquals(row.get(4),b.active,row.toString());
   }
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(false),"phase",new DataValue.TextValue("READY"))))); s.init();
   assertTrue(((CreateTextButton)get(s,"mainControl")).getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".unavailable"));
   assertFalse(((CreateTextButton)get(s,"mainControl")).active);
 }
 @Test void restartRequiresExplicitConfirmationAndCancelPreservesTheExecution() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   var status=new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),
     "phase",new DataValue.TextValue("COMPUTING"),"paused",new DataValue.BooleanValue(true),"work",new DataValue.IntegerValue(12)));
   set(m,"status",Optional.of(status)); s.init();
   var sent=new ArrayList<ComputerControlSession.Action>();
   set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
   click(s,"restart"); assertTrue(sent.isEmpty(),"Opening confirmation must not reset");
   click(s,"cancel_restart"); assertTrue(sent.isEmpty()); assertSame(status,m.status().orElseThrow());
   click(s,"restart"); s.onClose(); assertTrue(sent.isEmpty(),"Escape cancels, rather than closes debugger");
   click(s,"resume"); assertEquals(List.of(ComputerControlSession.Action.RESUME),sent);
   sent.clear(); click(s,"restart"); click(s,"confirm_restart");
   assertEquals(List.of(ComputerControlSession.Action.RESET),sent,"Confirm sends RESET only, not an implicit RUN");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void overviewUsesInstalledSourceAndHidesBackendUntilAdvanced(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(640,360); var m=(ComputerProgramMenu)get(s,"menu"); var nonce=UUID.randomUUID();
     var base=ProgramDocument.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of()),Map.of()));
     var body=new FlowDocumentBody(Map.of("ticks",com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER),
       List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("installed_sleep","ticks")),
       new DataValue.RecordValue(Map.of("ticks",new DataValue.IntegerValue(3))));
     var doc=new ProgramDocument(base.archive(),base.positions(),Optional.empty(),Optional.of(body));
     var draftBody=new FlowDocumentBody(Map.of("draft_ticks",com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER),
       List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("installed_sleep","draft_ticks")),
       new DataValue.RecordValue(Map.of("draft_ticks",new DataValue.IntegerValue(99))));
     set(get(s,"parent"),"workspace",new ProgramWorkspace(new ProgramDocument(base.archive(),base.positions(),Optional.empty(),Optional.of(draftBody))));
     var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
     set(m,"containerId",1); set(m,"nonce",nonce); set(m,"latestIntent",1L);
     m.accept(new ProgramEditorResponse(1,nonce,1,7,"ready","",new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,1),limits,ProgramDocumentWire.encode(doc,limits)));
     set(m,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,1,7,"ok","",new byte[0])));
     set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),
       "phase",new DataValue.TextValue("SLEEPING"),"paused",new DataValue.BooleanValue(true),"work",new DataValue.IntegerValue(12),
       "source",new DataValue.TextValue("installed_sleep"),"pc",new DataValue.IntegerValue(27),"execution",new DataValue.TextValue("register_v1"),
       "sleep_ticks",new DataValue.IntegerValue(5),"memory_units",new DataValue.IntegerValue(41))))); s.init();
     String overview=String.join("",(List<String>)get(s,"statusLines"));
     String installedLabel=Component.translatable("create_biotech.biocomputing.flow_editor.block_sleep","ticks").getString();
     assertTrue(overview.contains(installedLabel),overview);
     assertFalse(overview.contains("draft_ticks"),"A same-ID unsaved draft is not the installed running source");
     assertFalse(overview.contains("installed_sleep")); assertFalse(overview.contains("pc:")); assertFalse(overview.contains("register_v1"));
     set(m,"latestIntent",2L);
     m.accept(new ProgramEditorResponse(1,nonce,2,7,"invalid","Rejected draft",m.response().orElseThrow().hardware(),limits,new byte[0]));
     s.init();
     assertTrue(String.join("",(List<String>)get(s,"statusLines")).contains(installedLabel),
       "Rejecting a draft must not erase the acknowledged installed source");
     click(s,"advanced"); String advanced=String.join("",(List<String>)get(s,"statusLines"));
     assertTrue(advanced.contains("installed_sleep")); assertTrue(advanced.contains("pc: 27")); assertTrue(advanced.contains("register_v1"));
     click(s,"overview"); set(m,"controlResponse",Optional.of(new ProgramStatusResponse(1,nonce,2,8,"ok","",new byte[0]))); s.init();
     String missing=String.join("",(List<String>)get(s,"statusLines"));
     assertTrue(missing.contains(Component.translatable("create_biotech.biocomputing.debug.source_unknown").getString()),missing);
     assertFalse(missing.contains("installed_sleep") || missing.contains(installedLabel),"An outdated archive is not the running source");
     var requests=new ArrayList<ProgramEditorRequest>(); m.requestInstalledSource(8,requests::add);
     assertEquals(1,requests.size()); var sourceRequest=requests.getFirst();
     var feedback=m.response().orElseThrow(); long feedbackVersion=m.responseVersion();
     m.accept(new ProgramEditorResponse(1,UUID.randomUUID(),sourceRequest.intent(),8,"ready","",feedback.hardware(),limits,ProgramDocumentWire.encode(doc,limits)));
     assertTrue(m.installedSource(8).isEmpty(),"Another session cannot provide the source");
     m.accept(new ProgramEditorResponse(1,nonce,sourceRequest.intent(),8,"ready","",feedback.hardware(),limits,ProgramDocumentWire.encode(doc,limits)));
     set(s,"requested",true); s.containerTick();
     assertTrue(String.join("",(List<String>)get(s,"statusLines")).contains(installedLabel),
       "An arriving source read refreshes labels even without a new control response");
     assertSame(feedback,m.response().orElseThrow()); assertEquals(feedbackVersion,m.responseVersion());
   } finally { net.minecraft.locale.Language.inject(old); }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void completedProgramHasNoCurrentBlockRatherThanAMissingSourceMapping(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
     set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),
       "phase",new DataValue.TextValue("COMPLETE"),"source",DataValue.NullValue.INSTANCE)))); s.init();
     var lines=String.join("",(List<String>)get(s,"statusLines"));
     assertFalse(lines.contains(Component.translatable("create_biotech.biocomputing.debug.source_unknown").getString()),lines);
     assertTrue(lines.contains(Component.translatable("create_biotech.biocomputing.debug.source_inactive").getString()),lines);
     set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),
       "phase",new DataValue.TextValue("FAILED"),"source",DataValue.NullValue.INSTANCE)))); s.init();
     assertTrue(String.join("",(List<String>)get(s,"statusLines")).contains(Component.translatable("create_biotech.biocomputing.debug.source_unknown").getString()),
       "Missing fault locations must not be described as successful completion");
   } finally { net.minecraft.locale.Language.inject(old); }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"320,en_us", "640,zh_cn"})
 void variableRowsKeepLargeDeviceRepliesCompactAndFullValuesInTheExistingInspector(int width, String locale) throws Exception {
   var oldLanguage = net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s = screen(width, 360);
     var menu = (ComputerProgramMenu)get(s, "menu");
     String tail = "deliberate-tail-never-in-short-summary";
     var rawText = new DataValue.TextValue("😀\t§".repeat(200) + tail);
     var reply = new DataValue.RecordValue(Map.of("payload", rawText, "code", new DataValue.TextValue("ok")));
     var list = new DataValue.ListValue(List.of(reply, new DataValue.IntegerValue(19)));
     var nbt = new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of("native_payload", new DataValue.NbtValue(8, rawText))));
     var locals = new DataValue.RecordValue(Map.of("reply", reply, "items", list, "native", nbt, "long_text", rawText,
         "fault", new DataValue.ErrorValue("timeout", rawText.value())));
     var shared = new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(7)));
     set(menu, "status", Optional.of(new DataValue.RecordValue(Map.of("phase", new DataValue.TextValue("COMPLETE"),
         "locals", locals, "memory", shared))));
     var opened = new ArrayList<net.minecraft.client.gui.screens.Screen>();
     set(s, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)opened::add);
     click(s, "variables");
     var lines = (List<String>)get(s, "statusLines");
     assertEquals(8, lines.size(), "Two section headings and six variable rows");
     String content = String.join("\n", lines);
     for (String kind : List.of("record", "list", "nbt", "error"))
       assertTrue(content.contains(Component.translatable("create_biotech.biocomputing.tree.type." + kind).getString()), content);
     assertTrue(content.contains("#10") && content.contains("timeout") && content.contains("counter: 7"), content);
     assertFalse(content.contains(tail) || content.contains("payload") || content.contains("\t") || content.contains("§"), content);
     assertTrue(lines.stream().anyMatch(line -> line.startsWith("long_text: ") && line.endsWith("…")));
     int rightWidth = getInt(s, "rightWidth");
     assertTrue(lines.stream().allMatch(line -> Minecraft.getInstance().font.width(line) <= rightWidth));
     click(s, "inspect_private");
     var inspector = assertInstanceOf(ComputerValueTreeScreen.class, opened.getLast());
     assertNull(get(inspector, "generate"));
     var snapshot = ((ValueTree)get(inspector, "tree")).inputs();
     assertEquals(locals, snapshot);
     assertEquals(rawText, ((DataValue.RecordValue)snapshot.field("reply")).field("payload"));
     click(s, "inspect_public");
     assertEquals(shared, ((ValueTree)get(opened.getLast(), "tree")).inputs());
   } finally { net.minecraft.locale.Language.inject(oldLanguage); }
 }
 @Test void variablesAreGroupedAndRecordOrListInspectionIsFrozenAndReadOnly() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   var privateValues=new DataValue.RecordValue(Map.of("packet",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("payload",new DataValue.TextValue("hello")))))));
   var publicValues=new DataValue.RecordValue(Map.of("counter",new DataValue.IntegerValue(7)));
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("COMPUTING"),
      "paused",new DataValue.BooleanValue(true),"work",new DataValue.IntegerValue(12),"locals",privateValues,"memory",publicValues,"pc",new DataValue.IntegerValue(27))))); s.init();
   var opened=new ArrayList<net.minecraft.client.gui.screens.Screen>();
   set(s,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)opened::add);
   click(s,"variables"); click(s,"inspect_private");
   var inspector=(ComputerValueTreeScreen)opened.getLast();
   assertNull(get(inspector,"generate"),"Variable inspection cannot generate/edit/install a draft");
   var tree=(ValueTree)get(inspector,"tree"); assertEquals(privateValues,tree.inputs());
   var packet=tree.page(0,8).rows().getFirst(); tree.toggle(packet.path());
   assertTrue(tree.page(0,8).rows().stream().anyMatch(row->row.value() instanceof DataValue.RecordValue));
   click(s,"inspect_public"); assertEquals(publicValues,((ValueTree)get(opened.getLast(),"tree")).inputs());
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("phase",new DataValue.TextValue("IDLE"),"memory",publicValues)))); s.init();
   var privateButton=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
     .filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".inspect_private")).findFirst().orElseThrow();
   assertFalse(privateButton.active,"Compatibility status does not expose private execution frames");
   assertEquals(privateValues,tree.inputs(),"Opening a new snapshot never mutates the old frozen tree");
 }
 @Test void intentionalCancellationIsNotDisplayedAsAnExecutionError() throws Exception {
   var s=screen(640,360); var m=(ComputerProgramMenu)get(s,"menu");
   var diagnostic=new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("TASK_CANCELLED_ADMITTED")));
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("phase",new DataValue.TextValue("CANCELLED"),
     "paused",new DataValue.BooleanValue(true),"diagnostic",diagnostic)))); s.init();
   assertFalse(String.join("",(List<String>)get(s,"statusLines")).contains("TASK_CANCELLED_ADMITTED"));
   var opened=new ArrayList<net.minecraft.client.gui.screens.Screen>();
   set(s,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)opened::add);
   click(s,"inspect_snapshot");
   assertFalse(((ValueTree)get(opened.getLast(),"tree")).inputs().fields().containsKey("diagnostic"));
   click(s,"advanced"); click(s,"inspect_snapshot");
   assertEquals(diagnostic,((ValueTree)get(opened.getLast(),"tree")).inputs().fields().get("diagnostic"));
 }
 @Test void fullOverviewInspectionRetainsLongResultWithoutExposingRawBackendFields() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   var output=new DataValue.RecordValue(Map.of("message",new DataValue.TextValue("🧬".repeat(2000))));
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("phase",new DataValue.TextValue("SUCCEEDED"),"output",output,
     "pc",new DataValue.IntegerValue(44),"execution",new DataValue.TextValue("register_v1"),"memory",new DataValue.RecordValue(Map.of()))))); s.init();
   var opened=new ArrayList<net.minecraft.client.gui.screens.Screen>();
   set(s,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)opened::add);
   click(s,"inspect_snapshot"); var inspector=(ComputerValueTreeScreen)opened.getLast();
   var values=((ValueTree)get(inspector,"tree")).inputs();
   assertEquals(output,values.fields().get("output")); assertFalse(values.fields().containsKey("pc")); assertFalse(values.fields().containsKey("execution"));
   assertNull(get(inspector,"generate"));
   click(s,"advanced"); click(s,"inspect_snapshot");
   assertEquals(new DataValue.IntegerValue(44),((ValueTree)get(opened.getLast(),"tree")).inputs().fields().get("pc"));
   assertTrue(((CreateTextButton)get(s,"statusNext")).visible); assertTrue(((CreateTextButton)get(s,"statusNext")).active);
   assertEquals(output,values.fields().get("output"),"Long frozen results are never truncated or replaced");
 }
 @Test void captureReplyRetainsExecutionControlsButUnavailableReplyNeverShowsAResumableSnapshot() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu"); var nonce=UUID.randomUUID();
   var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
   set(m,"containerId",1); set(m,"nonce",nonce); set(m,"latestControlIntent",1L); set(m,"latestControlAction",ComputerControlSession.Action.READ_NETWORK); set(s,"requested",true);
   set(m,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,1),limits,new byte[0])));
   set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("COMPUTING"),
     "paused",new DataValue.BooleanValue(true),"work",new DataValue.IntegerValue(12))))); s.init();
   var frozen=new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(42)));
   m.accept(new ProgramStatusResponse(1,nonce,1,1,"ok","",ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("captured_inputs",new DataValue.RecordValue(Map.of("network",frozen)))),limits)));
   s.containerTick();
   var b=(CreateTextButton)get(s,"mainControl"); assertTrue(b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".resume"));
   var sent=new ArrayList<ComputerControlSession.Action>(); set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
   click(s,"resume"); assertEquals(List.of(ComputerControlSession.Action.RESUME),sent);
   assertEquals(frozen,((Map<String,DataValue>)get(s,"inputs")).get("network"));
   set(m,"latestControlIntent",2L); m.accept(new ProgramStatusResponse(1,nonce,2,1,"unavailable","hardware missing",new byte[0])); s.containerTick();
   assertTrue(b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".unavailable")); assertFalse(b.active);
 }
 @Test void realPrimaryAndStepWidgetsMapToExistingFlowAndCompatibilityActions() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   ((Map<String,DataValue>)get(s,"inputs")).put("count",new DataValue.IntegerValue(7));
   var sent=new ArrayList<ComputerControlSession.Action>(); var payloads=new ArrayList<DataValue.RecordValue>();
   set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->{sent.add(a);payloads.add(v);});
   for (var row:List.of(
     List.of(true,"READY",false,0L,"pause",ComputerControlSession.Action.PAUSE),
     List.of(true,"READY",true,0L,"start",ComputerControlSession.Action.RESUME),
     List.of(true,"READY",true,12L,"resume",ComputerControlSession.Action.RESUME),
     List.of(true,"WAITING_READ",true,12L,"resume",ComputerControlSession.Action.RESUME),
     List.of(true,"SLEEPING",false,12L,"pause",ComputerControlSession.Action.PAUSE),
     List.of(false,"IDLE",false,0L,"start",ComputerControlSession.Action.RUN),
     List.of(false,"RUNNING",true,12L,"resume",ComputerControlSession.Action.RESUME))) {
      var fields=new LinkedHashMap<String,DataValue>();
      if((boolean)row.get(0)) {fields.put("mode",new DataValue.TextValue("flow"));fields.put("available",new DataValue.BooleanValue(true));}
      fields.put("phase",new DataValue.TextValue((String)row.get(1)));fields.put("paused",new DataValue.BooleanValue((boolean)row.get(2)));fields.put("work",new DataValue.IntegerValue((long)row.get(3)));
      set(m,"status",Optional.of(new DataValue.RecordValue(fields)));s.init();sent.clear();payloads.clear();click(s,(String)row.get(4));
      assertEquals(List.of(row.get(5)),sent,row.toString()); assertEquals(new DataValue.IntegerValue(7),payloads.getLast().fields().get("count"));
      if(row.get(1).equals("IDLE")) {sent.clear();click(s,"step");assertEquals(List.of(ComputerControlSession.Action.START_PAUSED),sent);}
      else if((boolean)row.get(2)) {sent.clear();click(s,"step");assertEquals(List.of(ComputerControlSession.Action.STEP),sent);}
      else assertFalse(((CreateTextButton)get(s,"stepControl")).active);
   }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"en_us,320,240","en_us,640,360","zh_cn,320,240","zh_cn,640,360"})
 void everyVisiblePanelFitsBothLanguagesAndManualRefreshPreservesTheInputEditor(String locale,int width,int height) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {
     net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(width,height);var m=(ComputerProgramMenu)get(s,"menu");
     set(m,"status",Optional.of(new DataValue.RecordValue(Map.of("mode",new DataValue.TextValue("flow"),"available",new DataValue.BooleanValue(true),"phase",new DataValue.TextValue("COMPUTING"),
       "paused",new DataValue.BooleanValue(true),"work",new DataValue.IntegerValue(12),"locals",new DataValue.RecordValue(Map.of()),"memory",new DataValue.RecordValue(Map.of()),
       "diagnostic",new DataValue.TextValue("long_problem_".repeat(80))))));s.init(); assertVisibleWidgetsFit(s);
     for(String tab:List.of("variables","advanced","overview")){click(s,tab);assertVisibleWidgetsFit(s);}
     click(s,"restart");assertVisibleWidgetsFit(s);click(s,"cancel_restart");
     click(s,"inputs");assertVisibleWidgetsFit(s);
     assertFalse(((CreateTextButton)get(s,"inputPrevious")).visible);assertFalse(((CreateTextButton)get(s,"inputNext")).visible);
     var editors=boxes(s);editors.getFirst().setValue("unfinished name");editors.getLast().setValue("not yet saved");s.setFocused(editors.getFirst());
     var sent=new ArrayList<ComputerControlSession.Action>();set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
     click(s,"refresh");assertEquals(List.of(ComputerControlSession.Action.REFRESH),sent);
     set(s,"requested",true);set(m,"controlResponseVersion",1L);s.containerTick();
     assertSame(editors.getFirst(),boxes(s).getFirst());assertSame(editors.getLast(),boxes(s).getLast());assertSame(editors.getFirst(),s.getFocused());
     assertEquals("unfinished name",editors.getFirst().getValue());assertEquals("not yet saved",editors.getLast().getValue());
     s.init(); assertSame(editors.getFirst(),boxes(s).getFirst());assertSame(editors.getFirst(),s.getFocused());assertVisibleWidgetsFit(s);
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 private static void assertVisibleWidgetsFit(ComputerExecutionScreen s) throws Exception {
   var widgets=s.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
     .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).filter(w->w.visible).toList();
   for(var b:widgets){
     assertTrue(b.getX()>=getInt(s,"leftPos") && b.getY()>=getInt(s,"topPos"),b.getMessage().getString());
     assertTrue(b.getX()+b.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth"),b.getMessage().getString());
     assertTrue(b.getY()+b.getHeight()<=getInt(s,"topPos")+getInt(s,"imageHeight"),b.getMessage().getString());
     assertFalse(b.getMessage().getString().contains("create_biotech"),"Every visible label must resolve");
   }
   for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){
     var a=widgets.get(i);var b=widgets.get(j);
     assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY(),a.getMessage().getString()+" overlaps "+b.getMessage().getString());
   }
 }
 @Test void longTitlesAndInputNamesAreClippedInsideWidgetsWithCompleteHoverText() throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try { net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
   var s=inputScreen(320,240); String longTitle="Computer name "+"long_title_".repeat(30);set(s,"title",Component.literal(longTitle));
   String longName="very_long_input_name_".repeat(30);((Map<String,DataValue>)get(s,"inputs")).put(longName,new DataValue.IntegerValue(7));s.init();
   var header=s.children().stream().filter(net.minecraft.client.gui.components.StringWidget.class::isInstance).map(net.minecraft.client.gui.components.StringWidget.class::cast)
     .filter(w->w.getMessage().getString().contains(longTitle)).findFirst().orElseThrow();
   assertTrue(Minecraft.getInstance().font.width(header.getMessage())>header.getWidth());assertTrue(tooltipText(header).contains(longTitle));
   var row=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getString().equals(longName)).findFirst().orElseThrow();
   assertEquals(longName,tooltipText(row));assertTrue(row.getX()+row.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth")-8);
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void executionPanelTranslationsResolveAndRetainEveryFormattingArgument(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();var values=ComputerUiTranslationsTest.load(locale);
   assertEquals(locale.equals("zh_cn") ? "返回编辑器" : "Back to editor", values.get("create_biotech.biocomputing.debug.back"));
   try {net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
     var expected=ComputerUiTranslationsTest.load("en_us");
     for(var entry:expected.entrySet()) if(entry.getKey().startsWith("create_biotech.biocomputing.debug.")) {
       assertTrue(values.containsKey(entry.getKey()),locale+" "+entry.getKey());
       int count=(int)java.util.regex.Pattern.compile("%s").matcher(entry.getValue()).results().count();
       assertEquals(count,java.util.regex.Pattern.compile("%s").matcher(values.get(entry.getKey())).results().count(),entry.getKey());
       Object[] args=java.util.stream.IntStream.range(0,count).mapToObj(i->"[arg"+i+"]").toArray();
       String rendered=Component.translatable(entry.getKey(),args).getString();
       assertEquals(values.get(entry.getKey()).formatted(args),rendered,entry.getKey());assertFalse(rendered.contains("create_biotech"));
     }
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void terminalResultStillAllowsExplicitOwnerSchedulingAndBackgroundStepsWithoutRestart(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(320,240);var m=(ComputerProgramMenu)get(s,"menu");var sent=new ArrayList<ComputerControlSession.Action>();
     set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
     for(var row:List.of(List.of(false,"SUCCEEDED"),List.of(false,"FAILED"),List.of(true,"COMPLETE"),List.of(true,"FAILED"))) {
       for(boolean paused:List.of(true,false)) {
         var fields=new LinkedHashMap<String,DataValue>();if((boolean)row.getFirst()){fields.put("mode",new DataValue.TextValue("flow"));fields.put("available",new DataValue.BooleanValue(true));}
         fields.put("phase",new DataValue.TextValue((String)row.getLast()));fields.put("paused",new DataValue.BooleanValue(paused));fields.put("work",new DataValue.IntegerValue(12));
         var memory=new DataValue.RecordValue(Map.of("committed",new DataValue.IntegerValue(7)));fields.put("memory",memory);
         var status=new DataValue.RecordValue(fields);set(m,"status",Optional.of(status));s.init();sent.clear();
         var primary=(CreateTextButton)get(s,"mainControl");assertTrue(primary.active,"Completed/failed main result is not proof that owner work is finished");
         String key=paused?"resume_scheduling":"pause_scheduling";click(s,key);
         assertEquals(List.of(paused?ComputerControlSession.Action.RESUME:ComputerControlSession.Action.PAUSE),sent);
         String overview=String.join("",(List<String>)get(s,"statusLines"));
         assertTrue(overview.contains(Component.translatable("create_biotech.biocomputing.debug.state."+((String)row.getLast()).toLowerCase(Locale.ROOT)).getString()),overview);
         assertTrue(overview.contains(Component.translatable("create_biotech.biocomputing.debug.terminal_schedule_hint").getString()),overview);
         assertFalse(tooltipText(primary).contains("create_biotech"));
         assertSame(status,m.status().orElseThrow());assertEquals(memory,m.status().orElseThrow().fields().get("memory"));
         assertTrue(((CreateTextButton)get(s,"stepControl")).active);sent.clear();click(s,"step");assertEquals(List.of(ComputerControlSession.Action.STEP),sent);
         assertEquals(Component.translatable("create_biotech.biocomputing.debug.terminal_step_hint").getString(),tooltipText((CreateTextButton)get(s,"stepControl")));
         assertVisibleWidgetsFit(s);
       }
     }
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void onlyRetainedHardwareMismatchFlowOffersConfirmedMaintenanceReset(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(320,240);var m=(ComputerProgramMenu)get(s,"menu");var sent=new ArrayList<ComputerControlSession.Action>();
     set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
     var fields=new LinkedHashMap<String,DataValue>();fields.put("mode",new DataValue.TextValue("flow"));fields.put("available",new DataValue.BooleanValue(false));
     fields.put("phase",new DataValue.TextValue("WAITING_WRITE"));fields.put("paused",new DataValue.BooleanValue(true));fields.put("cursor",new DataValue.IntegerValue(3));fields.put("work",new DataValue.IntegerValue(12));
     fields.put("locals",new DataValue.RecordValue(Map.of("packet",new DataValue.TextValue("retained"))));
     fields.put("diagnostic",new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("HARDWARE_MISMATCH"),"message",new DataValue.TextValue("Missing pin x0"))));
     var retained=new DataValue.RecordValue(fields);set(m,"status",Optional.of(retained));s.init();
     assertFalse(((CreateTextButton)get(s,"mainControl")).active);assertFalse(((CreateTextButton)get(s,"stepControl")).active);
     assertTrue(((CreateTextButton)get(s,"resetControl")).active,"Maintenance is distinct from execution availability");
     click(s,"restart");assertTrue(sent.isEmpty());assertEquals(Component.translatable("create_biotech.biocomputing.debug.restart_maintenance_hint").getString(),((Component)get(s,"restartExplanation")).getString());
     assertVisibleWidgetsFit(s);click(s,"cancel_restart");assertTrue(sent.isEmpty());assertSame(retained,m.status().orElseThrow());
     click(s,"restart");click(s,"confirm_restart");assertEquals(List.of(ComputerControlSession.Action.RESET),sent);sent.clear();
     for(String code:List.of("UNAVAILABLE","NO_PROGRAM","INVALID_SAVE","RESOURCE_LIMIT")) {
       var rejected=new LinkedHashMap<>(fields);rejected.put("diagnostic",new DataValue.RecordValue(Map.of("code",new DataValue.TextValue(code))));set(m,"status",Optional.of(new DataValue.RecordValue(rejected)));s.init();
       assertFalse(((CreateTextButton)get(s,"resetControl")).active,code+" is not the existing maintenance exception");
     }
     for(String missing:List.of("mode","cursor","work","locals")) {
       var rejected=new LinkedHashMap<>(fields);rejected.remove(missing);set(m,"status",Optional.of(new DataValue.RecordValue(rejected)));s.init();assertFalse(((CreateTextButton)get(s,"resetControl")).active,"No retained flow evidence: "+missing);
     }
     var noProgram=new LinkedHashMap<>(fields);noProgram.put("phase",new DataValue.TextValue("UNAVAILABLE"));set(m,"status",Optional.of(new DataValue.RecordValue(noProgram)));s.init();assertFalse(((CreateTextButton)get(s,"resetControl")).active);
     assertTrue(sent.isEmpty());
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void resourceMaintenanceRequiresServerCapabilityAndConfirmationWithoutEnablingExecution(String locale) throws Exception {
   var old=net.minecraft.locale.Language.getInstance();
   try {net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
     var s=screen(320,240);var m=(ComputerProgramMenu)get(s,"menu");var sent=new ArrayList<ComputerControlSession.Action>();
     set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
     var fields=new LinkedHashMap<String,DataValue>(); fields.put("mode",new DataValue.TextValue("flow"));
     fields.put("available",new DataValue.BooleanValue(false));fields.put("phase",new DataValue.TextValue("UNAVAILABLE"));
     fields.put("diagnostic",new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("RESOURCE_LIMIT"))));
     for(boolean permitted:List.of(false,true)) {
       fields.put("resource_reset_available",new DataValue.BooleanValue(permitted));var status=new DataValue.RecordValue(fields);
       set(m,"status",Optional.of(status));s.init();
       assertFalse(((CreateTextButton)get(s,"mainControl")).active);assertFalse(((CreateTextButton)get(s,"stepControl")).active);
       assertEquals(permitted,((CreateTextButton)get(s,"resetControl")).active);
       if(permitted) {
         click(s,"restart");assertTrue(sent.isEmpty());
         assertEquals(Component.translatable("create_biotech.biocomputing.debug.restart_resource_hint").getString(),((Component)get(s,"restartExplanation")).getString());
         assertFalse(((Component)get(s,"restartExplanation")).getString().contains("create_biotech"));
         assertVisibleWidgetsFit(s);click(s,"cancel_restart");assertTrue(sent.isEmpty());assertSame(status,m.status().orElseThrow());
         click(s,"restart");click(s,"confirm_restart");assertEquals(List.of(ComputerControlSession.Action.RESET),sent);sent.clear();
       }
     }
     fields.put("diagnostic",new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("INVALID_SAVE"))));
     set(m,"status",Optional.of(new DataValue.RecordValue(fields)));s.init();assertFalse(((CreateTextButton)get(s,"resetControl")).active);
   } finally {net.minecraft.locale.Language.inject(old);}
 }
 @Test void networkCaptureUpdatesOnlyItsOwnFrozenInputAndPreservesDraftsOnFailureOrStaleReply() throws Exception {
    var s=inputScreen(320,240); var menu=(ComputerProgramMenu)get(s,"menu"); var nonce=UUID.randomUUID();
    set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"latestControlIntent",2L);
    set(menu,"latestControlAction",ComputerControlSession.Action.READ_NETWORK); set(s,"requested",true);
    var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
    set(menu,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",
        new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,1),limits,new byte[0])));
    var input=boxes(s).getFirst(); input.setValue("typing"); s.setFocused(input);
    var inputs=(Map<String,DataValue>)get(s,"inputs"); inputs.put("keep",new DataValue.IntegerValue(9));
    var snapshot=new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(42)));
    var unrelated=new DataValue.RecordValue(Map.of("private",new DataValue.BooleanValue(true)));
    byte[] bytes=ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("captured_inputs",
        new DataValue.RecordValue(Map.of("network",snapshot,"peripherals",unrelated,"keep",unrelated)))),limits);
    menu.accept(new ProgramStatusResponse(1,nonce,1,1,"ok","",bytes)); s.containerTick(); assertFalse(inputs.containsKey("network"));
    menu.accept(new ProgramStatusResponse(1,nonce,2,1,"ok","",bytes)); s.containerTick();
    assertEquals(snapshot,inputs.get("network")); assertFalse(inputs.containsKey("peripherals"));
    assertEquals(new DataValue.IntegerValue(9),inputs.get("keep")); assertSame(input,s.getFocused()); assertEquals("typing",input.getValue());
    set(menu,"latestControlIntent",3L); menu.accept(new ProgramStatusResponse(1,nonce,3,1,"invalid","topology_unloaded",new byte[0])); s.containerTick();
    assertEquals(snapshot,inputs.get("network")); assertTrue(menu.capturedInputs().isEmpty());
    inputs.remove("network"); set(menu,"latestControlIntent",4L); set(menu,"latestControlAction",ComputerControlSession.Action.REFRESH);
    menu.accept(new ProgramStatusResponse(1,nonce,4,1,"ok","",bytes)); s.containerTick(); assertFalse(inputs.containsKey("network"));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints = {320, 640})
 void longFailureNoticeStaysInItsColumnAndRetainsFullTooltipWithoutStealingFocus(int width) throws Exception {
    var oldLanguage=net.minecraft.locale.Language.getInstance();
    try {
    net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
    var s=inputScreen(width, width==320?240:360); var menu=(ComputerProgramMenu)get(s,"menu"); var nonce=UUID.randomUUID();
    set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"latestControlIntent",1L); set(s,"requested",true);
    var input=boxes(s).getFirst(); input.setValue("unfinished input"); s.setFocused(input);
    String detail="HARDWARE_MISMATCH: missing pin "+"long_pin_".repeat(45);
    menu.accept(new ProgramStatusResponse(1,nonce,1,1,"unavailable",detail,new byte[0])); s.containerTick();
    var label=s.children().stream().filter(net.minecraft.client.gui.components.StringWidget.class::isInstance)
        .map(net.minecraft.client.gui.components.StringWidget.class::cast)
        .filter(w->w.getMessage().getString().contains(detail)).findFirst().orElseThrow();
    assertEquals(getInt(s,"leftPos")+8,label.getX());
    assertTrue(label.getX()+label.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth")-8);
    assertTrue(Minecraft.getInstance().font.width(label.getMessage())>label.getWidth(), "Exercise overflow, not short text");
    assertEquals(label.getMessage().getString(),tooltipText(label));
    assertFalse(label.active); assertSame(input,s.getFocused()); assertEquals("unfinished input",input.getValue());
    set(menu,"latestControlIntent",2L); menu.accept(new ProgramStatusResponse(1,nonce,2,1,"ok","",new byte[0])); s.containerTick();
    assertFalse(label.getMessage().getString().contains(detail));
    assertEquals(label.getMessage().getString(),tooltipText(label), "Old error tooltip is replaced with current result");
    } finally { net.minecraft.locale.Language.inject(oldLanguage); }
 }
 private static String tooltipText(net.minecraft.client.gui.components.AbstractWidget widget) {
    var result=new StringBuilder(); assertNotNull(widget.getTooltip());
    widget.getTooltip().updateNarration(new net.minecraft.client.gui.narration.NarrationElementOutput() {
        @Override public void add(net.minecraft.client.gui.narration.NarratedElementType type,
                net.minecraft.client.gui.narration.NarrationThunk<?> thunk) { thunk.getText(result::append); }
        @Override public net.minecraft.client.gui.narration.NarrationElementOutput nest() { return this; }
    });
    return result.toString();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints = {320, 640})
 void communicationAndRecentEventsRemainDistinctUsableEntriesBelowExecutionActions(int width) throws Exception {
    var s=screen(width,width==320?240:360);
    var buttons=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).toList();
    var communication=buttons.stream().filter(b->b.getMessage().getContents() instanceof TranslatableContents t
        && t.getKey().endsWith(".communication")).findFirst().orElseThrow();
    var events=buttons.stream().filter(b->b.getMessage().getContents() instanceof TranslatableContents t
        && t.getKey().equals("create_biotech.biocomputing.events.title")).findFirst().orElseThrow();
    var capture=buttons.stream().filter(b->b.getMessage().getContents() instanceof TranslatableContents t
        && t.getKey().endsWith(".read_network")).findFirst().orElseThrow();
    for (var b:List.of(communication,capture,events)) {
      assertEquals(getInt(s,"leftPos")+8,b.getX());
      assertTrue(b.getX()+b.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth")/2);
      assertTrue(b.getWidth()>=88);
    }
    assertNotEquals(communication.getY(),events.getY());
    assertNotEquals(capture.getY(),events.getY());
    assertNotNull(capture.getTooltip());
    var oldLanguage=net.minecraft.locale.Language.getInstance();
    try {
      for (String locale:List.of("en_us","zh_cn")) {
        net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
        for (var b:List.of(communication,capture,events)) {
          assertFalse(b.getMessage().getString().contains("create_biotech"));
          assertTrue(b.getMessage().getString().codePoints().map(cp->cp<128?6:12).sum()<=b.getWidth()-8);
        }
        assertFalse(tooltipText(capture).contains("create_biotech"));
        assertTrue(tooltipText(capture).contains("network"));
      }
    } finally { net.minecraft.locale.Language.inject(oldLanguage); }
    assertTrue(events.getX()+events.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth")-8);
    assertTrue(communication.active); assertTrue(events.active);
 }
 @Test void selectingSnapshotTooLargeForLiteralBoxNeverTruncatesTheFrozenValue() throws Exception {
    var s=inputScreen(320,240);var inputs=(Map<String,DataValue>)get(s,"inputs");
    var snapshot=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("x".repeat(9000))));
    inputs.put("peripherals",snapshot);s.init();clickLiteral(s,"peripherals");
    assertNull(get(s,"selectedName"));assertSame(snapshot,inputs.get("peripherals"));
    assertTrue(((Component)get(s,"notice")).getContents() instanceof TranslatableContents t && t.getKey().endsWith(".read_only"));
 }
 @Test void selectingCapturedProviderErrorsIsReadOnlyAndPreservesTheCurrentEdit() throws Exception {
    var s=inputScreen(320,240);boxes(s).get(0).setValue("count");boxes(s).get(1).setValue("7");click(s,"save");
    var inputs=(Map<String,DataValue>)get(s,"inputs");
    var snapshot=new DataValue.RecordValue(Map.of("readers",new DataValue.RecordValue(Map.of("0,0,0",new DataValue.RecordValue(Map.of("energy",new DataValue.ErrorValue("unsupported","no energy storage")))))));
    inputs.put("peripherals",snapshot);s.init();((CreateTextButton)get(s,"inputNext")).onClick(0,0);
    assertDoesNotThrow(()->clickLiteral(s,"peripherals"));
    assertSame(snapshot,inputs.get("peripherals"));assertEquals("count",get(s,"selectedName"));assertEquals("7",get(s,"valueText"));
    assertTrue(((Component)get(s,"notice")).getContents() instanceof TranslatableContents t && t.getKey().endsWith(".read_only"));
    click(s,"save");assertSame(snapshot,inputs.get("peripherals"));assertEquals(new DataValue.IntegerValue(7),inputs.get("count"));
 }
 @Test void onlyLatestSuccessfulCaptureInstallsFrozenInputWithoutLosingFocus() throws Exception {
    var s=inputScreen(320,240);var menu=(ComputerProgramMenu)get(s,"menu");var nonce=UUID.randomUUID();
    set(menu,"containerId",1);set(menu,"nonce",nonce);set(menu,"latestControlIntent",2L);
    var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
    set(menu,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,1),limits,new byte[0])));
    set(s,"requested",true);var box=boxes(s).getFirst();box.setValue("typing");s.setFocused(box);
    var snapshot=new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(42)));
    byte[] bytes=ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("captured_inputs",new DataValue.RecordValue(Map.of("peripherals",snapshot)))),limits);
    var inputs=(Map<String,DataValue>)get(s,"inputs");
    menu.accept(new ProgramStatusResponse(1,nonce,1,1,"ok","",bytes));s.containerTick();assertFalse(inputs.containsKey("peripherals"));
    set(menu,"latestControlAction",ComputerControlSession.Action.valueOf("READ_PERIPHERALS"));
    menu.accept(new ProgramStatusResponse(1,nonce,2,1,"ok","",bytes));s.containerTick();assertEquals(snapshot,inputs.get("peripherals"));
    assertSame(box,s.getFocused());assertEquals("typing",box.getValue());
    var reopened=inputScreen(320,240);set(reopened,"menu",menu);set(reopened,"inputsRestored",false);reopened.init();
    assertEquals(snapshot,((Map<String,DataValue>)get(reopened,"inputs")).get("peripherals"));
    set(menu,"latestControlIntent",3L);menu.accept(new ProgramStatusResponse(1,nonce,3,1,"too_large","",new byte[0]));s.containerTick();assertEquals(snapshot,inputs.get("peripherals"));
    inputs.remove("peripherals");set(menu,"latestControlIntent",4L);set(menu,"latestControlAction",ComputerControlSession.Action.REFRESH);
    menu.accept(new ProgramStatusResponse(1,nonce,4,1,"ok","",bytes));s.containerTick();assertFalse(inputs.containsKey("peripherals"));
 }
 @Test void compactCaptureButtonHasUsableWidth() throws Exception {
    var s=screen(320,240);
    var capture=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
      .filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".read_peripherals")).findFirst().orElseThrow();
    assertTrue(capture.getWidth() >= 68);
 }
 @Test void arrivingStatusRefreshesDisplayedValuesAndPaginationWithoutReplacingTypedWidgets() throws Exception {
    var screen=inputScreen(320,240);var menu=(ComputerProgramMenu)get(screen,"menu");var nonce=UUID.randomUUID();
    set(menu,"containerId",1);set(menu,"nonce",nonce);set(menu,"latestControlIntent",1L);
    var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000);
    set(menu,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",
        new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000,10000,1),limits,new byte[0])));
    set(screen,"requested",true);var input=boxes(screen).getFirst();input.setValue("still typing");screen.setFocused(input);
    var longStatus=new DataValue.RecordValue(Map.of("output",new DataValue.TextValue("value".repeat(1000))));
    menu.accept(new ProgramStatusResponse(1,nonce,1,1,"ok","",ProgramEditorWire.encodeValue(longStatus,limits)));
    screen.containerTick();
    assertSame(input,boxes(screen).getFirst());
    assertSame(input,screen.getFocused());assertSame(input,boxes(screen).getFirst());assertEquals("still typing",input.getValue());
    set(screen,"statusPage",8);set(menu,"latestControlIntent",2L);
    menu.accept(new ProgramStatusResponse(1,nonce,2,1,"too_large","explicitly unavailable",new byte[0]));
    screen.containerTick();assertSame(input,screen.getFocused());assertEquals("still typing",input.getValue());
 }
 private Object oldClient;
 @BeforeAll static void bootstrap()throws Exception{Class<?>t=Class.forName("net.neoforged.fml.loading.LoadingModList");if(t.getMethod("get").invoke(null)==null)t.getMethod("of",List.class,List.class,List.class,List.class,Map.class).invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();field(net.minecraft.server.Bootstrap.class,"isBootstrapped").setBoolean(null,true);}
 @BeforeEach void client()throws Exception{oldClient=field(Minecraft.class,"instance").get(null);var c=allocate(Minecraft.class);set(c,"font",new Metrics());field(Minecraft.class,"instance").set(null,c);}@AfterEach void restore()throws Exception{field(Minecraft.class,"instance").set(null,oldClient);}
 @Test void widgetSaveSelectEditAndRemovePreserveValuesAcrossInvalidInput()throws Exception{var s=inputScreen(640,480);boxes(s).get(0).setValue("count");boxes(s).get(1).setValue("7");click(s,"save");var inputs=(Map<String,DataValue>)get(s,"inputs");assertEquals(new DataValue.IntegerValue(7),inputs.get("count"));boxes(s).get(0).setValue("renamed");boxes(s).get(1).setValue("bad");click(s,"save");assertEquals(new DataValue.IntegerValue(7),inputs.get("count"));assertFalse(inputs.containsKey("renamed"));clickLiteral(s,"count");click(s,"remove");assertFalse(inputs.containsKey("count"));}
 private static ComputerExecutionScreen screen()throws Exception{return screen(640,480);}private static ComputerExecutionScreen screen(int width,int height)throws Exception{ComputerExecutionScreen s=allocate(ComputerExecutionScreen.class);ComputerProgramScreen parent=allocate(ComputerProgramScreen.class);var m=allocate(ComputerProgramMenu.class);set(m,"response",Optional.empty());set(m,"controlResponse",Optional.empty());set(m,"status",Optional.empty());set(m,"debuggerInputs",new DataValue.RecordValue(Map.of()));set(s,"editorInventory",allocate(net.minecraft.world.entity.player.Inventory.class));set(s,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(target)->{});set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->{});set(s,"parent",parent);set(s,"menu",m);set(s,"nameText","");set(s,"valueText","");set(s,"notice",Component.empty());set(s,"inputs",new LinkedHashMap<>());set(s,"title",Component.literal("Computer"));set(s,"playerInventoryTitle",Component.empty());set(s,"children",new ArrayList<>());set(s,"renderables",new ArrayList<>());set(s,"narratables",new ArrayList<>());set(s,"width",width);set(s,"height",height);set(s,"minecraft",Minecraft.getInstance());set(s,"font",Minecraft.getInstance().font);s.init();return s;}
 private static ComputerExecutionScreen inputScreen(int width,int height)throws Exception { var s=screen(width,height); click(s,"inputs"); return s; }
 private static List<EditBox> boxes(ComputerExecutionScreen s){return s.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();}
 private static void click(ComputerExecutionScreen s,String key){s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getContents()instanceof TranslatableContents t&&t.getKey().equals("create_biotech.biocomputing.debug."+key)).findFirst().orElseThrow().onClick(0,0);}private static void clickLiteral(ComputerExecutionScreen s,String value){s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getString().equals(value)).findFirst().orElseThrow().onClick(0,0);}
 private static Object get(Object o,String n)throws Exception{return field(o.getClass(),n).get(o);}private static int getInt(Object o,String n)throws Exception{return (int)get(o,n);}private static void set(Object o,String n,Object v)throws Exception{field(o.getClass(),n).set(o,v);}private static Field field(Class<?>t,String n)throws Exception{for(Class<?>c=t;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}@SuppressWarnings("removal")private static<T>T allocate(Class<T>t)throws Exception{return t.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null)).allocateInstance(t));}
 private static final class Metrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor{Metrics(){super(i->null,false);}@Override public java.util.function.Function<ResourceLocation,net.minecraft.client.gui.font.FontSet> create$getFonts(){return i->null;}@Override public int width(String v){return v.codePoints().map(cp->cp<128?6:12).sum();}@Override public int width(FormattedText v){return width(v.getString());}@Override public String plainSubstrByWidth(String v,int w){int end=0,used=0;while(end<v.length()){int cp=v.codePointAt(end),n=cp<128?6:12;if(used+n>w)break;used+=n;end+=Character.charCount(cp);}return v.substring(0,end);}@Override public String plainSubstrByWidth(String v,int w,boolean r){return r?v.substring(Math.max(0,v.length()-w)):plainSubstrByWidth(v,w);}}
 @Test void selectingTextRestoresRawTextAndMatchingKindForLosslessResave()throws Exception{var s=inputScreen(640,480);var inputs=(Map<String,DataValue>)get(s,"inputs");inputs.put("message",new DataValue.TextValue("hello"));s.init();clickLiteral(s,"message");assertEquals("hello",get(s,"valueText"));assertEquals(EditorValueFormat.Kind.TEXT.ordinal(),get(s,"kind"));click(s,"save");assertEquals(new DataValue.TextValue("hello"),inputs.get("message"));} @Test void compactWindowKeepsEveryButtonInsideAndNonOverlapping()throws Exception{var s=screen(320,240);var buttons=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).toList();for(var b:buttons){assertTrue(b.getX()>=getInt(s,"leftPos")&&b.getY()>=getInt(s,"topPos"));assertTrue(b.getX()+b.getWidth()<=getInt(s,"leftPos")+getInt(s,"imageWidth"));assertTrue(b.getY()+b.getHeight()<=getInt(s,"topPos")+getInt(s,"imageHeight"));}for(int i=0;i<buttons.size();i++)for(int j=i+1;j<buttons.size();j++){var a=buttons.get(i);var b=buttons.get(j);assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY(),a.getMessage().getString()+" overlaps "+b.getMessage().getString());}}
 @Test void watchingAScopedVariableRefreshesReadOnlyAndKeepsAnUnavailableWatch() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   set(s,"watches",new LinkedHashSet<>());
   var sent=new ArrayList<ComputerControlSession.Action>();
   set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(action,values)->sent.add(action));
   set(s,"requested",true);
   observe(s,m,1,"ok",Map.of("locals",new DataValue.RecordValue(Map.of("count",new DataValue.IntegerValue(7))),
       "memory",new DataValue.RecordValue(Map.of("count",new DataValue.IntegerValue(99)))));
   click(s,"variables"); click(s,"open_monitors");
   var privateRow=monitor(s,"monitor_private","count");
   var publicRow=monitor(s,"monitor_public","count");
   assertNotSame(privateRow,publicRow);
   privateRow.onClick(0,0);
   assertEquals(Component.literal("7"),((TranslatableContents)monitor(s,"monitor_private","count").getMessage().getContents()).getArgs()[2]);
   for(int i=0;i<30;i++)s.containerTick();
   assertEquals(List.of(ComputerControlSession.Action.REFRESH),sent,"Only one refresh can be outstanding");
   observe(s,m,2,"ok",Map.of("memory",new DataValue.RecordValue(Map.of("count",new DataValue.IntegerValue(100)))));
   assertEquals(Component.translatable("create_biotech.biocomputing.debug.monitor_missing"),monitorValue(s,"monitor_private","count"));
   assertEquals(Component.literal("100"),monitorValue(s,"monitor_public","count"));
   observe(s,m,3,"unavailable",Map.of());
   assertEquals(Component.translatable("create_biotech.biocomputing.debug.monitor_no_snapshot"),monitorValue(s,"monitor_private","count"));
   click(s,"close_monitors");
   for(int i=0;i<40;i++)s.containerTick();
   assertEquals(List.of(ComputerControlSession.Action.REFRESH),sent,"Closing the live view stops its reads");
 }
 @Test void selectedMonitorViewInspectsOnlyTheChosenScopedListAndKeepsPollingReadOnly() throws Exception {
   var s=screen(320,240); var m=(ComputerProgramMenu)get(s,"menu");
   set(s,"watches",new LinkedHashSet<>()); set(s,"requested",true);
   var opened=new ArrayList<net.minecraft.client.gui.screens.Screen>();
   set(s,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)opened::add);
   var sent=new ArrayList<ComputerControlSession.Action>();
   set(s,"controlSender",(java.util.function.BiConsumer<ComputerControlSession.Action,DataValue.RecordValue>)(a,v)->sent.add(a));
   var privateList=new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)));
   var publicList=new DataValue.ListValue(List.of(new DataValue.IntegerValue(42)));
   observe(s,m,1,"ok",Map.of("locals",new DataValue.RecordValue(Map.of("items",privateList)),
       "memory",new DataValue.RecordValue(Map.of("items",publicList))));
   click(s,"variables"); click(s,"open_monitors");
   monitor(s,"monitor_public","items").onClick(0,0);
   click(s,"monitor_selected_only");
   assertThrows(NoSuchElementException.class,()->monitor(s,"monitor_private","items"));
   assertNotNull(monitor(s,"monitor_public","items"));
   click(s,"monitor_inspect");
   var inspector=assertInstanceOf(ComputerValueTreeScreen.class,opened.getLast());
   assertNull(get(inspector,"generate"),"Viewing a watched list never edits it");
   assertEquals(Map.of("items",publicList),((ValueTree)get(inspector,"tree")).inputs().fields());
   observe(s,m,2,"ok",Map.of("locals",new DataValue.RecordValue(Map.of("items",privateList))));
   assertEquals(Component.translatable("create_biotech.biocomputing.debug.monitor_missing"),monitorValue(s,"monitor_public","items"));
   assertEquals(Map.of("items",publicList),((ValueTree)get(inspector,"tree")).inputs().fields(),"Already opened views remain frozen");
   assertFalse(s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
       .anyMatch(b->b.visible&&b.active&&b.getMessage().equals(Component.translatable("create_biotech.biocomputing.debug.monitor_inspect"))));
   click(s,"monitor_all");
   assertNotNull(monitor(s,"monitor_private","items"));
   for(int i=0;i<20;i++)s.containerTick();
   assertEquals(List.of(ComputerControlSession.Action.REFRESH),sent);
 }

 private static void observe(ComputerExecutionScreen s,ComputerProgramMenu m,long version,String code,Map<String,DataValue> groups) throws Exception {
   var fields=new LinkedHashMap<>(groups);fields.put("phase",new DataValue.TextValue("COMPUTING"));
   set(m,"status",code.equals("ok")?Optional.of(new DataValue.RecordValue(fields)):Optional.empty());
   set(m,"controlResponse",Optional.of(new ProgramStatusResponse(1,UUID.randomUUID(),version,1,code,"",new byte[0])));
   set(m,"controlResponseVersion",version);s.containerTick();
 }
 private static CreateTextButton monitor(ComputerExecutionScreen s,String scope,String name) {
   return s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
       .filter(b->b.visible&&b.getMessage().getContents() instanceof TranslatableContents t&&t.getKey().endsWith("."+scope)
           &&t.getArgs()[1].equals(name)).findFirst().orElseThrow();
 }
 private static Object monitorValue(ComputerExecutionScreen s,String scope,String name) {
   return ((TranslatableContents)monitor(s,scope,name).getMessage().getContents()).getArgs()[2];
 }
}
