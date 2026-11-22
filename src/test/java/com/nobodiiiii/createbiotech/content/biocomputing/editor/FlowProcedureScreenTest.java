package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

class FlowProcedureScreenTest {
    @Test void manualIsReachableOnTheNarrowCanvasAndBesideTheAssemblyEditor() throws Exception {
        set(net.minecraft.client.Minecraft.getInstance(), "font", new Metrics() {
            @Override public net.minecraft.client.StringSplitter getSplitter() {
                return new net.minecraft.client.StringSplitter((codepoint, style) -> 1f);
            }
        });
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 320);
        assertManualButtonIsUncovered(editor);
        var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>) navigation::set);
        click(editor, "category_custom");
        click(editor, "define_procedure");
        var form = assertInstanceOf(ComputerFlowProcedureScreen.class, navigation.get());
        initializeForm(form, 320);
        click(form, "procedure_implementation");
        assertManualButtonIsUncovered(form);
        assertTrue(button(form, "assembly_syntax").active);
    }

    private static void assertManualButtonIsUncovered(Screen screen) {
        var widgets = screen.children().stream()
            .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
            .filter(w -> w.visible).toList();
        var manual = widgets.stream().filter(w -> w.getMessage().getContents() instanceof
            net.minecraft.network.chat.contents.TranslatableContents tr
            && tr.getKey().equals("create_biotech.biocomputing.manual.open")).findFirst().orElseThrow();
        assertTrue(manual.active && manual.getX() >= 0 && manual.getRight() <= screen.width);
        assertTrue(widgets.stream().filter(w -> w != manual).noneMatch(w ->
            w.getX() < manual.getRight() && w.getRight() > manual.getX()
            && w.getY() < manual.getBottom() && w.getBottom() > manual.getY()));
    }

    @Test void aNewArgumentAppendsAfterExistingLiteralTextAndKeepsThatOrderOnReopen() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>)navigation::set);
        click(editor, "category_custom"); click(editor, "define_procedure");
        var form = assertInstanceOf(ComputerFlowProcedureScreen.class, navigation.get());
        initializeForm(form, 640);
        click(form, "procedure_labels"); click(form, "procedure_add_label");
        boxes(form).getLast().setValue("when");
        click(form, "procedure_inputs"); click(form, "procedure_add_boolean");
        click(form, "procedure_labels"); click(form, "procedure_add_label");
        boxes(form).getLast().setValue("then");
        var preview = form.signaturePreview();
        assertEquals(List.of("when", "then"), preview.phrases().stream().map(ScratchSignaturePreview.Phrase::text).toList());
        assertTrue(preview.chips().getFirst().x() < preview.phrases().getFirst().x());
        assertTrue(preview.phrases().getFirst().x() < preview.chips().getLast().x(),
            "The new input appends to the existing sequence; the preceding label must not move");
        assertTrue(preview.chips().getLast().x() < preview.phrases().getLast().x());
        click(form, "procedure_apply");

        var saved = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var body = saved.flow().orElseThrow();
        var declaration = FlowProcedureTools.definitions(body).getFirst();
        assertEquals(List.of(new ScratchProcedureLabel(1, "when"), new ScratchProcedureLabel(2, "then")),
            body.canvas().procedureLabels().get(declaration.sourceId()));
        var opened = openDefinition(new ProgramWorkspace(saved), declaration);
        assertEquals(preview, opened.signaturePreview());
    }

    @Test void removingAnArgumentKeepsTheLiteralTextOnBothSidesInVisualOrder() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        var inputs = new LinkedHashMap<String, DataType>();
        inputs.put("value", DataType.Scalar.INTEGER);
        inputs.put("condition", DataType.Scalar.BOOLEAN);
        var definition = new FlowProgram.Procedure("ordered", new ModuleDefinition.Key("ordered", 1), inputs, Map.of(), Map.of());
        draft.place(List.of(definition, new FlowProgram.EndProcedure("end_ordered")), null, new ProgramDocument.Position(50, 60));
        // The user can move the second label before the first. The stored insertion order is then
        // different from the order rendered in the signature.
        draft.setProcedureLabels("ordered", List.of(new ScratchProcedureLabel(2, "then"), new ScratchProcedureLabel(1, "when")));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var form = openDefinition(workspace, definition);
        var expected = List.of("when", "then");
        assertEquals(expected, form.signaturePreview().phrases().stream().map(ScratchSignaturePreview.Phrase::text).toList());
        click(form, "next");
        assertEquals("condition", boxes(form).get(2).getValue());
        click(form, "procedure_remove_field");
        assertEquals(expected, form.signaturePreview().phrases().stream().map(ScratchSignaturePreview.Phrase::text).toList(),
            "Removing a slot must not swap adjacent fixed phrases");
        click(form, "procedure_apply");

        var restored = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var body = restored.flow().orElseThrow();
        assertEquals(List.of("value"), List.copyOf(FlowProcedureTools.definitions(body).getFirst().inputs().keySet()));
        assertEquals(List.of(new ScratchProcedureLabel(1, "when"), new ScratchProcedureLabel(1, "then")),
            body.canvas().procedureLabels().get(definition.sourceId()));
    }

    private static ComputerFlowProcedureScreen openDefinition(ProgramWorkspace workspace, FlowProgram.Procedure declaration) throws Exception {
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>)navigation::set);
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var definition = canvas.blocks().stream().filter(block -> block.step().equals(declaration)).findFirst().orElseThrow();
        canvas.focus(declaration.sourceId());
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas, "viewport");
        double x = (int)get(canvas, "x") + viewport.screenX(definition.x() + 10);
        double y = (int)get(canvas, "y") + viewport.screenY(definition.y() + 10);
        editor.mouseClicked(x, y, 1);
        click(editor, "procedure_context_edit");
        var opened = assertInstanceOf(ComputerFlowProcedureScreen.class, navigation.get());
        initializeForm(opened, 640);
        return opened;
    }

    @Test void editingLabelsIsASeparateVisualChangeAndUndoRestoresTheWholeDefinition() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640); var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>)navigation::set);
        click(editor, "category_custom"); click(editor, "define_procedure");
        var form = navigation.get(); initializeForm(form, 640);
        click(form, "procedure_add_boolean");
        click(form, "procedure_labels"); click(form, "procedure_add_label");
        boxes(form).getLast().setValue("when ready");
        click(form, "procedure_label_position"); // After all parameters -> before the first parameter.
        click(form, "procedure_apply");
        var labelled = workspace.snapshot();
        var saved = ProgramDocumentCodec.encode(labelled);
        assertEquals(new DataValue.IntegerValue(53), saved.field("version"));
        var oldVersion = new LinkedHashMap<>(saved.fields()); oldVersion.put("version", new DataValue.IntegerValue(52));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(oldVersion)));
        var limits = SubgraphWorkspaceTest.LIMITS;
        assertEquals(labelled, ProgramDocumentWire.decode(ProgramDocumentWire.encode(labelled, limits), limits));
        var before = FlowProcedureTools.definitions(labelled.flow().orElseThrow()).getFirst();
        assertEquals(List.of(new ScratchProcedureLabel(0,"when ready")),
            labelled.flow().orElseThrow().canvas().procedureLabels().get(before.sourceId()));
        initialize(editor, 640); click(editor, "edit");
        form = navigation.get(); initializeForm(form, 640); click(form, "procedure_labels");
        assertEquals("when ready", boxes(form).getLast().getValue());
        click(form, "procedure_remove_label"); click(form, "procedure_apply");
        var unlabelled = workspace.snapshot();
        assertEquals(before,FlowProcedureTools.definitions(unlabelled.flow().orElseThrow()).getFirst());
        assertEquals(labelled.flow().orElseThrow().program(labelled.archive()).steps(),
            unlabelled.flow().orElseThrow().program(unlabelled.archive()).steps());
        assertTrue(((DataValue.IntegerValue)ProgramDocumentCodec.encode(unlabelled).field("version")).value() < 53);
        initialize(editor,640); click(editor, "undo");
        assertEquals(labelled,workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void returnedCustomBlockDropsIntoAValueSocketAndKeepsExplicitArguments(int width) throws Exception {
        var key = new ModuleDefinition.Key("identity", 1);
        var procedure = new FlowProgram.Procedure("definition", key, Map.of("n", DataType.Scalar.INTEGER),
            Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "n"));
        var stack = new FlowBlockLayout.Stack(new ProgramDocument.Position(800, 16),
            List.of(procedure, new FlowProgram.EndProcedure("definition_end")));
        var body = FlowDocumentBody.emptyAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body.edited(body.inputs(), body.steps(), body.initial(),
            body.canvas().arranged(body.canvas().main(), List.of(stack)), body.inline())));
        var editor = FlowCallScreenTest.editor(workspace, width);
        click(editor, "category_variables"); click(editor, "add_assign");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var target = canvas.inlineSlots().stream().filter(FlowBlockCanvas.Slot::target).findFirst().orElseThrow();
        FlowInlineScreenTest.setLiteral(editor, target, "v");
        var before = workspace.snapshot(); var slot = FlowInlineScreenTest.slot(editor, "0", 0);
        click(editor, "category_custom");
        for (int i = 0; i < 10; i++) {
            var visible = editor.children().stream().filter(c -> c instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b
                && b.visible && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().endsWith(".procedure_value")).findFirst();
            if (visible.isEmpty()) { assertTrue(button(editor, "palette_next").active); click(editor, "palette_next"); continue; }
            var reporter = (com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton)visible.orElseThrow();
            assertEquals(before, workspace.snapshot(), "Browsing the reporter catalog does not mutate or install");
            editor.mouseClicked(reporter.getX() + 8, reporter.getY() + 8, 0);
            editor.mouseDragged(slot.x() + 8, slot.y() + slot.height() / 2, 0, 40, 0);
            editor.mouseReleased(slot.x() + 8, slot.y() + slot.height() / 2, 0);
            break;
        }
        var updated = workspace.flow().orElseThrow(); var graph = updated.inline().get(updated.steps().getFirst().sourceId());
        assertNotNull(graph, "Drop must reach the visible slot: " + get(editor, "notice"));
        var call = assertInstanceOf(ComputationGraph.ProcedureValue.class, graph.nodes().get(graph.outputs().get("value")));
        assertEquals(key, call.procedure()); assertEquals(Set.of("n"), call.arguments().keySet());
        FlowInlineScreenTest.setLiteral(editor, FlowInlineScreenTest.slot(editor, "0", 0), "7");
        assertEquals(new DataValue.IntegerValue(7), FlowInlineScreenTest.run(workspace.snapshot()).field("v"));
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        var references = ScratchProcedureReferences.find(new FlowDraft(workspace.flow().orElseThrow()), key);
        assertEquals(1, references.size()); assertEquals(ScratchProcedureReferences.Kind.VALUE, references.getFirst().kind());
        click(editor, "undo"); click(editor, "undo"); assertEquals(before, workspace.snapshot());
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void definitionRulesArePlayerEditablePortableAndPreservedWhenReopened(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width); var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>)navigation::set);
        click(editor, "category_custom"); click(editor, "define_procedure");
        var form = navigation.get(); initializeForm(form, width); click(form, "procedure_rules"); bounds(form);
        click(form, "rule_allowed"); // The operation-family list starts with device waits.
        click(form, "rules_inside"); click(form, "rule_allowed"); // Context families start with loops.
        click(form, "procedure_apply");
        var declaration = FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();
        assertEquals(new FlowBlockRules(Set.of("device_wait"), Set.of("loop")), declaration.rules());
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        initialize(editor, width); click(editor, "edit"); form = navigation.get(); initializeForm(form, width);
        click(form, "procedure_rules"); assertNotNull(button(form, "rule_forbidden")); click(form, "procedure_apply");
        assertEquals(declaration, FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst());
    }
    @Test void definitionDialogCreatesASeparateHatWithoutAttachingOrInstallingIt() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var before = workspace.snapshot(); var editor = FlowCallScreenTest.editor(workspace, 426);
        click(editor,"category_custom"); var define = button(editor,"define_procedure");
        var navigation = new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)navigation::set);
        define.onClick(0,0); var form = navigation.get(); assertNotNull(form); initializeForm(form,426); bounds(form);
        boxes(form).getFirst().setValue("wait_copy");
        assertEquals(before,workspace.snapshot()); click(form,"procedure_apply");
        assertSame(editor,navigation.get()); initialize(editor,426);
        var body = workspace.flow().orElseThrow(); assertTrue(body.steps().isEmpty());
        var stack = body.canvas().detached().getFirst(); var definition = assertInstanceOf(FlowProgram.Procedure.class,stack.steps().getFirst());
        assertEquals("wait_copy",definition.key().id()); assertEquals(Map.of("value",DataType.Scalar.INTEGER),definition.inputs());
        assertEquals(Map.of("result","value"),definition.returns()); assertInstanceOf(FlowProgram.EndProcedure.class,stack.steps().getLast());
        assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        assertFalse((boolean)get(editor,"waiting")); click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Execution.class)
    void aProcedureCallIsDraggedThenExplicitlyBoundRatherThanCapturingSameNames(FlowProgram.Execution execution) throws Exception {
        var initial=new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41),"value",new DataValue.IntegerValue(999)));
        var definition=FlowProcedureDocumentTest.definition();
        var layout = new FlowBlockLayout(new ProgramDocument.Position(16,16), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(280,16), List.of(definition,new FlowProgram.Sleep("wait","value"),new FlowProgram.EndProcedure("end")))));
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            ((DataType.RecordType)DataType.typeOf(initial)).fields(),List.of(),initial,layout,Map.of(),execution)));
        var editor=FlowCallScreenTest.editor(workspace,640); click(editor,"category_custom");
        if (execution == FlowProgram.Execution.REGISTER_V1) FlowPaletteScreenTest.search(editor).setValue("bind:");
        var prototype=button(editor, execution == FlowProgram.Execution.REGISTER_V1 ? "block_bind_invoke" : "block_invoke"); var canvas=get(editor,"canvas");
        double fx=prototype.getX()+10,fy=prototype.getY()+10,x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        var before=workspace.snapshot(); editor.mouseClicked(fx,fy,0); editor.mouseDragged(x,y,0,x-fx,y-fy);
        assertEquals(before,workspace.snapshot()); editor.mouseReleased(x,y,0);
        var call=assertInstanceOf(FlowProgram.Invoke.class,workspace.flow().orElseThrow().steps().getFirst());
        assertTrue(call.bindings().arguments().isEmpty(),"A dropped call is visibly unbound, never a same-name capture");
        var navigation=new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)navigation::set);
        click(editor,"edit"); var form=navigation.get(); initializeForm(form,640); bounds(form);
        boxes(form).getLast().setValue("seed"); click(form,"procedure_outputs"); boxes(form).getLast().setValue("answer");
        click(form,"procedure_apply"); initialize(editor,640);
        var document=workspace.snapshot(); var bound=assertInstanceOf(FlowProgram.Invoke.class,document.flow().orElseThrow().steps().getFirst());
        assertEquals(new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer")),bound.bindings());
        var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),document.flow().orElseThrow().program(document.archive()),initial,new ResourceLimits(200,100000,1));
        for(int i=0;runtime.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++) {assertTrue(i<120);runtime.elapseTick();runtime.advance(1);}
        assertEquals(new DataValue.IntegerValue(41),runtime.checkpoint().locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(999),runtime.checkpoint().locals().field("value"));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertFalse((boolean)get(editor,"waiting"));
    }
    static void initializeForm(Screen form,int width) throws Exception {
        set(form,"width",width); set(form,"height",260); set(form,"minecraft",net.minecraft.client.Minecraft.getInstance());
        set(form,"font",net.minecraft.client.Minecraft.getInstance().font);
        var init=form.getClass().getDeclaredMethod("init"); init.setAccessible(true); init.invoke(form);
    }
    @Test void aNewDefinitionCanReceiveAWaitBlockAndShowsOnlyItsOwnParameters() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("seed",DataType.Scalar.INTEGER),
            List.of(),new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(3))))));
        var editor = FlowCallScreenTest.editor(workspace,640); var navigation = new AtomicReference<Screen>();
        set(editor,"navigator",(Consumer<Screen>)navigation::set);
        click(editor,"category_custom"); click(editor,"define_procedure"); var form = navigation.get(); initializeForm(form,640);
        click(form,"procedure_apply"); initialize(editor,640);
        var valueLabel = FlowBlockCatalog.label(new InlineGraphEdits.Variable("value", List.of(), DataType.Scalar.INTEGER));
        var seedLabel = FlowBlockCatalog.label(new InlineGraphEdits.Variable("seed", List.of(), DataType.Scalar.INTEGER));
        click(editor,"category_variables"); assertTrue(visibleLabels(editor).contains(valueLabel)); assertFalse(visibleLabels(editor).contains(seedLabel));
        click(editor,"add_sleep"); boxes(editor).getFirst().setValue("value"); click(editor,"apply");
        var body = workspace.flow().orElseThrow(); assertTrue(body.steps().isEmpty());
        assertEquals(3,body.canvas().detached().getFirst().steps().size());
        assertEquals("value",assertInstanceOf(FlowProgram.Sleep.class,body.canvas().detached().getFirst().steps().get(1)).ticksLocal());
        assertEquals(Map.of("value",DataType.Scalar.INTEGER),FlowProcedureTools.locals(body,body.canvas().detached().getFirst().steps().get(1).sourceId(),
            workspace.snapshot().archive(),new ResourceLimits(200,100000,1)));
        click(editor,"reset_view"); var canvas = get(editor,"canvas");
        double x = (int)get(canvas,"x")+24, y = (int)get(canvas,"y")+24;
        editor.mouseClicked(x,y,0); editor.mouseReleased(x,y,0); click(editor,"category_variables");
        assertTrue(visibleLabels(editor).contains(seedLabel)); assertFalse(visibleLabels(editor).contains(valueLabel));
        assertFalse((boolean)get(editor,"waiting"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320,426,640})
    void allTypedPortsRemainReachableAndCancelDoesNotPublish(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace,width); var menu = (ComputerProgramMenu)get(editor,"menu");
        var ports = new LinkedHashMap<String,DataType>(); var sources = new LinkedHashMap<String,String>();
        for (int i=0;i<9;i++) { ports.put("field_"+i,new DataType.ListType(DataType.Scalar.INTEGER)); sources.put("field_"+i,"field_"+i); }
        var definition = new FlowProgram.Procedure("definition",FlowProcedureDocumentTest.KEY,ports,ports,sources);
        var applied = new AtomicReference<FlowProgram.Step>(); var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var form = new ComputerFlowProcedureScreen(menu,(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),
            net.minecraft.network.chat.Component.empty(),"definition",definition,null,applied::set,()->closed.set(true));
        initializeForm(form,width);
        for (int tab=0;tab<2;tab++) {
            if(tab==1)click(form,"procedure_outputs"); var seen = new HashSet<String>();
            do {
                bounds(form);
                for (var box:boxes(form)) if (box.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key
                        && key.getKey().endsWith("procedure_field_name")) assertTrue(seen.add(box.getValue()));
                if(!button(form,"next").active)break; click(form,"next");
            }while(true);
            assertEquals(ports.keySet(),seen);
        }
        click(form,"procedure_apply"); assertEquals(definition,applied.get(),"Untouched complex types must not become the displayed sample's type");
        assertTrue(closed.get()); applied.set(null); closed.set(false);
        initializeForm(form,width); boxes(form).getFirst().setValue("unsaved_rename"); click(form,"cancel");
        assertNull(applied.get()); assertTrue(closed.get());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320,426,640})
    void importedInlineCallCanEditReturnsWithoutErasingItsArguments(int width) throws Exception {
        var document = ScratchLegacyDocumentCompatibilityTest.read("scratch-v35-inline-call",35);
        var body = document.flow().orElseThrow(); var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body));
        var editor = FlowCallScreenTest.editor(workspace,width); var menu = (ComputerProgramMenu)get(editor,"menu");
        var call = (FlowProgram.Invoke)body.steps().getFirst();
        var definition = (FlowProgram.Procedure)body.canvas().detached().getFirst().steps().getFirst();
        var applied = new AtomicReference<FlowProgram.Step>();
        var form = new ComputerFlowProcedureScreen(menu,(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),
            net.minecraft.network.chat.Component.empty(),call.sourceId(),definition,call,applied::set,()->{});
        initializeForm(form,width); bounds(form); click(form,"procedure_outputs"); bounds(form);
        for(var box:boxes(form)) if(box.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key
            && key.getKey().endsWith("procedure_binding")) box.setValue("new_answer");
        click(form,"procedure_apply");
        var result = assertInstanceOf(FlowProgram.Invoke.class,applied.get());
        assertEquals(call.operands(),result.operands());
        assertTrue(result.bindings().arguments().isEmpty());
        assertEquals(Map.of("result","new_answer"),result.bindings().returns());
        assertEquals(body,workspace.flow().orElseThrow(),"The detached form does not edit the workspace before the caller applies it");
    }
    @Test void cancelledCallDragAndIncompleteBindingsLeaveTheDraftUnchanged() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),
            new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),
                List.of(FlowProcedureDocumentTest.definition(),new FlowProgram.EndProcedure("end"))))))));
        var editor = FlowCallScreenTest.editor(workspace,640); click(editor,"category_custom"); var before=workspace.snapshot();
        var prototype = button(editor,"block_invoke"); var canvas=get(editor,"canvas");
        double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        for(int mode=0;mode<3;mode++) {
            editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0); editor.mouseDragged(x,y,0,10,10);
            if(mode==0)editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
            else if(mode==1)click(editor,"category_variables"); else editor.onClose();
            editor.mouseReleased(x,y,0); assertEquals(before,workspace.snapshot());
            click(editor,"category_custom"); prototype=button(editor,"block_invoke");
        }
        editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0); editor.mouseReleased(x,y,0);
        var unbound=workspace.snapshot(); var navigation=new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)navigation::set);
        click(editor,"edit"); var form=navigation.get(); initializeForm(form,640); click(form,"procedure_apply");
        assertSame(form,navigation.get()); assertEquals(unbound,workspace.snapshot());
        assertThrows(ComputationException.class,()->workspace.snapshot().validate(new ResourceLimits(200,100000,1)));
        click(form,"cancel"); assertSame(editor,navigation.get()); assertEquals(unbound,workspace.snapshot());
        assertFalse((boolean)get(editor,"waiting"));
    }
    private static Set<net.minecraft.network.chat.Component> visibleLabels(Screen screen) {
        var labels = new HashSet<net.minecraft.network.chat.Component>();
        screen.children().forEach(child->{if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)labels.add(widget.getMessage());});
        return labels;
    }
    @Test void movingADefinitionNearTheMainStackKeepsItSeparateAndUndoable() throws Exception {
        var workspace = new ProgramWorkspace();
        var definition = FlowProcedureDocumentTest.definition();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),
            new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(16,86),
                List.of(definition,new FlowProgram.Sleep("sleep","value"),new FlowProgram.EndProcedure("end"))))))));
        var before = workspace.snapshot(); var editor = FlowCallScreenTest.editor(workspace,640);
        var canvas = get(editor,"canvas"); double x = (int)get(canvas,"x")+24, y = (int)get(canvas,"y");
        editor.mouseClicked(x,y+96,0); editor.mouseDragged(x,y+50,0,0,-46);
        assertEquals(before,workspace.snapshot()); editor.mouseReleased(x,y+50,0);
        var body = workspace.flow().orElseThrow(); assertTrue(body.steps().isEmpty());
        assertEquals(new ProgramDocument.Position(16,40),body.canvas().detached().getFirst().position());
        assertEquals(before.flow().orElseThrow().canvas().detached().getFirst().steps(),body.canvas().detached().getFirst().steps());
        click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }
    @Test void resourcePreviewCountsDefinitionCodeAndLiteralInputsOnceButNotParkedDrafts() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            new FlowBlockLayout(new ProgramDocument.Position(16,16), List.of(
                new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16), List.of(FlowProcedureDocumentTest.definition(),
                    new FlowProgram.Repeat("repeat",new FlowInput.Literal(new DataValue.IntegerValue(2))),
                    new FlowProgram.EndRepeat("repeat_end"),new FlowProgram.EndProcedure("end"))),
                new FlowBlockLayout.Stack(new ProgramDocument.Position(16,180), List.of(new FlowProgram.Sleep("parked","missing"))))))));
        var document = workspace.snapshot(); var program = document.flow().orElseThrow().program(document.archive());
        var parent = screen(); var menu = readyMenu(parent); parent.importDocument(document);
        var usage = program.expressions().compile(menu.response().orElseThrow().hardware()).usage();
        var status = (net.minecraft.network.chat.contents.TranslatableContents)parent.resourceStatus().getContents();
        assertEquals(usage.programUnits() + program.steps().size(), status.getArgs()[0]);
        assertEquals(usage.constantUnits() + usage.initialMemoryUnits() + FlowProgram.inputConstantUnits(program.steps()), status.getArgs()[2]);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh_cn", "en_us"})
    void procedureControlsAndCatalogHavePackagedTranslations(String locale) throws Exception {
        var values = ComputerUiTranslationsTest.load(locale);
        for (String key : List.of("define_procedure", "procedure_name", "procedure_revision", "procedure_inputs", "procedure_inputs_selected",
                "procedure_outputs", "procedure_outputs_selected", "procedure_field_name", "procedure_binding", "procedure_return_local",
                "procedure_type_sample", "procedure_type_sample_hint", "procedure_remove_field", "procedure_add_field", "procedure_apply",
                "procedure_definition_title", "procedure_call_title", "procedure_binding_heading", "procedure_return_heading", "procedure_input_heading",
                "procedure_signature_hint", "procedure_call_hint", "procedure_drag_hint", "procedure_missing", "block_procedure", "block_invoke",
                "block_end_procedure", "procedure_definition_hint", "procedure_type_integer", "procedure_type_decimal", "procedure_type_text",
                "procedure_type_boolean", "procedure_type_json", "procedure_type_nbt")) {
            String value = values.get("create_biotech.biocomputing.flow_editor." + key);
            assertNotNull(value, locale + ": " + key); assertFalse(value.isBlank());
            int arguments = Set.of("block_procedure", "block_invoke", "procedure_missing").contains(key) ? 2 : 0;
            assertEquals(arguments, java.util.regex.Pattern.compile("%s").matcher(value).results().count(), key);
        }
        var old = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            var definition = FlowProcedureDocumentTest.definition();
            var entry = FlowBlockCatalog.entries(Map.of(), List.of(definition)).stream().filter(e -> e.procedure() != null).findFirst().orElseThrow();
            assertTrue(entry.label().getString().contains(definition.key().id()));
            assertFalse(entry.help().getString().contains("create_biotech."));
            assertTrue(FlowBlockCatalog.search(Map.of(), List.of(definition), FlowBlockPalette.Category.EVENTS, "delay_increment").contains(entry));
        } finally { net.minecraft.locale.Language.inject(old); }
    }
}
