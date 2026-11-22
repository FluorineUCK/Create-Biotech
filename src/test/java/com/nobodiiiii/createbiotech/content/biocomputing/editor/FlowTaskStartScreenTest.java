package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;

/** Headless real controls and preparation/owner integration, not rendered-client evidence. */
class FlowTaskStartScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void playerDragsExplicitTaskBindsArgumentAndHandleThenPreparesAndInstallsTheDocument(int width) throws Exception {
        var initial = new DataValue.RecordValue(Map.of("seed", new DataValue.IntegerValue(0), "value", new DataValue.IntegerValue(999)));
        var definition = FlowProcedureDocumentTest.definition();
        var layout = new FlowBlockLayout(new ProgramDocument.Position(16,16), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(280,16), List.of(definition, new FlowProgram.Sleep("wait","value"), new FlowProgram.EndProcedure("end")))));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            ((DataType.RecordType)DataType.typeOf(initial)).fields(), List.of(), initial, layout, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace,width); click(editor,"category_custom");
        assertNotNull(button(editor,"block_invoke")); if (button(editor,"palette_next").active) click(editor,"palette_next"); var prototype = button(editor,"block_task_start"); var canvas = get(editor,"canvas");
        double fx = prototype.getX()+10, fy = prototype.getY()+10, x = (int)get(canvas,"x")+24, y = (int)get(canvas,"y")+50;
        var before = workspace.snapshot(); editor.mouseClicked(fx,fy,0); editor.mouseDragged(x,y,0,x-fx,y-fy);
        assertEquals(before,workspace.snapshot()); editor.mouseReleased(x,y,0);
        var launch = assertInstanceOf(FlowProgram.TaskStart.class,workspace.flow().orElseThrow().steps().getFirst());
        assertTrue(launch.arguments().isEmpty());
        var navigation = new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)navigation::set);
        click(editor,"edit"); var form = navigation.get(); FlowProcedureScreenTest.initializeForm(form,width); bounds(form);
        boxes(form).getLast().setValue("seed"); click(form,"task_handle"); boxes(form).getLast().setValue("child_ticket");
        click(form,"procedure_apply");
        var document = workspace.snapshot(); launch = (FlowProgram.TaskStart)document.flow().orElseThrow().steps().getFirst();
        assertEquals(Map.of("value","seed"),launch.arguments()); assertEquals("child_ticket",launch.handle());
        var encoded = ProgramDocumentCodec.encode(document); assertEquals(new DataValue.IntegerValue(25),encoded.field("version"));
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,SubgraphWorkspaceTest.LIMITS),SubgraphWorkspaceTest.LIMITS);
        assertEquals(document,decoded); var limits = new ComputerHostLimits(new ResourceLimits(1000,100000,1),SubgraphWorkspaceTest.LIMITS, com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        var prepared = PreparedProgram.prepare(decoded,limits); var owner = new ComputerBlockHost(() -> {});
        assertTrue(owner.flowSnapshot().isEmpty()); owner.installProgram(prepared,ComputerRuntime.MemoryPolicy.RESET,limits,true);
        for (int i=0;i<12;i++) owner.step(limits,true);
        var saved = owner.flowSnapshot().orElseThrow(); assertEquals(1,saved.tasks().contexts().size());
        assertEquals(FlowRuntime.Status.COMPLETE,saved.tasks().contexts().getFirst().status());
        assertEquals(new DataValue.IntegerValue(0),saved.tasks().contexts().getFirst().locals().field("result"));
        assertEquals(new DataValue.IntegerValue(999),saved.state().locals().field("value"));
        assertInstanceOf(DataValue.RecordValue.class,saved.state().locals().field("child_ticket"));
    }

    @Test void detachedTaskStartIsCapabilityGatedAndLegacyDocumentsDoNotOfferIt() throws Exception {
        var workspace=new ProgramWorkspace(); var def=FlowProcedureDocumentTest.definition();
        var stacks=List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),List.of(def,new FlowProgram.EndProcedure("end"))),
            new FlowBlockLayout.Stack(new ProgramDocument.Position(280,120),List.of(new FlowProgram.TaskStart("parked",def.key(),Map.of(),"h"))));
        var body=new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),new FlowBlockLayout(new ProgramDocument.Position(16,16),stacks),Map.of(),FlowProgram.Execution.REGISTER_V1);
        workspace.setFlow(Optional.of(body)); var encoded=ProgramDocumentCodec.encode(workspace.snapshot());
        assertEquals(new DataValue.IntegerValue(25),encoded.field("version")); assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(encoded));
        var bad=new TreeMap<>(encoded.fields()); bad.put("version",new DataValue.IntegerValue(24));
        assertThrows(RuntimeException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(bad)));
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),body.initial(),body.canvas(),body.inline(),FlowProgram.Execution.LEGACY_GRAPH)));
        assertThrows(RuntimeException.class,()->ProgramDocumentCodec.encode(workspace.snapshot()));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(stacks.getFirst())))));
        var editor=FlowCallScreenTest.editor(workspace,640); click(editor,"category_custom");
        @SuppressWarnings("unchecked") var entries=(List<FlowBlockCatalog.Entry>)get(editor,"catalogEntries");
        assertTrue(entries.stream().noneMatch(FlowBlockCatalog.Entry::taskStart));
        assertNetworkVersionAtLeast(90);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "zh_cn"})
    void assemblyInstallTooltipDistinguishesSupportedStartFromUnsupportedTaskOperations(String locale) throws Exception {
        var scope = ComputerUiTranslationsTest.load(locale).get("create_biotech.biocomputing.flow_editor.assembly_scope");
        if (locale.equals("en_us")) {
            assertFalse(scope.contains("in-program task creation/cancellation"));
            assertTrue(scope.contains("explicit Start Task blocks"));
            assertTrue(scope.contains("local handle"));
            assertTrue(scope.contains("in-program task cancellation"));
        } else {
            assertFalse(scope.contains("程序内创建／取消任务"));
            assertTrue(scope.contains("显式“启动任务”积木"));
            assertTrue(scope.contains("本机句柄"));
            assertTrue(scope.contains("程序内取消任务"));
        }
    }
    @Test void taskStartPlayerLanguageLabelsArePresentInBothLocales() throws Exception {
        for(String locale:List.of("en_us","zh_cn")) {
            var values=ComputerUiTranslationsTest.load(locale);
            for(String key:List.of("block_task_start","add_task_start","task_handle","task_start_title","task_start_hint")) {
                assertNotNull(values.get("create_biotech.biocomputing.flow_editor."+key));
                assertFalse(values.get("create_biotech.biocomputing.flow_editor."+key).isBlank());
            }
        }
    }
}
