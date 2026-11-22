package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

/** Real Create widget callbacks over the same root draft owned by the program screen. */
public class ComputerSendScreenTest {
    private Object oldClient;
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        oldClient=field(Minecraft.class,"instance").get(null); var client=allocate(Minecraft.class);
        set(client,"font",new ComputerProgramScreenTest.Metrics()); field(Minecraft.class,"instance").set(null,client);
    }
    @AfterEach void restore() throws Exception { field(Minecraft.class,"instance").set(null,oldClient); }

    @Test void actualButtonsCreateSelectUpdateAndDeleteOrderedBindings() throws Exception {
        var screen=screen(426); var draft=(ProgramDraft)get(screen,"draft");
        fields(screen,"endpoint","payload"); click(screen,"create");
        fields(screen,"other_endpoint","payload"); click(screen,"create");
        assertEquals(List.of(new ProgramDefinition.Send("endpoint","payload"),new ProgramDefinition.Send("other_endpoint","payload")),draft.sends());
        binding(screen,0).onClick(0,0); fields(screen,"other_endpoint","other_payload"); click(screen,"update");
        assertEquals(List.of(new ProgramDefinition.Send("other_endpoint","other_payload"),new ProgramDefinition.Send("other_endpoint","payload")),draft.sends());
        click(screen,"delete");
        assertEquals(List.of(new ProgramDefinition.Send("other_endpoint","payload")),draft.sends());
    }

    @Test void conditionControlsPersistAcrossModesSelectionAndResizeWithoutInstalling() throws Exception {
        var screen=screen(320,"request",List.of("request","stored"));var draft=(ProgramDraft)get(screen,"draft");
        fields(screen,"endpoint","payload");click(screen,"condition");click(screen,"condition_disabled");
        ((EditBox)get(screen,"conditionOutput")).setValue("enabled");assertLayout(screen);click(screen,"create");
        assertEquals(new ProgramDefinition.Send("endpoint","payload").when("enabled"),draft.sends().getFirst());
        binding(screen,0).onClick(0,0);click(screen,"condition");set(screen,"width",426);screen.init();assertLayout(screen);
        assertEquals("enabled",((EditBox)get(screen,"conditionOutput")).getValue());
        click(screen,"mode_request");click(screen,"request_options");rpcFields(screen,"lookup","stored");click(screen,"update");
        assertEquals(ProgramDefinition.Send.request("endpoint","payload","lookup","stored").when("enabled"),draft.sends().getFirst());
        draft.setSend(0,"other_endpoint","other_payload");assertEquals(Optional.of("enabled"),draft.sends().getFirst().conditionOutput());
        binding(screen,0).onClick(0,0);click(screen,"condition");click(screen,"condition_enabled");click(screen,"update");
        assertTrue(draft.sends().getFirst().conditionOutput().isEmpty());
        click(screen,"mode_reply");click(screen,"condition");assertLayout(screen);
        assertEquals(0L,get(get(screen,"menu"),"latestIntent"));
    }

    @Test void explicitContinuationControlsPreserveCaptureAcrossResizeAndOutputEdits() throws Exception {
        var screen=screen(320,"request",List.of("request","stored"));var draft=(ProgramDraft)get(screen,"draft");
        click(screen,"mode_request");fields(screen,"endpoint","payload");click(screen,"request_options");rpcFields(screen,"lookup","stored");
        click(screen,"continuation");click(screen,"capture_disabled");
        ((EditBox)get(screen,"captureOutput")).setValue("payload");
        ((EditBox)get(screen,"captureInput")).setValue("saved");
        assertLayout(screen);click(screen,"create");
        var expected=ProgramDefinition.Send.continueRequest("endpoint","payload","lookup","stored","payload","saved");
        assertEquals(expected,draft.sends().getFirst());
        binding(screen,0).onClick(0,0);click(screen,"continuation");set(screen,"width",426);screen.init();assertLayout(screen);
        assertEquals("payload",((EditBox)get(screen,"captureOutput")).getValue());
        assertEquals("saved",((EditBox)get(screen,"captureInput")).getValue());click(screen,"update");
        assertEquals(expected,draft.sends().getFirst());
        draft.setSend(0,"other_endpoint","other_payload");
        assertEquals(expected.continuation(),draft.sends().getFirst().continuation());
        binding(screen,0).onClick(0,0);click(screen,"continuation");click(screen,"capture_enabled");click(screen,"update");
        assertTrue(draft.sends().getFirst().continuation().isEmpty());
    }

    @Test void namedContinuationCallbackOffersReplyWithoutAnEditableDestination() throws Exception {
        var screen=screen(320,"stored",List.of("request","stored"));
        click(screen,"mode_reply");assertNull(get(screen,"target"));
        ((EditBox)get(screen,"payload")).setValue("payload");click(screen,"create");
        assertEquals(ProgramDefinition.Send.reply("payload"),((ProgramDraft)get(screen,"draft")).sends().getFirst());
    }

    @Test void repeatedBindingsRemainDistinctAndEditsDoNotSendOrInstall() throws Exception {
        var screen=screen(320); var draft=(ProgramDraft)get(screen,"draft"); var menu=get(screen,"menu");
        fields(screen,"endpoint","payload"); click(screen,"create"); click(screen,"create");
        assertEquals(2,draft.sends().size());
        assertEquals(0L,get(menu,"latestIntent")); assertEquals(0L,get(menu,"latestControlIntent"));
    }

    @Test void createCallbackFailurePropagatesAfterExactlyOneSuccessfulMutation() throws Exception {
        var screen=screen(426);var draft=(ProgramDraft)get(screen,"draft");
        var callbackFailure=new IllegalStateException("rebuild failed");
        set(screen,"changed",(Runnable)()->{throw callbackFailure;});
        fields(screen,"endpoint","payload");

        var thrown=assertThrows(IllegalStateException.class,()->click(screen,"create"));

        assertSame(callbackFailure,thrown);
        assertEquals(List.of(new ProgramDefinition.Send("endpoint","payload")),draft.sends());
    }

    @Test void updateCallbackFailurePropagatesAfterExactlyOneSuccessfulMutation() throws Exception {
        var screen=screen(426);var draft=(ProgramDraft)get(screen,"draft");
        draft.addSend("endpoint","payload");screen.init();binding(screen,0).onClick(0,0);
        var callbackFailure=new IllegalStateException("rebuild failed");
        set(screen,"changed",(Runnable)()->{throw callbackFailure;});
        fields(screen,"other_endpoint","other_payload");

        var thrown=assertThrows(IllegalStateException.class,()->click(screen,"update"));

        assertSame(callbackFailure,thrown);
        assertEquals(List.of(new ProgramDefinition.Send("other_endpoint","other_payload")),draft.sends());
    }

    @Test void invalidExistingNamesStayVisibleAndRepairableAcrossResize() throws Exception {
        var screen=screen(320); var draft=(ProgramDraft)get(screen,"draft");
        draft.addSend("missing endpoint with a very long literal suffix","missing payload with a very long literal suffix"); screen.init();
        binding(screen,0).onClick(0,0);
        assertEquals("missing endpoint with a very long literal suffix",((EditBox)get(screen,"target")).getValue());
        assertEquals("missing payload with a very long literal suffix",((EditBox)get(screen,"payload")).getValue());
        set(screen,"width",426); screen.init();
        assertEquals(0,get(screen,"selected"));
        assertEquals("missing endpoint with a very long literal suffix",((EditBox)get(screen,"target")).getValue());
        fields(screen,"endpoint","payload"); click(screen,"update");
        assertEquals(new ProgramDefinition.Send("endpoint","payload"),draft.sends().getFirst());
    }

    @Test void importedOutputNamesLongerThanTheOldFieldLimitRemainLossless() throws Exception {
        var screen=screen(320);var draft=(ProgramDraft)get(screen,"draft");String longName="output_"+"x".repeat(1500);
        draft.addSend(longName,longName);screen.init();binding(screen,0).onClick(0,0);
        assertEquals(longName,((EditBox)get(screen,"target")).getValue());assertEquals(longName,((EditBox)get(screen,"payload")).getValue());
        click(screen,"update");assertEquals(new ProgramDefinition.Send(longName,longName),draft.sends().getFirst());
    }

    @Test void compactAndWideLayoutsKeepControlsInsideAndNonOverlapping() throws Exception {
        for(int width:List.of(320,426)){var screen=screen(width,"request",List.of("request","result"));assertLayout(screen);
            click(screen,"mode_request");assertLayout(screen);click(screen,"request_options");assertLayout(screen);
            click(screen,"mode_reply");assertLayout(screen);set(screen,"width",width==320?426:320);screen.init();assertLayout(screen);}
    }

    @Test void actualModeAndTabControlsCreateRequestAndPreserveAllFieldsWhenEditing() throws Exception {
        var screen=screen(320,"tick",List.of("tick","reply"));var draft=(ProgramDraft)get(screen,"draft");
        click(screen,"mode_request"); fields(screen,"endpoint","payload"); click(screen,"request_options");
        rpcFields(screen,"answer","reply"); click(screen,"bindings"); click(screen,"request_options"); click(screen,"create");
        assertEquals(ProgramDefinition.Send.request("endpoint","payload","answer","reply"),draft.sends().getFirst());
        binding(screen,0).onClick(0,0);click(screen,"bindings");fields(screen,"other_endpoint","other_payload");
        click(screen,"request_options");rpcFields(screen,"other_answer","tick");click(screen,"update");
        assertEquals(ProgramDefinition.Send.request("other_endpoint","other_payload","other_answer","tick"),draft.sends().getFirst());
    }

    @Test void replyIsNotOfferedAtRootAndHasNoEditableTarget() throws Exception {
        var ordinary=screen(426,null,List.of("tick","reply"));
        assertFalse(hasButton(ordinary,"mode_reply"));
        var reply=screen(426,"request",List.of("tick","request"));click(reply,"mode_reply");
        assertNull(get(reply,"target"));assertFalse(hasButton(reply,"request_options"));
        ((EditBox)get(reply,"payload")).setValue("payload");click(reply,"create");
        assertEquals(ProgramDefinition.Send.reply("payload"),((ProgramDraft)get(reply,"draft")).sends().getFirst());
    }

    @Test void invalidRequestRetainsDraftAndCanBeRepairedAgainstLiveWorkspaceEntries() throws Exception {
        var names=new ArrayList<>(List.of("tick"));var screen=screen(426,"tick",names);var draft=(ProgramDraft)get(screen,"draft");
        click(screen,"mode_request");fields(screen,"endpoint","payload");click(screen,"request_options");rpcFields(screen,"slot","missing");click(screen,"create");
        assertTrue(draft.sends().isEmpty());assertFalse(((Component)get(screen,"notice")).getString().isBlank());
        names.add("missing");click(screen,"create");
        assertEquals(ProgramDefinition.Send.request("endpoint","payload","slot","missing"),draft.sends().getFirst());
    }

    @Test void importedAllModeRecordsRemainExactThroughWidgetsResizeWireAndInstalledHost() throws Exception {
        String longTarget="target_"+"t".repeat(300),longPayload="payload_"+"p".repeat(300);
        var fixture=RequestReplyFixtures.client();var base=fixture.program();
        var rootOutputs=new LinkedHashMap<>(base.graph().outputs());
        rootOutputs.put(longTarget,rootOutputs.get("target"));rootOutputs.put(longPayload,rootOutputs.get("payload"));
        var rootGraph=new ComputationGraph(base.graph().inputs(),base.graph().nodes(),rootOutputs);
        var message=new ProgramDefinition.Send(longTarget,longPayload);
        var request=ProgramDefinition.Send.request(longTarget,longPayload,"lookup_literal","result");
        var result=base.entries().get("result");
        var requestEntry=new ProgramDefinition.Entry(result.graph(),result.writes(),List.of(ProgramDefinition.Send.reply("payload")));
        var entries=new LinkedHashMap<>(base.entries());entries.put("request",requestEntry);
        var archive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(rootGraph,base.cells(),base.writes(),List.of(message,request),entries),fixture.modules());
        var workspace=new ProgramWorkspace(ProgramDocument.of(archive));

        var root=screen(320,null,workspace::entryNames,workspace,workspace.root());
        binding(root,0).onClick(0,0);assertSendFields(root,message);set(root,"width",426);root.init();assertSendFields(root,message);
        binding(root,1).onClick(0,0);assertSendFields(root,request);click(root,"request_options");assertSendFields(root,request);click(root,"bindings");assertSendFields(root,request);click(root,"back");
        var requestScreen=screen(320,"request",workspace::entryNames,workspace,workspace.entry("request"));
        binding(requestScreen,0).onClick(0,0);assertSendFields(requestScreen,ProgramDefinition.Send.reply("payload"));set(requestScreen,"width",426);requestScreen.init();assertSendFields(requestScreen,ProgramDefinition.Send.reply("payload"));click(requestScreen,"back");

        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var received=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
        assertEquals(List.of(message,request),received.archive().program().sends());
        assertEquals(List.of(ProgramDefinition.Send.reply("payload")),received.archive().program().entries().get("request").sends());
        var host=new ComputerBlockHost(()->{});
        assertEquals(ComputerRuntime.InstallResult.APPLIED,host.install(received.archive(),ComputerRuntime.MemoryPolicy.RESET,EventEntryFixtures.LIMITS,true));
        assertEquals(received.archive(),host.snapshot().orElseThrow().program());
    }

    @Test void malformedImportedRpcReferencesSurviveWidgetsAndWireButHostRejectsWithoutReplacingProgram() throws Exception {
        var valid=RequestReplyFixtures.client();var base=valid.program();
        assertRejectedMalformed(valid,new ProgramDefinition(base.graph(),base.cells(),base.writes(),
            List.of(ProgramDefinition.Send.request("missing_output","payload","slot_literal","result")),base.entries()),null,"missing_output");
        assertRejectedMalformed(valid,new ProgramDefinition(base.graph(),base.cells(),base.writes(),
            List.of(ProgramDefinition.Send.request("target","payload","slot_literal","missing_handler")),base.entries()),null,"missing_handler");
        var entries=new LinkedHashMap<>(base.entries());var result=base.entries().get("result");
        entries.put("request",new ProgramDefinition.Entry(result.graph(),result.writes(),List.of(ProgramDefinition.Send.reply("missing_payload"))));
        assertRejectedMalformed(valid,new ProgramDefinition(base.graph(),base.cells(),base.writes(),List.of(),entries),"request","missing_payload");
    }

    private static void assertRejectedMalformed(com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive valid,
        ProgramDefinition invalid,String activeEntry,String expectedLocation)throws Exception {
        var invalidArchive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(invalid,valid.modules());
        var workspace=new ProgramWorkspace(ProgramDocument.of(invalidArchive));
        var draft=activeEntry==null?workspace.root():workspace.entry(activeEntry);
        var screen=screen(320,activeEntry,workspace::entryNames,workspace,draft);
        var malformed=draft.sends().getFirst();binding(screen,0).onClick(0,0);assertSendFields(screen,malformed);
        set(screen,"width",426);screen.init();assertSendFields(screen,malformed);click(screen,"back");
        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var received=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
        var receivedSends=activeEntry==null?received.archive().program().sends():received.archive().program().entries().get(activeEntry).sends();
        assertEquals(List.of(malformed),receivedSends);
        var host=new ComputerBlockHost(()->{});host.install(valid,ComputerRuntime.MemoryPolicy.RESET,EventEntryFixtures.LIMITS,true);
        var before=host.snapshot().orElseThrow();
        var error=assertThrows(ComputationException.class,()->host.install(received.archive(),ComputerRuntime.MemoryPolicy.RESET,EventEntryFixtures.LIMITS,true));
        assertEquals(ComputationException.Code.MISSING_REFERENCE,error.code());assertEquals(expectedLocation,error.location());
        var after=host.snapshot().orElseThrow();
        assertEquals(before.program(),after.program());assertEquals(before.machine(),after.machine());assertEquals(before.pending(),after.pending());
        assertEquals(before.rpc(),after.rpc());assertEquals(before.mailbox(),after.mailbox());
        var diagnostic=after.installationDiagnostic().orElseThrow();
        assertEquals(ComputationException.Code.MISSING_REFERENCE,diagnostic.code());assertEquals(expectedLocation,diagnostic.location());
    }

    public static ProgramDocument senderDocumentForIntegration(String endpoint,DataValue.RecordValue payloadValue)throws Exception{
        bootstrap();Object previous=field(Minecraft.class,"instance").get(null);try{var client=allocate(Minecraft.class);set(client,"font",new ComputerProgramScreenTest.Metrics());field(Minecraft.class,"instance").set(null,client);var screen=screen(426);var draft=(ProgramDraft)get(screen,"draft");draft.configure(draft.outputs().get("endpoint"),new ComputationGraph.Constant(new DataValue.TextValue(endpoint)));draft.configure(draft.outputs().get("payload"),new ComputationGraph.Constant(payloadValue));fields(screen,"endpoint","payload");click(screen,"create");var document=new ProgramDocument(new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(draft.definition(),Map.of()),Map.of());var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);return ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,limits),limits);}finally{field(Minecraft.class,"instance").set(null,previous);}
    }

    /** Builds the physical transport client's archive through the real widget/draft/workspace/wire route. */
    public static ProgramDocument requesterDocumentForIntegration()throws Exception{
        bootstrap();Object previous=field(Minecraft.class,"instance").get(null);
        try{
            var client=allocate(Minecraft.class);set(client,"font",new ComputerProgramScreenTest.Metrics());field(Minecraft.class,"instance").set(null,client);
            var fixture=RequestReplyFixtures.client();var definition=fixture.program();
            var withoutSend=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
                new ProgramDefinition(definition.graph(),definition.cells(),definition.writes(),List.of(),definition.entries()),fixture.modules());
            var workspace=new ProgramWorkspace(ProgramDocument.of(withoutSend));var draft=workspace.root();
            var screen=screen(426,null,workspace::entryNames,workspace,draft);
            click(screen,"mode_request");fields(screen,"target","payload");click(screen,"request_options");rpcFields(screen,"lookup","result");click(screen,"create");
            var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
            return ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
        }finally{field(Minecraft.class,"instance").set(null,previous);}
    }

    private static ComputerSendScreen screen(int width)throws Exception{return screen(width,"tick",List.of("tick"));}
    private static ComputerSendScreen screen(int width,String activeEntry,List<String> entryNames)throws Exception{
        var workspace=new ProgramWorkspace();var d=workspace.root();
        String endpoint=d.add(new ComputationGraph.Constant(new DataValue.TextValue("factory:a")),0,0),other=d.add(new ComputationGraph.Constant(new DataValue.TextValue("factory:b")),20,0),payload=d.add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of())),40,0),otherPayload=d.add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(1)))),60,0);
        d.output("endpoint",endpoint);d.output("other_endpoint",other);d.output("payload",payload);d.output("other_payload",otherPayload);
        return screen(width,activeEntry,()->List.copyOf(entryNames),workspace,d);
    }
    private static ComputerSendScreen screen(int width,String activeEntry,java.util.function.Supplier<List<String>> names,ProgramWorkspace workspace,ProgramDraft d)throws Exception{var s=allocate(ComputerSendScreen.class);var m=allocate(ComputerProgramMenu.class);set(s,"menu",m);set(s,"draft",d);set(s,"changed",(Runnable)()->{});set(s,"close",(Runnable)()->{});set(s,"activeEntry",activeEntry);set(s,"entryNames",names);set(s,"selected",-1);set(s,"targetText","");set(s,"payloadText","");set(s,"slotText","");set(s,"replyEntryText","");set(s,"captureOutputText","");set(s,"captureInputText","");set(s,"conditionText","");set(s,"dispatch",ProgramDefinition.Dispatch.MESSAGE);set(s,"title",Component.literal("Sends"));set(s,"playerInventoryTitle",Component.empty());set(s,"children",new ArrayList<>());set(s,"renderables",new ArrayList<>());set(s,"narratables",new ArrayList<>());set(s,"width",width);set(s,"height",240);set(s,"minecraft",Minecraft.getInstance());set(s,"font",Minecraft.getInstance().font);s.init();return s;}
    private static void fields(ComputerSendScreen screen,String target,String payload)throws Exception {
        ((EditBox)get(screen,"target")).setValue(target);
        ((EditBox)get(screen,"payload")).setValue(payload);
    }
    private static void rpcFields(ComputerSendScreen screen,String slot,String reply)throws Exception {
        ((EditBox)get(screen,"slot")).setValue(slot);
        ((EditBox)get(screen,"replyEntry")).setValue(reply);
    }
    private static void assertSendFields(ComputerSendScreen screen,ProgramDefinition.Send expected)throws Exception{
        assertEquals(expected.dispatch(),get(screen,"dispatch"));assertEquals(expected.endpointOutput(),get(screen,"targetText"));assertEquals(expected.payloadOutput(),get(screen,"payloadText"));assertEquals(expected.requestSlot(),get(screen,"slotText"));assertEquals(expected.replyEntry(),get(screen,"replyEntryText"));
    }
    private static CreateTextButton binding(ComputerSendScreen s,int index){
        for(int attempts=0;attempts<100;attempts++){
            var found=s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->{
                if(!(b.getMessage().getContents() instanceof TranslatableContents t))return false;
                String key=t.getKey();
                return (key.equals("create_biotech.biocomputing.send.binding")
                    ||key.equals("create_biotech.biocomputing.send.binding_request")
                    ||key.equals("create_biotech.biocomputing.send.binding_reply"))
                    &&t.getArgs().length>0&&Objects.equals(t.getArgs()[0],index+1);
            }).findFirst();
            if(found.isPresent())return found.get();
            int current=(int)getUnchecked(s,"page"),rows=(int)invokeUnchecked(s,"rows");click(s,index<current*rows?"previous":"next");
        }
        throw new NoSuchElementException("binding "+index);
    }
    private static void click(ComputerSendScreen screen,String key) {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button->button.getMessage().getContents() instanceof TranslatableContents text
                &&text.getKey().equals("create_biotech.biocomputing.send."+key))
            .findFirst().orElseThrow().onClick(0,0);
    }
    private static boolean hasButton(ComputerSendScreen screen,String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .anyMatch(button->button.getMessage().getContents() instanceof TranslatableContents text
                &&text.getKey().equals("create_biotech.biocomputing.send."+key));
    }
    private static void assertLayout(ComputerSendScreen screen)throws Exception{var widgets=screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance).map(net.minecraft.client.gui.components.AbstractWidget.class::cast).toList();for(var b:widgets){assertTrue(b.getX()>=getInt(screen,"leftPos")&&b.getY()>=getInt(screen,"topPos"));assertTrue(b.getX()+b.getWidth()<=getInt(screen,"leftPos")+getInt(screen,"imageWidth"));assertTrue(b.getY()+b.getHeight()<=getInt(screen,"topPos")+getInt(screen,"imageHeight"));}for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY(),a.getMessage().getString()+" overlaps "+b.getMessage().getString());}}
    private static Object getUnchecked(Object target,String name) {
        try{return get(target,name);}catch(Exception error){throw new AssertionError(error);}
    }
    private static Object invokeUnchecked(Object target,String name) {
        try {
            var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);
        } catch(Exception error) {throw new AssertionError(error);}
    }
    private static int getInt(Object target,String name)throws Exception{return (int)get(target,name);}
    private static Object get(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}
    private static Field field(Class<?> type,String name)throws Exception {
        for(Class<?> current=type;current!=null;current=current.getSuperclass())try {
            var result=current.getDeclaredField(name);result.setAccessible(true);return result;
        } catch(NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    private static<T>T allocate(Class<T> type)throws Exception {
        var unsafe=(sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        return type.cast(unsafe.allocateInstance(type));
    }
}
