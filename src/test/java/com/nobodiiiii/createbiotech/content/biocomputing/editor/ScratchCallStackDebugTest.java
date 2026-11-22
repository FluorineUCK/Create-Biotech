package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchCallStackDebugTest {
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }

    @Test void frozenChildUsesItsOwnNestedCallSitesAndFixedRevisionsAfterRestore() {
        var worker = new ModuleDefinition.Key("worker", 1);
        var intermediate = new ModuleDefinition.Key("transform", 2);
        var leaf = new ModuleDefinition.Key("transform", 3);
        var types = Map.<String, DataType>of("ticks", DataType.Scalar.INTEGER);
        var program = new FlowProgram(types, List.of(
            new FlowProgram.Sleep("main_sleep", "ticks"),
            new FlowProgram.Procedure("worker_definition", worker, types, Map.of(), Map.of()),
            call("call_intermediate", intermediate),
            new FlowProgram.EndProcedure("worker_end"),
            new FlowProgram.Procedure("intermediate_definition", intermediate, types, Map.of(), Map.of()),
            call("call_leaf", leaf),
            new FlowProgram.EndProcedure("intermediate_end"),
            new FlowProgram.Procedure("leaf_definition", leaf, types, Map.of(), Map.of()),
            new FlowProgram.Sleep("child_sleep", "ticks"),
            new FlowProgram.EndProcedure("leaf_end"))).withAssembly();
        var limit = StockApplicationDriverDocuments.hardware();
        var input = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(120)));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, input, limit, true);
        var child = host.startFlowTask(worker, input, limit, true);
        for (int i = 0; i < 32 && host.flowTask(child).orElseThrow().status() != FlowRuntime.Status.SLEEPING; i++)
            host.step(limit, true);
        assertEquals(FlowRuntime.Status.SLEEPING, host.flowTask(child).orElseThrow().status());
        var disk = host.write();
        var restored = new ComputerBlockHost(() -> {});
        restored.read(disk);
        restored.tick(limit, true);
        var saved = restored.flowSnapshot().orElseThrow();
        var task = assertInstanceOf(DataValue.RecordValue.class,
            TaskDebugView.task(saved, child).orElseThrow().field("debug_task"));
        assertEquals(new DataValue.TextValue("child_sleep"), task.field("source"));
        var frames = assertInstanceOf(DataValue.ListValue.class, task.field("call_stack"));
        assertEquals(List.of(frame("call_intermediate", intermediate), frame("call_leaf", leaf)), frames.values());
        assertEquals(disk, restored.write(), "Observation of the restored paused call does not change its checkpoint");
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void taskLabelsUseOnlyTheObservedInstallationAndKeepRawFrames(String locale) throws Exception {
        var oldLanguage = Language.getInstance();
        try {
            Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
            var workspace = new ProgramWorkspace();
            var input = new DataValue.RecordValue(Map.of("actual_delay", new DataValue.IntegerValue(120)));
            var delay = new FlowProgram.Sleep("installed_sleep", "actual_delay");
            workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("actual_delay", DataType.Scalar.INTEGER), List.of(delay),
                input, FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
            var limit = StockApplicationDriverDocuments.hardware();
            var nonce = UUID.randomUUID();
            // The unit harness has no live NeoForge menu registry; supply a previously accepted source reply.
            var menu = clientMenu();
            var installed = ComputerProgramMenu.class.getDeclaredField("installedSource");
            installed.setAccessible(true);
            installed.set(menu, new ProgramEditorResponse(1, nonce, 1, 7, "ready", "", limit.hardware(), limit.archive(),
                ProgramDocumentWire.encode(workspace.snapshot(), limit.archive())));
            var source = new DataValue.TextValue("installed_sleep");
            var stack = new DataValue.ListValue(List.of(frame(source.value(), new ModuleDefinition.Key("transform", 3)),
                DataValue.NullValue.INSTANCE));
            var raw = new DataValue.RecordValue(Map.of("source", source, "phase", new DataValue.TextValue("SLEEPING"),
                "call_stack", stack, "locals", input));
            var visible = ScratchDebugSource.taskFields(menu, 7, raw);
            assertTrue(((DataValue.TextValue)visible.field(label("task_current_block"))).value().contains("actual_delay"));
            var calls = assertInstanceOf(DataValue.ListValue.class, visible.field(label("stack_title")));
            var known = assertInstanceOf(DataValue.TextValue.class, calls.values().getFirst());
            assertTrue(known.value().contains("transform @ 3") && known.value().contains("actual_delay"));
            assertTrue(known.value().contains(Component.translatable("create_biotech.biocomputing.flow_editor.main_hat").getString()));
            assertFalse(known.value().contains("installed_sleep"));
            assertEquals(new DataValue.TextValue(label("stack_frame_unknown")), calls.values().getLast());
            var advanced = assertInstanceOf(DataValue.RecordValue.class, visible.field(label("advanced")));
            assertEquals(source, advanced.field("source"));
            assertEquals(stack, advanced.field("call_stack"));
            assertEquals(input, visible.field("locals"));

            var stale = ScratchDebugSource.taskFields(menu, 8, raw);
            assertEquals(new DataValue.TextValue(label("source_unknown")), stale.field(label("task_current_block")));
            var staleFrame = (DataValue.TextValue)((DataValue.ListValue)stale.field(label("stack_title"))).values().getFirst();
            assertFalse(staleFrame.value().contains("actual_delay"));
            assertEquals(advanced, stale.field(label("advanced")));
        } finally { Language.inject(oldLanguage); }
    }

    @SuppressWarnings("removal")
    private static ComputerProgramMenu clientMenu() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ComputerProgramMenu)((sun.misc.Unsafe)field.get(null)).allocateInstance(ComputerProgramMenu.class);
    }

    private static String label(String name) {
        return Component.translatable("create_biotech.biocomputing.debug." + name).getString();
    }

    private static FlowProgram.Invoke call(String source, ModuleDefinition.Key key) {
        return new FlowProgram.Invoke(source, key, new FlowCallBindings(Map.of("ticks", "ticks"), Map.of()));
    }

    private static DataValue frame(String source, ModuleDefinition.Key key) {
        return new DataValue.RecordValue(Map.of("source", new DataValue.TextValue(source),
            "procedure", new DataValue.TextValue(key.id()), "revision", new DataValue.IntegerValue(key.revision())));
    }
}
