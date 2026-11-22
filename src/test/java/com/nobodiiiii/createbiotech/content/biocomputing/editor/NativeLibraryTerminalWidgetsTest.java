package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;

/** Headless real widgets connected to real Behaviour/Session; only the network boundary is in-process. */
class NativeLibraryTerminalWidgetsTest {
    final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception {ComputerProgramScreenTest.bootstrap();NativeLibraryTerminalSessionTest.bootstrap();}
    @BeforeEach void client() throws Exception {environment.client();}
    @AfterEach void restore() throws Exception {environment.restore();}
    static void tick(ComputerTerminalScreen viewer)throws Exception{var m=ComputerTerminalScreen.class.getDeclaredMethod("containerTick");m.setAccessible(true);m.invoke(viewer);}
    static EditBox field(ComputerTerminalScreen viewer){return viewer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();}
    static void response(ComputerProgramMenu menu,NativeLibraryTerminalSessionTest.Fixture f,ComputerControlSession.Action action,ComputerControlSession.Answer answer)throws Exception {
        set(menu,"latestControlIntent",f.intent);set(menu,"latestControlAction",action);
        menu.accept(new ProgramStatusResponse(menu.containerId,f.nonce,f.intent,f.computer.programRevision(),answer.code(),answer.detail(),
            ProgramEditorWire.encodeValue(answer.dedicatedStatus().orElseThrow(),f.computer.hardware().limits().archive())));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426})
    void typingRefreshSubmitInspectAndExplicitClaimUseOnlyPublicControlsWithoutClearingDraft(int width)throws Exception {
        var f=new NativeLibraryTerminalSessionTest.Fixture();f.start();var original=f.computer.flowSnapshot().orElseThrow();
        var parent=screen();var menu=(ComputerProgramMenu)get(parent,"menu");var limits=f.computer.hardware().limits();
        set(menu,"nonce",f.nonce);set(menu,"response",Optional.of(new ProgramEditorResponse(0,f.nonce,1,f.computer.programRevision(),"ready","",limits.hardware(),limits.archive(),new byte[0])));
        var viewer=new ComputerTerminalScreen(menu,(Inventory)get(parent,"editorInventory"),Component.empty(),parent);
        Screen[] opened={viewer};var submissions=new ArrayList<TerminalSubmit>();var claims=new ArrayList<TerminalTaskClaim>();var actions=new ArrayList<ComputerControlSession.Action>();
        set(viewer,"navigator",(Consumer<Screen>)(next->opened[0]=next));
        set(viewer,"request",(Consumer<ComputerControlSession.Action>)(action->{try{actions.add(action);response(menu,f,action,f.apply(action,new DataValue.RecordValue(Map.of())));}catch(Exception e){throw new RuntimeException(e);}}));
        set(viewer,"submitRequest",(Consumer<TerminalSubmit>)(submit->{try{submissions.add(submit);var answer=f.apply(ComputerControlSession.Action.TERMINAL_SUBMIT,submit.encode());assertEquals("ok",answer.code());response(menu,f,ComputerControlSession.Action.TERMINAL_SUBMIT,answer);}catch(Exception e){throw new RuntimeException(e);}}));
        set(viewer,"claimRequest",(Consumer<TerminalTaskClaim>)(claim->{try{claims.add(claim);var answer=f.apply(ComputerControlSession.Action.TERMINAL_CLAIM,claim.encode());assertEquals("ok",answer.code());response(menu,f,ComputerControlSession.Action.TERMINAL_CLAIM,answer);}catch(Exception e){throw new RuntimeException(e);}}));
        init(viewer,width);tick(viewer);bounds(viewer);
        assertEquals(original,f.computer.flowSnapshot().orElseThrow());
        String typed="{\"typed\":42} literal 文本";field(viewer).setValue(typed);
        assertTrue(submissions.isEmpty());assertEquals(original,f.computer.flowSnapshot().orElseThrow(),"Typing alone never starts a task");
        button(viewer,"refresh").onClick(0,0);tick(viewer);assertEquals(typed,field(viewer).getValue());assertEquals(original,f.computer.flowSnapshot().orElseThrow());
        button(viewer,"submit").onClick(0,0);tick(viewer);assertEquals(1,submissions.size());assertEquals(1,f.computer.flowSnapshot().orElseThrow().tasks().contexts().size());
        assertFalse(button(viewer,"submit").active,"One publication cannot submit twice");
        // Complete that exact UI-admitted task through real four-owner serial/mechanical execution, not injected final states.
        NativeLibraryTerminalExecutionTest.completeSubmitted(f,false,true,false);
        button(viewer,"refresh").onClick(0,0);tick(viewer);bounds(viewer);
        assertEquals(typed,field(viewer).getValue(),"Task result changes do not clear the stable main-output input context");
        String nextDraft="do not send yet";field(viewer).setValue(nextDraft);button(viewer,"refresh").onClick(0,0);tick(viewer);assertEquals(nextDraft,field(viewer).getValue());
        var retained=f.computer.flowSnapshot().orElseThrow();button(viewer,"tasks").onClick(0,0);var tasks=assertInstanceOf(ComputerTerminalTasksScreen.class,opened[0]);init(tasks,width);bounds(tasks);
        set(tasks,"navigator",(Consumer<Screen>)(next->opened[0]=next));assertTrue(button(tasks,"claim_task").active);button(tasks,"inspect_task").onClick(0,0);
        var inspector=assertInstanceOf(ComputerValueTreeScreen.class,opened[0]);init(inspector,width);
        var result=(DataValue.RecordValue)((ValueTree)get(inspector,"tree")).inputs().field("result");
        assertEquals(new DataValue.TextValue(typed),((DataValue.RecordValue)result.field("read_result")).field("value"));assertTrue(claims.isEmpty());assertEquals(retained,f.computer.flowSnapshot().orElseThrow());
        inspector.onClose();assertSame(tasks,opened[0]);button(tasks,"claim_task").onClick(0,0);tick(viewer);
        assertEquals(1,claims.size());assertTrue(f.computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());assertEquals(1,submissions.size());
        button(viewer,"refresh").onClick(0,0);tick(viewer);assertEquals(nextDraft,field(viewer).getValue());
        assertEquals(original.state(),f.computer.flowSnapshot().orElseThrow().state());
        assertTrue(actions.stream().allMatch(a->a==ComputerControlSession.Action.READ_TERMINAL));
    }
}
