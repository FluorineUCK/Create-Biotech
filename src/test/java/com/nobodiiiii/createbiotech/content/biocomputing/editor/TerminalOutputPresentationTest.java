package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;

class TerminalOutputPresentationTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); TerminalBehaviourTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @Test void sleepingFlowShowsPendingOutputInsteadOfTypeErrorWithoutAdvancingItsProgram() throws Exception {
        var layout = new TerminalDefinition("value", Map.of("value", new TerminalDefinition.Text(
            new TerminalBinding.Path(new ValuePath("output", List.of()).key("ticks")))));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ticks", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("sleep", "ticks")),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(20))))));
        workspace.setTerminal(Optional.of(layout));
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); computer.step();
        var before = computer.flowSnapshot();
        var fixture = new Viewer(); fixture.publish(computer.terminalStatus());
        var view = fixture.view();
        assertEquals("output_pending", view.problem().orElseThrow().code());
        assertTrue(view.value().isEmpty(), "Pending is presentation metadata, not a fabricated null or zero value");
        assertNotNull(TerminalControlsTest.button(fixture.screen, "output_pending"));
        assertEquals(before, computer.flowSnapshot(), "Opening and projecting a terminal cannot execute a tick");
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), fixture.actions);
        var opened = new Screen[1];
        set(fixture.screen, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        TerminalControlsTest.click(fixture.screen, "output_pending");
        assertInstanceOf(ComputerValueTreeScreen.class, opened[0]);
        assertEquals(before, computer.flowSnapshot(), "Inspecting absence cannot advance a sleeping program");
        var oldReply = fixture.menu.controlResponse().orElseThrow();
        // Only these explicit debugger operations advance the local clock, never the terminal refresh.
        for (int i = 0; i < 24; i++) computer.step();
        var complete = computer.flowSnapshot();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE, complete.orElseThrow().state().status());
        TerminalControlsTest.click(fixture.screen, "refresh"); fixture.publish(computer.terminalStatus());
        assertTrue(fixture.view().problem().isEmpty());
        assertEquals(new DataValue.IntegerValue(20), fixture.view().value().orElseThrow());
        var displayed = fixture.view(); fixture.menu.accept(oldReply); fixture.tick();
        assertEquals(displayed, fixture.view(), "Late pending observations cannot erase a fresh completed result");
        assertEquals(complete, computer.flowSnapshot());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.READ_TERMINAL), fixture.actions);
    }

    @Test void failedFlowDisplaysFailureNotAnEndlessPendingResult() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ticks", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("sleep", "ticks")),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(-1))))));
        workspace.setTerminal(Optional.of(new TerminalDefinition("value", Map.of("value", new TerminalDefinition.Text(
            new TerminalBinding.Path(new ValuePath("output", List.of()).key("ticks")))))));
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); computer.step();
        var before = computer.flowSnapshot();
        var fixture = new Viewer(); fixture.publish(computer.terminalStatus());
        assertEquals("output_failed", fixture.view().problem().orElseThrow().code());
        assertNotNull(TerminalControlsTest.button(fixture.screen, "output_failed"));
        assertEquals(before, computer.flowSnapshot());
    }

    @Test void installedPublicAckTerminalReadsPreserveCheckpointAndShowExplicitBusinessFailure() throws Exception {
        var lower = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        var previous=new ProgramDocument(TerminalBehaviourTest.counter(),Map.of(),Optional.empty());
        lower.installDocument(previous,ComputerRuntime.MemoryPolicy.RESET);var before=lower.snapshot();long oldRevision=lower.programRevision();
        var doc = NativeLibraryResultTerminalTest.published("ack_a");
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable.class,()->lower.installDocument(doc,ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(previous,lower.document().orElseThrow());assertEquals(before,lower.snapshot());assertEquals(oldRevision,lower.programRevision());
        var computer = new com.nobodiiiii.createbiotech.content.biocomputing.computer.MasterTerminalFixture().getBehaviour(ComputerBehaviour.TYPE);
        assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.installDocument(doc, ComputerRuntime.MemoryPolicy.RESET));
        var fixture = new Viewer();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        long revision = computer.programRevision();
        var pending = computer.flowSnapshot();
        var serialBefore = computer.serialStatus(); var pinsBefore = computer.signalOutputs(); var limitsBefore = computer.hardware();
        var answer = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL,
            () -> { throw new AssertionError("Read must not decode or dispatch input"); }, computer).orElseThrow();
        fixture.publish(answer.dedicatedStatus().orElseThrow());
        assertEquals("output_pending", find(fixture.view(), "acknowledged").problem().orElseThrow().code());
        assertTrue(find(fixture.view(), "acknowledged").value().isEmpty());
        assertEquals(pending, computer.flowSnapshot());
        // The public default sequence is -1: explicit execution returns a business error before any XBUS write.
        for (int i=0;i<1000 && computer.flowSnapshot().orElseThrow().state().status() != com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE;i++) computer.step();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE,computer.flowSnapshot().orElseThrow().state().status());
        var complete = computer.flowSnapshot();
        TerminalControlsTest.click(fixture.screen,"refresh");
        answer = session.apply(nonce, 2, revision, ComputerControlSession.Action.READ_TERMINAL,
            () -> { throw new AssertionError("Read decoded inputs"); }, computer).orElseThrow();
        fixture.publish(answer.dedicatedStatus().orElseThrow());
        assertEquals(new DataValue.BooleanValue(false),find(fixture.view(),"acknowledged").value().orElseThrow());
        assertEquals(new DataValue.TextValue("invalid_sequence"),find(fixture.view(),"error").value().orElseThrow());
        assertEquals(DataValue.NullValue.INSTANCE,find(fixture.view(),"ack_reply").value().orElseThrow());
        assertEquals(complete,computer.flowSnapshot()); assertEquals(revision,computer.programRevision());
        assertEquals(doc,computer.document().orElseThrow());
        assertEquals(serialBefore,computer.serialStatus()); assertEquals(pinsBefore,computer.signalOutputs()); assertEquals(limitsBefore,computer.hardware());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL,ComputerControlSession.Action.READ_TERMINAL),fixture.actions);
    }

    @Test void everyPublishedLayoutShowsPendingFailedAndCompleteRecordsThroughActualTerminalControls() throws Exception {
        for(String role:List.of("read_a","write_a","inspect_a","ack_a")) {
            var terminal=NativeLibraryResultTerminalTest.published(role).terminal().orElseThrow();
            var fixture=new Viewer();
            var names=terminal.nodes().entrySet().stream().filter(e->e.getValue() instanceof TerminalDefinition.Text text && text.value() instanceof TerminalBinding.Path).map(Map.Entry::getKey).toList();
            for(String phase:List.of("WAITING_READ","FAILED")) {
                fixture.publish(envelope(terminal,DataValue.NullValue.INSTANCE,phase));
                for(String name:names) {
                    var field=find(fixture.view(),name);
                    assertEquals(phase.equals("FAILED")?"output_failed":"output_pending",field.problem().orElseThrow().code());
                    assertTrue(field.value().isEmpty());
                }
            }
            var raw=new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("read"),"value",new DataValue.ListValue(List.of(new DataValue.IntegerValue(44)))));
            var fields=new LinkedHashMap<String,DataValue>();
            for(String name:names) fields.put(name,name.equals("success") || name.equals("acknowledged")?new DataValue.BooleanValue(true):raw);
            fixture.publish(envelope(terminal,new DataValue.RecordValue(fields),"COMPLETE"));
            for(String name:names) assertEquals(fields.get(name),find(fixture.view(),name).value().orElseThrow());
            var shown=new Screen[1];set(fixture.screen,"navigator",(Consumer<Screen>)(next->shown[0]=next));
            var rawButton=fixture.screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
                .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
                .filter(b->b.getMessage().getString().contains(" · ")).findFirst().orElseThrow();
            rawButton.onClick(0,0);
            var tree=assertInstanceOf(ComputerValueTreeScreen.class,shown[0]);TerminalControlsTest.init(tree,426);
            assertEquals(raw,((ValueTree)get(tree,"tree")).inputs().field("value"));assertNull(get(tree,"generate"));
            treeControl(tree,new ValuePath("value",List.of()),"expandButtons");
            treeControl(tree,new ValuePath("value",List.of()).key("value"),"expandButtons");
            treeControl(tree,new ValuePath("value",List.of()).key("value").index(0),"rowButtons");
            assertTrue(String.join("",(List<String>)get(tree,"detailLines")).contains("44"));
            assertEquals(raw,((ValueTree)get(tree,"tree")).inputs().field("value"));
            assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL),fixture.actions,"Exploration must not emit protocol actions");
        }
    }
    @SuppressWarnings("unchecked")
    private static void treeControl(ComputerValueTreeScreen screen,ValuePath path,String control) throws Exception {
        for(int attempt=0;attempt<10;attempt++) {
            var page=(ValueTree.Page)get(screen,"visible");
            for(int i=0;i<page.rows().size();i++) if(page.rows().get(i).path().equals(path)) {
                ((List<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton>)get(screen,control)).get(i).onClick(0,0);return;
            }
            if(page.index()+1>=page.count())break;
            screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
                .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
                .filter(button->button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key && key.getKey().equals("create_biotech.biocomputing.tree.tree_next"))
                .findFirst().orElseThrow().onClick(0,0);
        }
        throw new AssertionError("Missing visible record/list path "+path);
    }
    private static DataValue.RecordValue envelope(TerminalDefinition layout,DataValue output,String phase) {
        return new DataValue.RecordValue(Map.of("terminal",TerminalCodec.encode(layout),"output",output,"memory",new DataValue.RecordValue(Map.of()),"phase",new DataValue.TextValue(phase),"paused",new DataValue.BooleanValue(true)));
    }
    private static TerminalProjection.View find(TerminalProjection.View root,String node) {
        var pending=new ArrayDeque<TerminalProjection.View>();pending.add(root);
        while(!pending.isEmpty()){var current=pending.removeFirst();if(current.node().equals(node))return current;pending.addAll(current.children());}
        throw new AssertionError("Missing public output "+node);
    }

    private static final class Viewer {
        final ComputerTerminalScreen screen;
        final ComputerProgramMenu menu;
        final UUID nonce = UUID.randomUUID();
        final ArchiveLimits limits = new ArchiveLimits(100000, 1000000, 10000000);
        final List<ComputerControlSession.Action> actions = new ArrayList<>();
        long intent;
        Viewer() throws Exception {
            var parent = screen(); menu = (ComputerProgramMenu)get(parent, "menu");
            set(menu, "nonce", nonce);
            set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 1, "ready", "",
                new ResourceLimits(1000, 1000000, 1), limits, new byte[0])));
            screen = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), Component.empty(), parent);
            set(screen, "request", (Consumer<ComputerControlSession.Action>)actions::add);
            TerminalControlsTest.init(screen, 426); tick();
        }
        void publish(DataValue.RecordValue envelope) throws Exception {
            intent++;
            set(menu, "latestControlIntent", intent);
            set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
            menu.accept(new ProgramStatusResponse(0, nonce, intent, 1, "ok", "", ProgramEditorWire.encodeValue(envelope, limits)));
            tick(); TerminalControlsTest.bounds(screen);
        }
        TerminalProjection.View view() throws Exception { return (TerminalProjection.View)get(screen, "view"); }
        void tick() throws Exception {
            var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(screen);
        }
    }
}
