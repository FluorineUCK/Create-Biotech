package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.Map;
import org.junit.jupiter.api.*;

class ComputerCanvasPortsTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void dragAndReverseClickPairConnectNamedInputsWithoutOpeningAModal() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String a = draft.add(ProgramDraftTest.number(5), 20, 20), b = draft.add(ProgramDraftTest.number(7), 20, 110);
        String sum = draft.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"), 220, 20);
        draft.output("answer", sum);
        press(screen, 150, 62, 0); release(screen, 220, 82, 0); // no intermediate move callback
        assertEquals(Map.of("left", a), draft.node(sum).connections()); assertNull(get(screen, "editing"));
        click(screen, 220, 96, 0); click(screen, 150, 152, 0);
        assertEquals(Map.of("left", a, "right", b), draft.node(sum).connections());
        var machine = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM));
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick();
        assertEquals(new DataValue.IntegerValue(12), machine.report().output().orElseThrow().field("answer"));
        assertEquals(220, draft.node(sum).x()); assertEquals(Map.of("answer", sum), draft.outputs());
        assertFalse((boolean)get(screen, "waiting")); assertFalse((boolean)get(screen, "requested"));
    }

    static void click(ComputerProgramScreen screen, double x, double y, int button) throws Exception { press(screen,x,y,button); release(screen,x,y,button); }

    @Test void typeMismatchRetainsOldConnectionAndCanBeCancelled() throws Exception {
        var screen=screen(); var draft=(ProgramDraft)get(screen,"draft");
        String a=draft.add(ProgramDraftTest.number(5),20,20);
        String text=draft.add(new ComputationGraph.Constant(new DataValue.TextValue("not a number")),20,110);
        String sum=draft.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"left","right"),220,20);
        draft.connect(sum,"left",a); draft.connect(sum,"right",a); draft.output("answer",sum);
        click(screen,220,82,0); click(screen,150,152,0);
        assertEquals(a,draft.node(sum).connections().get("left"));
        assertTrue(diagnostic(screen).contains("TYPE_MISMATCH"));
        screen.keyPressed(256,0,0);
        assertNull(get(screen,"wireFrom")); assertEquals(3,draft.nodes().size());
    }

    @Test void cancellingReplacingDisconnectingAndCyclesNeverSilentlyDeleteNodes() throws Exception {
        var screen=screen(); var draft=(ProgramDraft)get(screen,"draft");
        String a=draft.add(ProgramDraftTest.number(5),20,20);
        String sum=draft.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"left","right"),220,20);
        draft.connect(sum,"left",a); draft.connect(sum,"right",a); draft.output("answer",sum);
        click(screen,220,82,0); click(screen,350,62,0); // self-feedback, not shared state
        assertEquals(a,draft.node(sum).connections().get("left"));
        assertTrue(diagnostic(screen).contains("GRAPH_CYCLE"));
        screen.keyPressed(256,0,0);
        press(screen,220,82,0); screen.mouseReleased(-1,-1,0);
        assertNull(get(screen,"wireFrom")); assertEquals(a,draft.node(sum).connections().get("left"));
        click(screen,220,82,1);
        assertEquals(Map.of("right",a),draft.node(sum).connections()); assertEquals(2,draft.nodes().size());
        var viewport=(CanvasViewport)get(screen,"viewport"); viewport.pan(12,8); viewport.zoomAt(200,100,2);
        click(screen,150,62,0); click(screen,220,82,0);
        assertEquals(Map.of("left",a,"right",a),draft.node(sum).connections());
        click(screen,220,82,0); screen.init();
        assertNull(get(screen,"wireFrom")); assertEquals(a,draft.node(sum).connections().get("left"));
    }

    @Test void clickingAnotherSourceSwitchesThePendingEndpointRatherThanUsingAnOldSelection() throws Exception {
        var screen=screen(); var draft=(ProgramDraft)get(screen,"draft");
        draft.add(ProgramDraftTest.number(5),20,20); String b=draft.add(ProgramDraftTest.number(7),20,110);
        String sum=draft.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"left","right"),220,20);
        click(screen,150,62,0); click(screen,150,152,0); click(screen,220,82,0);
        assertEquals(Map.of("left",b),draft.node(sum).connections());
    }

    @Test void toolbarGraphSwitchCancelsTheWireAndNeverConnectsMatchingIdsAcrossGraphs() throws Exception {
        var screen=screen(); var root=(ProgramDraft)get(screen,"draft"); root.add(ProgramDraftTest.number(5),20,20);
        click(screen,150,62,0); toolbar(screen,"modules").onClick(0,0);
        ((net.minecraft.client.gui.components.EditBox)get(screen,"moduleName")).setValue("leaf"); toolbar(screen,"create_module").onClick(0,0);
        var child=(ProgramDraft)get(screen,"draft"); assertNotSame(root,child); assertNull(get(screen,"wireFrom"));
        String id=child.add(new ComputationGraph.Field("source","value"),220,20);
        assertEquals(root.nodes().getFirst().id(),id); click(screen,220,82,0); screen.keyPressed(256,0,0);
        assertTrue(child.node(id).connections().isEmpty()); assertEquals(1,root.nodes().size());
    }

    private static com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton toolbar(ComputerProgramScreen screen,String key) {
        return screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                &&t.getKey().equals("create_biotech.biocomputing.editor."+key)).findFirst().orElseThrow();
    }
    static void press(ComputerProgramScreen screen, double x, double y, int button) throws Exception {
        var viewport = (CanvasViewport)get(screen, "viewport"); screen.mouseClicked(origin(screen,"canvasX")+viewport.screenX(x), origin(screen,"canvasY")+viewport.screenY(y), button);
    }
    private static String diagnostic(ComputerProgramScreen screen) throws Exception {
        var message=(net.minecraft.network.chat.Component)get(screen,"notice");
        return String.valueOf(((net.minecraft.network.chat.contents.TranslatableContents)message.getContents()).getArgs()[0]);
    }
    static void release(ComputerProgramScreen screen, double x, double y, int button) throws Exception {
        var viewport = (CanvasViewport)get(screen, "viewport"); screen.mouseReleased(origin(screen,"canvasX")+viewport.screenX(x), origin(screen,"canvasY")+viewport.screenY(y), button);
    }
    private static int origin(ComputerProgramScreen screen,String name) throws Exception {
        var method=ComputerProgramScreen.class.getDeclaredMethod(name); method.setAccessible(true);return(int)method.invoke(screen);
    }
}
