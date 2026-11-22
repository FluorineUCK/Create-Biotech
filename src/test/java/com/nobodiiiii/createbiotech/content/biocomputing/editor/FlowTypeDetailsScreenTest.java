package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;

class FlowTypeDetailsScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    private net.minecraft.locale.Language previousLanguage;
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); previousLanguage = net.minecraft.locale.Language.getInstance(); net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us"))); }
    @AfterEach void restore() throws Exception { try { net.minecraft.locale.Language.inject(previousLanguage); } finally { environment.restore(); } }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"initials", "add_sleep"})
    void hiddenPaletteCannotOverlayInitialOrParameterForms(String entry) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyClosedAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var prototype = ComputerFlowScreenTest.button(editor, "add_sleep");
        int x = prototype.getX() + 2, y = prototype.getY() + 2;
        var visibleHint = hover(editor, x, y, false);
        assertNotNull(visibleHint);
        var before = workspace.snapshot();

        ComputerFlowScreenTest.click(editor, entry);
        assertFalse(editor.children().contains(prototype), "The old palette has left the rendered form");
        assertNull(hover(editor, x, y, false), "Hidden palette help must not cover form fields");
        assertNull(hover(editor, x, y, true), "Shift must not resurrect hidden palette type hints");
        ComputerFlowScreenTest.click(editor, entry.equals("initials") ? "back" : "cancel");
        assertEquals(visibleHint, hover(editor, x, y, false), "Returning restores the actual visible palette");
        assertEquals(before, workspace.snapshot(), "Opening and cancelling forms never changes source");
    }
    @Test void hoverShiftReleaseChangesOnlySupplementaryPaletteTooltip() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("dynamic", DataType.Scalar.ANY), List.of(),
            new DataValue.RecordValue(Map.of("dynamic", new DataValue.IntegerValue(7))))));
        var editor = FlowCallScreenTest.editor(workspace, 320);
        var search = FlowPaletteScreenTest.search(editor); search.setValue("dynamic"); editor.setFocused(search);
        var widget = editor.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).filter(w -> w.getMessage().getString().contains("dynamic") && w != search).findFirst().orElseThrow();
        var before = workspace.snapshot(); int x=widget.getX()+2,y=widget.getY()+2;
        Component ordinary = hover(editor,x,y,false), shifted = hover(editor,x,y,true), released=hover(editor,x,y,false);
        assertFalse(ordinary.getString().contains("ANY")); assertTrue(shifted.getString().contains("ANY"));
        assertEquals(ordinary,released); assertEquals(before,workspace.snapshot()); assertSame(search,editor.getFocused());
        assertSame(search,FlowPaletteScreenTest.search(editor)); assertFalse((boolean)get(editor,"waiting"));
    }
    @Test void serverCapabilityProjectionRoundTripsUnknownFalseAndTrue() throws Exception {
        var ctor=ProgramEditorResponse.class.getConstructor(int.class,UUID.class,long.class,long.class,String.class,String.class,
            ResourceLimits.class,com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits.class,byte[].class,Boolean.class,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.class);
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        for(int level : new int[]{1,4,5}) {
            var identity = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
                net.minecraft.resources.ResourceLocation.parse("minecraft:villager"), net.minecraft.resources.ResourceLocation.parse("minecraft:mason"), level);
            var hardware = config.select(identity,false);
            if (level == 1) hardware = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection.Status.NO_RESIDENT, Optional.empty(), hardware.profile(), hardware.limits());
            Boolean capability = ProgramEditorResponse.capability(hardware);
            assertEquals(level == 1 ? null : Boolean.valueOf(level == 5), capability);
            var packet=(ProgramEditorResponse)ctor.newInstance(7,UUID.randomUUID(),1L,0L,"ready","",new ResourceLimits(1,1,1),ProgramEditorWireTest.LIMITS,new byte[0],capability,com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT);
            var reissued = new ProgramEditorResponse(7, packet.nonce(), 1L, 0L, "ready", "", packet.hardware(),
                packet.archiveLimits(), new byte[]{11}, !Boolean.TRUE.equals(capability), packet.pins()).reissueSource(2L, hardware);
            assertEquals(capability, reissued.nativeIsa1b());
            assertEquals(2L, reissued.intent());
            assertArrayEquals(new byte[]{11}, reissued.archive());
            var wire=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try { packet.write(wire); var copy=new ProgramEditorResponse(wire);
                assertEquals(capability,ProgramEditorResponse.class.getMethod("nativeIsa1b").invoke(copy));
            } finally {wire.release();}
        }
    }
    @Test void placedNestedExpressionAndConstructorMemberShareTypeDetails() throws Exception {
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,640);
        ComputerFlowScreenTest.click(editor,"category_variables"); ComputerFlowScreenTest.click(editor,"add_assign");
        var slot=FlowInlineScreenTest.slot(editor,"0",0);
        assertTrue(hover(editor,(int)slot.x()+2,(int)slot.y()+2,true).getString().contains(ComputerGraphPorts.typeLabel(DataType.Scalar.INTEGER)));
        assertNull(hover(editor,(int)slot.x()+2,(int)slot.y()+2,false));
        FlowInlineScreenTest.dropReporter(editor,"inline_list_make",slot);
        var nested=FlowInlineScreenTest.slot(editor,"0",0);
        assertTrue(hover(editor,(int)nested.x()+2,(int)nested.y()+2,true).getString().contains(ComputerGraphPorts.typeLabel(DataType.Scalar.INTEGER)));
        FlowInlineScreenTest.openListMembers(editor);
        var member=FlowInlineScreenTest.constructorMember(editor); var before=workspace.snapshot();
        assertTrue(hover(editor,member.getX()+2,member.getY()+2,true).getString().contains(ComputerGraphPorts.typeLabel(DataType.Scalar.INTEGER)));
        assertEquals(before,workspace.snapshot()); assertFalse((boolean)get(editor,"waiting"));
    }
    @Test void callBindingInspectionUsesDeclaredTypeWithoutChangingTheBinding() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,320);
        var definition=new FlowProgram.Procedure("def",new ModuleDefinition.Key("identity",1),Map.of("value",DataType.Scalar.ANY),Map.of("result",DataType.Scalar.ANY),Map.of("result","value"));
        var invocation=new FlowProgram.Invoke("call",definition.key(),new FlowCallBindings(Map.of("value","local"),Map.of("result","output")));
        var form=new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),Component.empty(),"call",definition,invocation,ignored->fail("No apply"),()->{});
        FlowProcedureScreenTest.initializeForm(form,320);
        var binding=ComputerFlowScreenTest.boxes(form).stream().filter(box->box.getValue().equals("local")).findFirst().orElseThrow();
        var method=ComputerFlowProcedureScreen.class.getDeclaredMethod("typeTooltip",int.class,int.class,boolean.class);method.setAccessible(true);
        Component details=(Component)method.invoke(form,binding.getX()+2,binding.getY()+2,true);
        assertTrue(details.getString().contains("ANY"));assertTrue(details.getString().contains("Declared"));assertEquals("local",binding.getValue());
        var ordinary=(Component)method.invoke(form,binding.getX()+2,binding.getY()+2,false);assertFalse(ordinary.getString().contains("ANY"));
        assertEquals("Caller local (blank return discards)", ordinary.getString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"en_us,320","en_us,640","zh_cn,320","zh_cn,640"})
    void authorizedCapabilityRefreshUpdatesAdviceWithoutReplacingDirtyDraft(String locale,int width) throws Exception {
        net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
        var workspace=new ProgramWorkspace();var dynamic=new DataType.ListType(DataType.Scalar.ANY);
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("dynamic",dynamic),List.of(),new DataValue.RecordValue(Map.of("dynamic",new DataValue.ListValue(List.of(new DataValue.IntegerValue(9))))))));
        var editor=FlowCallScreenTest.editor(workspace,width);var menu=(ComputerProgramMenu)get(editor,"menu");
        UUID nonce=UUID.randomUUID();set(menu,"nonce",nonce);set(menu,"latestIntent",1L);
        var search=FlowPaletteScreenTest.search(editor);search.setValue("dynamic");editor.setFocused(search);
        var widget=editor.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance).map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
            .filter(w->w!=search&&w.getMessage().getString().contains("dynamic")).findFirst().orElseThrow();
        var before=workspace.snapshot();int x=widget.getX()+2,y=widget.getY()+2;
        var ready=menu.response().orElseThrow();
        for(Boolean capability:new Boolean[]{null,false,true}) {
            menu.accept(new ProgramEditorResponse(0,nonce,1,7,"ready","",ready.hardware(),ready.archiveLimits(),new byte[0],capability,ready.pins()));
            editor.containerTick();
            var hint=hover(editor,x,y,false).getString();
            assertEquals(Boolean.FALSE.equals(capability),hint.contains("ISA-1b"));
            assertEquals(before,workspace.snapshot());assertSame(search,FlowPaletteScreenTest.search(editor));assertSame(search,editor.getFocused());
            assertFalse((boolean)get(editor,"waiting"));
        }
        menu.accept(new ProgramEditorResponse(0,UUID.randomUUID(),1,7,"ready","",ready.hardware(),ready.archiveLimits(),new byte[0],false,ready.pins()));
        assertEquals(Boolean.TRUE,menu.nativeIsa1b(),"Wrong nonce cannot change advisory authority");
    }
    @Test void fieldProofUnknownAndLongHintsAreBoundedWithoutInventingAny() {
        var record=new DataType.RecordType(Map.of("reading",DataType.Scalar.INTEGER));
        assertEquals(DataType.Scalar.INTEGER,FlowTypeDetails.source(new InlineGraphEdits.Variable("source",List.of("reading"),record)).getFirst().type());
        var graph=new ComputationGraph(Map.of("source",record),Map.of("input",new ComputationGraph.Input("source"),"field",new ComputationGraph.Field("input","reading")),Map.of("value","field"));
        assertEquals(DataType.Scalar.INTEGER,FlowTypeDetails.expression(graph,"field",Map.of("source",record),Map.of("source","source"),Map.of()).getFirst().type());
        assertNull(FlowTypeDetails.expression(graph,"field",Map.of(),Map.of(),Map.of()).getFirst().type());
        var unknown=FlowTypeDetails.tooltip(Component.empty(),FlowTypeDetails.expression(graph,"field",Map.of(),Map.of(),Map.of()),true,false).getString();
        assertFalse(unknown.contains("ANY"));assertTrue(unknown.contains("unknown"));
        DataType shared=DataType.Scalar.INTEGER;for(int i=0;i<24;i++)shared=new DataType.RecordType(Map.of("left",shared,"right",shared));
        assertFalse(FlowTypeDetails.any(shared));
        var detail=FlowTypeDetails.tooltip(Component.literal("x".repeat(10000)),List.of(FlowTypeDetails.declared("long",new DataType.ListType(DataType.Scalar.ANY)),FlowTypeDetails.declared("deep",shared)),true,false).getString();
        assertTrue(detail.length()<=1800);assertTrue(detail.contains("SOFTWARE_V1"));assertFalse(detail.contains("not implemented"));
        assertTrue(detail.indexOf("ISA-1b")<detail.indexOf("xxx"));
    }
    @Test void genericAcceptedAnyIsNotAdvertisedAsAnAuthoredDynamicValue() {
        var generic = new FlowTypeDetails.Hint("type_accepted", "value", DataType.Scalar.ANY);
        var hint = FlowTypeDetails.tooltip(Component.literal("Operator"), List.of(generic), false, false).getString();
        assertTrue(hint.contains("ISA-1b"));assertTrue(hint.contains("SOFTWARE_V1"));assertTrue(hint.contains("boundaries"));assertFalse(hint.contains("not implemented"));
        assertFalse(hint.contains("This source includes dynamic ANY"), hint);
    }
    @Test void longWrappedDetailsFitTheLogicalViewport() throws Exception {

        var font = new ComputerProgramScreenTest.Metrics() {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text, int width) {
                assertEquals(288, width);
                return Collections.nCopies(40, net.minecraft.util.FormattedCharSequence.EMPTY);
            }
        };
        var lines = FlowTypeDetails.visibleLines(font, Component.literal("some detail ".repeat(150)), 320, 180);
        assertTrue(lines.size() > 1);
        assertTrue(lines.size() * 10 + 24 <= 180);

    }
    @Test void graphCompilerProofUsesAuthoredParameterTypesAndRejectsBadBindings() {
        var graph = new ComputationGraph(Map.of("parameter", DataType.Scalar.ANY),
            Map.of("input", new ComputationGraph.Input("parameter")), Map.of("out", "input"));
        var limits = new ResourceLimits(10000, 10000, 10000);
        var program = new ProgramDefinition(graph, Map.of(), List.of(), List.of(),
            Map.of("entry", new ProgramDefinition.Entry(graph, List.of(), List.of())));
        var compiled = ProgramCompiler.compile(program, Map.of(), limits);
        var bindings = Map.of("parameter", "local");
        var locals = Map.<String, DataType>of("local", DataType.Scalar.INTEGER);
        var expression = new FlowInput.Expression("entry", "out", bindings);
        expression.validate(DataType.Scalar.ANY, locals, compiled, "slot");
        var inferred = compiled.select("entry").root().outputType().fields().get("out");
        assertEquals(DataType.Scalar.ANY, inferred);
        assertEquals(inferred, FlowTypeDetails.expression(graph, "input", locals, bindings, Map.of()).getFirst().type());

        var integerGraph = new ComputationGraph(Map.of("parameter", DataType.Scalar.INTEGER), graph.nodes(), graph.outputs());
        var invalid = Map.<String, DataType>of("local", DataType.Scalar.TEXT);
        var badProgram = new ProgramDefinition(integerGraph, Map.of(), List.of(), List.of(),
            Map.of("entry", new ProgramDefinition.Entry(integerGraph, List.of(), List.of())));
        var checked = ProgramCompiler.compile(badProgram, Map.of(), limits);
        assertThrows(ComputationException.class, () -> expression.validate(DataType.Scalar.INTEGER, invalid, checked, "slot"));
        var hints = FlowTypeDetails.expression(integerGraph, "input", invalid, bindings, Map.of());
        assertNull(hints.getFirst().type());
        assertEquals(DataType.Scalar.INTEGER, hints.stream().filter(h -> h.kind().equals("type_declared")).findFirst().orElseThrow().type());
        assertTrue(hints.stream().anyMatch(h -> h.kind().equals("type_binding_invalid")));
    }

    @Test void placedGenericEqualityAndIndexKeepTheirPaletteAdviceForConcreteOperands() {
        var graph = new ComputationGraph(Map.of(), Map.of(
            "number", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
            "numbers", new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(0)))),
            "same", new ComputationGraph.ValueEquals("number", "number"),
            "item", new ComputationGraph.DynamicIndex("numbers", "number")), Map.of("out", "same"));
        for (String node : List.of("same", "item")) {
            var hints = FlowTypeDetails.expression(graph, node, Map.of(), Map.of(), Map.of());
            var ordinary = FlowTypeDetails.tooltip(Component.empty(), hints, false, false).getString();
            assertTrue(ordinary.contains("accepts generic ANY"), ordinary);
            assertFalse(ordinary.contains("This source includes dynamic ANY"), ordinary);
        }
    }
    @Test void placedGenericReportersUseOrdinaryAdviceThroughScratchHitTesting() throws Exception {
        for (String key : List.of("inline_value_equals", "inline_list_get")) {
            var workspace = new ProgramWorkspace();
            var itemType = new DataType.ListType(DataType.Scalar.INTEGER);
            workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("items", itemType), List.of(),
                new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))))),
                FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
            var editor = FlowCallScreenTest.editor(workspace, 640);
            var menu = (ComputerProgramMenu)get(editor, "menu");
            var ready = menu.response().orElseThrow();
            UUID nonce = UUID.randomUUID();
            set(menu, "nonce", nonce); set(menu, "latestIntent", 1L);
            menu.accept(new ProgramEditorResponse(0, nonce, 1L, 0L, "ready", "", ready.hardware(),
                ready.archiveLimits(), new byte[0], false, ready.pins()));
            ComputerFlowScreenTest.click(editor, "category_variables"); ComputerFlowScreenTest.click(editor, "add_assign");
            FlowInlineScreenTest.dropReporter(editor, key, FlowInlineScreenTest.slot(editor, "0", 0));
            if (key.equals("inline_list_get"))
                FlowInlineScreenTest.dropPrivateVariable(editor, "items", FlowInlineScreenTest.slot(editor, "[]", 0));
            var graph = workspace.flow().orElseThrow().inline().values().iterator().next();
            var root = graph.outputs().get("value");
            var canvas = (FlowBlockCanvas)get(editor, "canvas");
            var slot = canvas.inlineSlots().stream().filter(candidate -> !candidate.target() && root.equals(candidate.node())).findFirst().orElseThrow();
            var ordinary = hover(editor, (int)slot.x()+3, (int)slot.y()+3, false);
            assertNotNull(ordinary, key);
            assertTrue(ordinary.getString().contains("accepts generic ANY"), ordinary.getString());
        }
    }

    @Test void directLocalSlotReportsLexicalTypeNotASyntheticGraphParameter() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("count", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Repeat("repeat", new FlowInput.Local("count")), new FlowProgram.EndRepeat("end")),
            new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(2))))));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var slot = FlowInlineScreenTest.slot(editor, "count", 0);
        var tooltip = hover(editor, (int)slot.x()+3, (int)slot.y()+3, true).getString();
        assertTrue(tooltip.contains("Local variable count:"), tooltip);
        assertTrue(tooltip.contains(ComputerGraphPorts.typeLabel(DataType.Scalar.INTEGER)), tooltip);
        assertFalse(tooltip.contains("Compiler expression type"), tooltip);
        assertFalse(tooltip.contains("Declared source"), tooltip);
    }

    static Component hover(ComputerFlowScreen screen,int x,int y,boolean shift) throws Exception {
        var method=ComputerFlowScreen.class.getDeclaredMethod("typeTooltip",int.class,int.class,boolean.class);
        method.setAccessible(true);return (Component)method.invoke(screen,x,y,shift);
    }
}

