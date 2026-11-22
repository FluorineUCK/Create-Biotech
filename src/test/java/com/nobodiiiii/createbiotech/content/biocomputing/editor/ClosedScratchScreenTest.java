package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;

class ClosedScratchScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void emptyAuthorizedScratchLoadUsesTheNewPolicyOnlyForANewDraft() throws Exception {
        var parent=screen(); var menu=readyMenu(parent);
        var hub=new ComputerScratchScreen(menu,(net.minecraft.world.entity.player.Inventory)get(parent,"editorInventory"),
            net.minecraft.network.chat.Component.empty());
        set(hub,"navigator",(Consumer<Screen>)ignored->{});
        ComputerScratchNavigationTest.initialize(hub); set(hub,"requested",true); set(menu,"responseVersion",1L);
        hub.containerTick();
        var fresh=(ProgramWorkspace)get(hub,"workspace");
        assertEquals(FlowProgram.Compilation.SOFTWARE_V1,fresh.flow().orElseThrow().compilation());
        assertEquals(FlowProgram.Compilation.ORIGINAL,FlowDocumentBody.emptyAssembly().compilation());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=FlowProgram.Compilation.class,names={"CLOSED_V1","SOFTWARE_V1"})
    void actualScratchControlsCreateAnySignatureAndBindTwoConcreteCalls(FlowProgram.Compilation policy) throws Exception {
        var workspace=new ProgramWorkspace();
        var types=Map.<String,DataType>of("number",DataType.Scalar.INTEGER,"flag",DataType.Scalar.BOOLEAN,"ticks",DataType.Scalar.INTEGER);
        var values=new DataValue.RecordValue(Map.of("number",new DataValue.IntegerValue(9),"flag",new DataValue.BooleanValue(false),"ticks",new DataValue.IntegerValue(2)));
        workspace.setFlow(Optional.of(new FlowDocumentBody(types,List.of(),values,FlowBlockLayout.DEFAULT,Map.of(),
            FlowProgram.Execution.REGISTER_V1,policy)));
        var editor=FlowCallScreenTest.editor(workspace,640);
        var nav=new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)nav::set);
        click(editor,"category_custom"); click(editor,"define_procedure");
        var form=nav.get(); FlowProcedureScreenTest.initializeForm(form,640);
        boxes(form).getFirst().setValue("closed");
        selectAny(form);
        click(form,"procedure_add_field");
        field(form,"procedure_field_name").setValue("ticks");
        click(form,"procedure_outputs"); selectAny(form);
        click(form,"procedure_apply"); initialize(editor,640);
        var definition=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();
        assertEquals(Map.of("value",DataType.Scalar.ANY,"ticks",DataType.Scalar.INTEGER),definition.inputs());
        assertEquals(Map.of("result",DataType.Scalar.ANY),definition.outputs());
        click(editor,"category_variables");
        click(editor,"add_sleep"); boxes(editor).getFirst().setValue("ticks"); click(editor,"apply");
        var bodyCanvas=(FlowBlockCanvas)get(editor,"canvas");
        bodyCanvas.focus(definition.sourceId());
        var bodyJoint=bodyCanvas.joints().stream().filter(j->definition.sourceId().equals(j.socket().stack())&&j.socket().index()==2).findFirst().orElseThrow();
        var viewport=(com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(bodyCanvas,"viewport");
        double bodyX=(int)get(bodyCanvas,"x")+viewport.screenX(bodyJoint.x())+4;
        double bodyY=(int)get(bodyCanvas,"y")+viewport.screenY(bodyJoint.y())+4;
        var assignment=button(editor,"add_assign");
        editor.mouseClicked(assignment.getX()+8,assignment.getY()+8,0);
        editor.mouseDragged(bodyX,bodyY,0,60,20); editor.mouseReleased(bodyX,bodyY,0);
        var nested=assertInstanceOf(FlowProgram.Evaluate.class,workspace.flow().orElseThrow().canvas().detached().getFirst().steps().get(2));
        bodyCanvas.focus(nested.sourceId());
        editor.mouseClicked((int)get(bodyCanvas,"x")+20,(int)get(bodyCanvas,"y")+28,0);
        editor.mouseReleased((int)get(bodyCanvas,"x")+20,(int)get(bodyCanvas,"y")+28,0);
        FlowInlineScreenTest.dropReporter(editor,"inline_value_equals",FlowInlineScreenTest.slot(editor,"0",0));
        FlowInlineScreenTest.dropPrivateVariable(editor,"value",FlowInlineScreenTest.slot(editor,"0",0));
        FlowInlineScreenTest.dropPrivateVariable(editor,"value",FlowInlineScreenTest.slot(editor,"null",0));
        for (String value:List.of("number","flag")) {
            click(editor,"reset_view"); click(editor,"category_custom");
            var prototype=button(editor,"block_invoke"); var canvas=(FlowBlockCanvas)get(editor,"canvas");
            var joint=canvas.joints().stream().filter(j->j.socket().stack()==null).reduce((a,b)->b).orElseThrow();
            double x=(int)get(canvas,"x")+joint.x()+4,y=(int)get(canvas,"y")+joint.y()+4;
            editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);
            editor.mouseDragged(x,y,0,10,10); editor.mouseReleased(x,y,0);
            var call=assertInstanceOf(FlowProgram.Invoke.class,workspace.flow().orElseThrow().steps().getLast());
            click(editor,"edit"); form=nav.get(); FlowProcedureScreenTest.initializeForm(form,640);
            do {
                var port=field(form,"procedure_field_name").getValue();
                field(form,"procedure_binding").setValue(port.equals("ticks")?"ticks":value);
                if(!button(form,"next").active)break; click(form,"next");
            } while(true);
            click(form,"procedure_outputs"); field(form,"procedure_binding").setValue(value+"_returned");
            click(form,"procedure_apply"); initialize(editor,640);
            var bound=(FlowProgram.Invoke)workspace.flow().orElseThrow().steps().getLast();
            assertEquals(call.sourceId(),bound.sourceId());
            assertEquals(value,bound.bindings().arguments().get("value"));
        }
        var previousLanguage=net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
            set((ComputerProgramMenu)get(editor,"menu"),"nativeIsa1b",false);
            var call=workspace.flow().orElseThrow().steps().getLast();
            var canvas=(FlowBlockCanvas)get(editor,"canvas"); canvas.focus(call.sourceId());
            int mx=(int)get(canvas,"x")+20, my=(int)get(canvas,"y")+28;
            var ordinary=editor.typeTooltip(mx,my,false);
            assertNotNull(ordinary);
            assertFalse(ordinary.getString().contains("ISA-1b"));
            var details=editor.typeTooltip(mx,my,true).getString();
            assertTrue(details.contains("proven concrete signature instances"),details);
            assertTrue(details.contains("Declared")&&details.contains("ANY"),details);
        } finally { net.minecraft.locale.Language.inject(previousLanguage); }
        var document=workspace.snapshot();
        assertEquals(policy,document.flow().orElseThrow().compilation());
        var archived=ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,SubgraphWorkspaceTest.LIMITS),SubgraphWorkspaceTest.LIMITS);
        assertEquals(document,archived);
        var image=FlowXBus.image(archived.flow().orElseThrow().program(archived.archive()));
        assertEquals(2,image.procedures().size());
        assertTrue(image.instructionFamilies().contains(InstructionFamily.ISA_1A));
        assertFalse(image.instructionFamilies().contains(InstructionFamily.ISA_1B));
        assertFalse(image.registerTypes().contains(DataType.Scalar.ANY));
        var base=new ComputerHostLimits(new ResourceLimits(4096,100000,1),SubgraphWorkspaceTest.LIMITS);
        var limits=new ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),base.taskSlots(),true,false);
        var host=new ComputerBlockHost(()->{});
        host.installProgram(PreparedProgram.prepare(archived,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        host.setPaused(false,limits,true);
        for(int tick=0;tick<150 && host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;tick++) host.tick(limits,true);
        var done=host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,done.status());
        assertEquals(values.field("number"),done.locals().field("number_returned"));
        assertEquals(values.field("flag"),done.locals().field("flag_returned"));
    }

    private static void selectAny(Screen form) {
        for(var choice=ProcedureSignatureTypes.Choice.INTEGER;choice!=ProcedureSignatureTypes.Choice.ANY;choice=choice.next())
            click(form,"procedure_type_"+choice.name().toLowerCase(Locale.ROOT));
    }
    private static net.minecraft.client.gui.components.EditBox field(Screen form,String key) {
        return boxes(form).stream().filter(box->box.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents label
            && label.getKey().endsWith("."+key)).findFirst().orElseThrow();
    }
}
