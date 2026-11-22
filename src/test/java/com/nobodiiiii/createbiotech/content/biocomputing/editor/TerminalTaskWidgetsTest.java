package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;

class TerminalTaskWidgetsTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    static ProgramDocument document() {
        var types = Map.<String,DataType>of("value", DataType.Scalar.INTEGER);
        var program = new FlowProgram(types, List.of(new FlowProgram.Sleep("main", "value"),
            new FlowProgram.Procedure("definition", new ModuleDefinition.Key("wait_copy",1),types,Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value")),
            new FlowProgram.Sleep("child", "value"), new FlowProgram.EndProcedure("return")));
        return new ProgramDocument(program.expressions(),Map.of(),Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(Map.of("value",new DataValue.IntegerValue(100))))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320,426})
    void flowTerminalSelectsPinnedDefinitionWithoutRenamingOrInstallingAndPreservesExplicitInputs(int width) throws Exception {
        var parent = screen(); var workspace = new ProgramWorkspace(document()); var opened = new Screen[1];
        var editor = new ComputerTerminalEditorScreen((ComputerProgramMenu)get(parent,"menu"),(Inventory)get(parent,"editorInventory"),
            Component.empty(),parent,workspace,()->{},()->{});
        set(editor,"navigator",(Consumer<Screen>)(next->opened[0]=next)); init(editor,width); click(editor,"add.button");
        ((EditBox)get(editor,"literal")).setValue("Start task"); click(editor,"configure");
        click(editor,"button_slot"); click(editor,"button_slot");
        ((EditBox)get(editor,"literal")).setValue("{\"value\":1}"); click(editor,"configure");
        click(editor,"select_task"); var picker=opened[0]; assertNotNull(picker); init(picker,width); bounds(picker);
        picker.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.getMessage().getString().contains("wait_copy@1")).findFirst().orElseThrow().onClick(0,0);
        assertSame(editor,opened[0]); bounds(editor);
        var draft=(TerminalDraft)get(editor,"draft");
        var button=(TerminalDefinition.Button)draft.snapshot().nodes().get(get(editor,"selected"));
        assertEquals(new TerminalTarget.Task(new ModuleDefinition.Key("wait_copy",1)),button.target());
        assertEquals(new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("value",new DataValue.IntegerValue(1)))),button.inputs());
        click(editor,"configure"); init(editor,width==320?426:320); bounds(editor); click(editor,"apply");
        assertDoesNotThrow(()->workspace.snapshot().validateTerminalTargets());
        assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        // Opening an existing task target must not attempt to reinterpret it as a legacy event name.
        var reopened=new ComputerTerminalEditorScreen((ComputerProgramMenu)get(parent,"menu"),(Inventory)get(parent,"editorInventory"),Component.empty(),parent,workspace,()->{},()->{});
        init(reopened,width); bounds(reopened);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320,426})
    void frozenTaskViewerInspectsResultsAndClaimsOnlyAnExplicitFinishedRow(int width) throws Exception {
        var parent=screen(); var menu=(ComputerProgramMenu)get(parent,"menu"); var opened=new Screen[1];
        var viewer=new ComputerTerminalScreen(menu,(Inventory)get(parent,"editorInventory"),Component.empty(),parent);
        var claims=new ArrayList<TerminalTaskClaim>();
        set(viewer,"navigator",(Consumer<Screen>)(next->opened[0]=next));
        set(viewer,"claimRequest",(Consumer<TerminalTaskClaim>)claims::add);
        set(viewer,"publishedView",7L);
        var result=new DataValue.RecordValue(Map.of("result",new DataValue.IntegerValue(42)));
        var rows=new ArrayList<DataValue>();
        for(int i=0;i<20;i++) rows.add(new DataValue.RecordValue(Map.of("handle",new DataValue.TextValue(UUID.randomUUID().toString()),
            "procedure",new DataValue.TextValue("wait_copy"),"revision",new DataValue.IntegerValue(1),
            "phase",new DataValue.TextValue(i==0?"SLEEPING":"COMPLETE"),"result",i==0?DataValue.NullValue.INSTANCE:result,"failure",DataValue.NullValue.INSTANCE)));
        set(viewer,"snapshot",new TerminalStatusView(Optional.of(TerminalDefinition.empty()),new DataValue.RecordValue(Map.of("memory",new DataValue.RecordValue(Map.of()),"output",DataValue.NullValue.INSTANCE,"tasks",new DataValue.ListValue(rows))),"SLEEPING",false));
        init(viewer,width); bounds(viewer); click(viewer,"tasks"); var tasks=opened[0]; init(tasks,width); bounds(tasks);
        set(tasks,"navigator",(Consumer<Screen>)(next->opened[0]=next));
        assertFalse(button(tasks,"claim_task").active); // first row still running
        tasks.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.getMessage().getString().contains("2 · wait_copy@1")).findFirst().orElseThrow().onClick(0,0);
        click(tasks,"inspect_task"); var inspector=assertInstanceOf(ComputerValueTreeScreen.class,opened[0]); init(inspector,width);
        assertEquals(result,((ValueTree)get(inspector,"tree")).inputs().field("result")); assertTrue(claims.isEmpty());
        inspector.onClose(); assertSame(tasks,opened[0]); click(tasks,"next"); bounds(tasks); click(tasks,"previous");
        click(tasks,"claim_task"); assertSame(viewer,opened[0]); assertEquals(List.of(new TerminalTaskClaim(7,1)),claims);
        assertTrue((boolean)get(viewer,"clickLocked")); assertTrue((boolean)get(viewer,"requestPending"));
        assertFalse(button(viewer,"tasks").active,"Refresh publishes a new task list before the next mutation");
    }
}
