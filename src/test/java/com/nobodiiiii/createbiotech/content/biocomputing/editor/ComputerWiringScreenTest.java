package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.*;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.junit.jupiter.api.*;

class ComputerWiringScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    private Dist oldDist; private CaptureListener connection; private Language oldLanguage;
    @BeforeAll static void bootstrapUi() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        client.client(); oldDist = FMLEnvironment.dist; setDist(Dist.CLIENT);
        oldLanguage = Language.getInstance();
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/en_us.json"))).getAsJsonObject();
        Language.inject(new Language() {
            @Override public String getOrDefault(String key,String fallback){return json.has(key)?json.get(key).getAsString():fallback;}
            @Override public boolean has(String key){return json.has(key);}
            @Override public boolean isDefaultRightToLeft(){return false;}
            @Override public FormattedCharSequence getVisualOrder(FormattedText text){return FormattedCharSequence.forward(text.getString(),Style.EMPTY);}
        });
        set(Minecraft.getInstance(), "font", new Metrics());
        connection = allocate(CaptureListener.class); connection.sent = new ArrayList<>();
        var player = allocate(LocalPlayer.class); set(player, "connection", connection); Minecraft.getInstance().player = player;
    }
    @AfterEach void restore() throws Exception { Language.inject(oldLanguage); setDist(oldDist); client.restore(); }
    @Test void pendingConfigurationRefreshCannotExposeAnUnconsumedDedicatedWiringReply() throws Exception {
        var m = clusterMenu(); var nonce = (UUID)get(m, "nonce");
        m.listWiringSources();
        var sources = new ClusterWiringSources(1, new net.minecraft.core.BlockPos(10,20,30), Map.of("io",true));
        m.accept(new ClusterControlResponse(1,nonce,1,9,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        assertTrue(m.response().isPresent());
        m.requestRefresh();
        assertTrue(m.response().isEmpty(), "A parent screen must not treat the unconsumed directory response as its new REFRESH response");
        m.accept(new ClusterControlResponse(1,nonce,1,9,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        assertTrue(m.response().isEmpty(), "Late old response cannot end the refresh wait");
        var view = new ClusterControlView(ClusterConfiguration.empty(),List.of(),"ready",0,0);
        m.accept(new ClusterControlResponse(1,nonce,2,10,"ready","",WiringControlTest.LIMITS,ClusterControlWire.encodeView(view,WiringControlTest.LIMITS)));
        assertEquals(10,m.response().orElseThrow().revision()); assertEquals(view,m.view().orElseThrow());
    }
    private static ClusterControllerMenu clusterMenu() throws Exception {
        var m = allocate(ClusterControllerMenu.class); var nonce = UUID.randomUUID();
        set(m,"containerId",1); set(m,"nonce",nonce); set(m,"view",Optional.empty());
        set(m,"response",Optional.of(new ClusterControlResponse(1,nonce,0,7,"ready","",WiringControlTest.LIMITS,new byte[0])));
        return m;
    }
    @Test void sharedWiringWidgetsSendControllerPacketsAndKeepItsSourceSeparateFromMemberPins() throws Exception {
        var s = screen(320); var m = clusterMenu(); var nonce = (UUID)get(m,"nonce");
        m.listWiringSources();
        var sources = new ClusterWiringSources(1,new net.minecraft.core.BlockPos(10,20,30),Map.of("io§c",true,"removed",false));
        m.accept(new ClusterControlResponse(1,nonce,1,7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        assertEquals(sources,m.wiringSources().orElseThrow()); m.selectWiringSource("io§c");
        set(s,"menu",m); set(s,"responseVersion",m.wiringResponseVersion()); set(s,"requested",false); s.init();
        connection.sent.clear(); s.containerTick();
        var read = (ClusterControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ClusterControlSession.Action.READ_WIRING,read.action());
        assertEquals(new DataValue.RecordValue(Map.of("endpoint",new DataValue.TextValue("io§c"))),ProgramEditorWire.decodeValue(read.value(),WiringControlTest.LIMITS));
        m.accept(new ClusterControlResponse(1,nonce,read.intent(),7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(snapshot(read.intent()),WiringControlTest.LIMITS)));
        s.containerTick();
        assertTrue(String.join("",(List<String>)get(s,"lines")).contains("\\u00a7c"));
        assertTrue(m.wiringSources().isEmpty(),"Pin reply must not masquerade as an alias directory reply");
        ((EditBox)get(s,"name")).setValue("from_gateway"); ((CreateTextButton)get(s,"save")).onClick(0,0);
        var add = (ClusterControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ClusterControlSession.Action.ADD_WIRING,add.action());
        assertEquals(new WiringControlData.Add(read.intent(),"from_gateway","x0",0),WiringControlData.Add.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(add.value(),WiringControlTest.LIMITS)));
        assertFalse(((CreateTextButton)get(s,"save")).active);
        m.accept(new ClusterControlResponse(1,nonce,add.intent(),7,"ok","",WiringControlTest.LIMITS,
            ProgramEditorWire.encodeValue(snapshotWithEntry(add.intent(),false),WiringControlTest.LIMITS)));
        s.containerTick(); ((CreateTextButton)get(s,"entry")).onClick(0,0); ((CreateTextButton)get(s,"toggle")).onClick(0,0);
        var enable = (ClusterControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ClusterControlSession.Action.SET_WIRING_ENABLED,enable.action());
        assertEquals(new WiringControlData.SetEnabled(add.intent(),"saved",true),WiringControlData.SetEnabled.decode(
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(enable.value(),WiringControlTest.LIMITS)));
        assertFalse(((CreateTextButton)get(s,"toggle")).active);
        m.leaveWiring();
        var leave = (ClusterControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ClusterControlSession.Action.LIST_WIRING,leave.action()); assertEquals(0,leave.value().length);
        m.accept(new ClusterControlResponse(1,nonce,add.intent(),7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(snapshot(add.intent()),WiringControlTest.LIMITS)));
        assertTrue(m.wiringResponse().isEmpty()); assertTrue(m.response().isEmpty());
    }
    @Test void configuringUsesTheDraftRevisionNotTheNewerDedicatedReplyRevision() throws Exception {
        var m = clusterMenu(); var nonce = (UUID)get(m,"nonce");
        m.listWiringSources();
        m.accept(new ClusterControlResponse(1,nonce,1,9,"stale","",WiringControlTest.LIMITS,new byte[0]));
        m.configure(ClusterConfiguration.empty(),7);
        var request=(ClusterControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ClusterControlSession.Action.CONFIGURE,request.action()); assertEquals(7,request.revision());
    }
    @Test void malformedOrOldScopeDirectoryCannotRestoreAnAliasSelection() throws Exception {
        var m=clusterMenu(); var nonce=(UUID)get(m,"nonce"); m.listWiringSources();
        var sources=new ClusterWiringSources(1,net.minecraft.core.BlockPos.ZERO,Map.of("orphan",false));
        m.accept(new ClusterControlResponse(1,nonce,1,7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        m.listWiringSources();
        m.accept(new ClusterControlResponse(1,nonce,1,7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        assertTrue(m.wiringSources().isEmpty());
        m.accept(new ClusterControlResponse(1,nonce,2,7,"ok","",WiringControlTest.LIMITS,ProgramEditorWire.encodeValue(sources.encode(),WiringControlTest.LIMITS)));
        assertTrue(m.wiringSources().isEmpty(),"Payload view must also match its current response intent");
        assertThrows(IllegalArgumentException.class,()->m.selectWiringSource("orphan"));
    }
    @Test void malformedOrRejectedRefreshDiscardsChoicesAndOldReplyCannotRestoreThem() throws Exception {
        var s = screen(320); reply(s, snapshot(1), 1, "ok");
        assertTrue(((CreateTextButton)get(s, "save")).active);
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)snapshot(2).field("wiring")).fields());
        fields.put("local_pins", new DataValue.ListValue(List.of(new DataValue.TextValue("x0"), new DataValue.TextValue("x0"))));
        reply(s, new DataValue.RecordValue(Map.of("wiring", new DataValue.RecordValue(fields))), 2, "ok");
        assertNull(get(s,"snapshot")); assertFalse(((CreateTextButton)get(s,"save")).active);
        reply(s, snapshot(3), 3, "ok"); ((CreateTextButton)get(s,"refresh")).onClick(0,0);
        var menu=(ComputerProgramMenu)get(s,"menu");
        var request=(ProgramControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ComputerControlSession.Action.READ_WIRING,request.action()); assertEquals(0,request.inputs().length);
        menu.accept(new ProgramStatusResponse(1,(UUID)get(menu,"nonce"),3,1,"ok","",ProgramEditorWire.encodeValue(snapshot(3),WiringControlTest.LIMITS)));
        s.containerTick(); assertNull(get(s,"snapshot")); assertTrue((boolean)get(s,"pending"));
        reply(s,snapshot(4),4,"stale"); assertNull(get(s,"snapshot")); assertFalse((boolean)get(s,"pending"));
        assertFalse(((CreateTextButton)get(s,"save")).active); assertTrue(((CreateTextButton)get(s,"refresh")).active);
        assertTrue(menu.endpointStatus().isEmpty()); assertTrue(menu.communicationStatus().isEmpty());
    }
    @Test void removeRequiresSelectingTheStoredEntryAndItsExactPublishedName() throws Exception {
        var s=screen(320); var fields=new LinkedHashMap<>(((DataValue.RecordValue)snapshot(1).field("wiring")).fields());
        var target=((DataValue.ListValue)fields.get("peer_pins")).values().getFirst();
        fields.put("entries",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("name",new DataValue.TextValue("saved"),
            "local_pin",new DataValue.TextValue("x0"),"peer",target,"availability",new DataValue.TextValue("unreachable"))))));
        reply(s,new DataValue.RecordValue(Map.of("wiring",new DataValue.RecordValue(fields))),1,"ok");
        var remove=(CreateTextButton)get(s,"remove"); assertFalse(remove.active);
        ((EditBox)get(s,"name")).setValue("different draft"); ((CreateTextButton)get(s,"entry")).onClick(0,0);
        assertTrue(connection.sent.isEmpty()); assertTrue(remove.active); remove.onClick(0,0);
        var request=(ProgramControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ComputerControlSession.Action.REMOVE_WIRING,request.action());
        assertEquals(new WiringControlData.Remove(1,"saved"),WiringControlData.Remove.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(request.inputs(),WiringControlTest.LIMITS)));
        assertEquals("different draft",((EditBox)get(s,"name")).getValue());
    }
    @Test void explicitEnableAndDisableUseTheSelectedStoredNameAndNeverTheNameDraft() throws Exception {
        var s=screen(320); reply(s,snapshotWithEntry(1,false),1,"ok");
        var toggle=(CreateTextButton)get(s,"toggle"); assertFalse(toggle.active);
        ((EditBox)get(s,"name")).setValue("unrelated draft");
        ((CreateTextButton)get(s,"entry")).onClick(0,0);
        assertTrue(toggle.active); assertTrue(connection.sent.isEmpty()); toggle.onClick(0,0);
        var request=(ProgramControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals("SET_WIRING_ENABLED",request.action().name());
        var data=(DataValue.RecordValue)ProgramEditorWire.decodeValue(request.inputs(),WiringControlTest.LIMITS);
        assertEquals(Set.of("view","name","enabled"),data.fields().keySet());
        assertEquals(new DataValue.IntegerValue(1),data.field("view"));
        assertEquals(new DataValue.TextValue("saved"),data.field("name"));
        assertEquals(new DataValue.BooleanValue(true),data.field("enabled"));
        assertFalse(toggle.active);
        var menu = (ComputerProgramMenu)get(s,"menu");
        menu.accept(new ProgramStatusResponse(1,(UUID)get(menu,"nonce"),request.intent(),1,"ok","",
            ProgramEditorWire.encodeValue(snapshotWithEntry(request.intent(),true),WiringControlTest.LIMITS)));
        s.containerTick();
        assertEquals("Activation choice saved; only enabled wires can rendezvous.", ((Component)get(s,"notice")).getString());
        ((CreateTextButton)get(s,"entry")).onClick(0,0); ((CreateTextButton)get(s,"toggle")).onClick(0,0);
        var stop=(ProgramControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(new DataValue.BooleanValue(false),((DataValue.RecordValue)ProgramEditorWire.decodeValue(stop.inputs(),WiringControlTest.LIMITS)).field("enabled"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "en_us,Explicit wiring; enable to connect running programs,Enabled; rendezvous requires a reachable peer and matching XBus operations.",
        "zh_cn,显式接线；启用后连接运行中的程序,连线已启用；会合仍需对端可达并执行匹配的 XBus 操作。"
    })
    void wiringCopyDescribesNormalSchedulingWithoutPromisingDelivery(String locale, String heading, String enabled) throws Exception {
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/assets/create_biotech/lang/" + locale + ".json"))).getAsJsonObject();
        String prefix = "create_biotech.biocomputing.wiring.";
        assertEquals(heading, json.get(prefix + "mode").getAsString());
        assertEquals(enabled, json.get(prefix + "enabled_config").getAsString());
        for (String key : List.of("mode", "enabled_config", "state_saved", "activation_help")) {
            String copy = json.get(prefix + key).getAsString();
            assertFalse(copy.contains("not connected") || copy.contains("尚未接入") || copy.contains("暂不自动"), key);
        }
        // New declarations still require an explicit enable; reading/configuring never starts a program.
        assertEquals(locale.equals("en_us") ? "Declaration saved; no transfer started." : "声明已保存；未启动传输。",
            json.get(prefix + "saved").getAsString());
    }
    @Test void mutationButtonCaptionsFitWithoutHidingTheActionAtSmallAndLargeWidths() throws Exception {
        for (int width : List.of(320, 640)) for (boolean enabled : List.of(false, true)) {
            var s = screen(width); reply(s, snapshotWithEntry(1, enabled), 1, "ok");
            ((CreateTextButton)get(s, "entry")).onClick(0, 0);
            for (String name : List.of("toggle", "remove")) {
                var button = (CreateTextButton)get(s, name);
                assertTrue(button.active);
                assertTrue(Minecraft.getInstance().font.width(button.getMessage()) <= button.getWidth() - 8,
                    "The full action must fit: " + button.getMessage().getString());
            }
        }
    }
    static DataValue.RecordValue snapshotWithEntry(long view, boolean enabled) {
        var fields=new LinkedHashMap<>(((DataValue.RecordValue)snapshot(view).field("wiring")).fields());
        var target=((DataValue.ListValue)fields.get("peer_pins")).values().getFirst();
        fields.put("entries",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("name",new DataValue.TextValue("saved"),
            "local_pin",new DataValue.TextValue("x0"),"peer",target,"availability",new DataValue.TextValue("ready"),"enabled",new DataValue.BooleanValue(enabled))))));
        return new DataValue.RecordValue(Map.of("wiring",new DataValue.RecordValue(fields)));
    }
    @Test void tooLongNameIsNotSilentlyTruncatedOrSentAndFullEscapedTargetRemainsPaged() throws Exception {
        var s=screen(320); reply(s,snapshot(1),1,"ok"); var box=(EditBox)get(s,"name");
        box.setValue("x".repeat(65)); ((CreateTextButton)get(s,"save")).onClick(0,0);
        assertEquals(65,box.getValue().length()); assertTrue(connection.sent.isEmpty());
        var lines=(List<String>)get(s,"lines");
        assertTrue(String.join("",lines).contains("\\u00a7c"));
        assertTrue(lines.stream().allMatch(line->Minecraft.getInstance().font.width(line)<=292));
        assertFalse(((CreateTextButton)get(s,"peer")).getToolTip().isEmpty());
        while(((CreateTextButton)get(s,"next")).active)((CreateTextButton)get(s,"next")).onClick(0,0);
        assertTrue((int)get(s,"page")>0); assertTrue(connection.sent.isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void smallScreenWidgetsRemainInsideAndNonOverlapping(int width) throws Exception {
        var s=screen(width); var widgets=s.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        for(var a:widgets)assertTrue(a.getX()>=0&&a.getY()>=0&&a.getX()+a.getWidth()<=width&&a.getY()+a.getHeight()<=240);
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);
            assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY());}
    }
    @Test void explicitSaveSendsOnlyPublishedSelectionAndResizeNeitherSendsNorLosesDraft() throws Exception {
        var s = screen(320); var menu = (ComputerProgramMenu)get(s, "menu");
        reply(s, snapshot(1), 1, "ok");
        var name = (EditBox)get(s, "name"); name.setValue("wire§literal"); s.setFocused(name);
        ((CreateTextButton)get(s, "local")).onClick(0, 0);
        ((CreateTextButton)get(s, "peer")).onClick(0, 0);
        assertTrue(connection.sent.isEmpty());
        set(s, "width", 426); s.init();
        assertEquals("wire§literal", ((EditBox)get(s, "name")).getValue()); assertTrue(connection.sent.isEmpty());
        ((CreateTextButton)get(s, "save")).onClick(0, 0);
        var request = (ProgramControlRequest)((ServerboundCustomPayloadPacket)connection.sent.getLast()).payload();
        assertEquals(ComputerControlSession.Action.ADD_WIRING, request.action());
        assertEquals(new WiringControlData.Add(1, "wire§literal", "x1", 1), WiringControlData.Add.decode(
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(request.inputs(), WiringControlTest.LIMITS)));
        assertFalse(((CreateTextButton)get(s, "save")).active);
        assertEquals(new DataValue.RecordValue(Map.of("keep", new DataValue.IntegerValue(9))), menu.debuggerInputs());
    }
    static DataValue.RecordValue snapshot(long view) {
        var pin = new DataValue.RecordValue(Map.of("endpoint", new DataValue.TextValue("peer§c" + "x".repeat(55)), "domain", new DataValue.TextValue("primary"),
            "pin", new DataValue.TextValue("x0"), "position", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(1), "y", new DataValue.IntegerValue(2), "z", new DataValue.IntegerValue(3)))));
        return new DataValue.RecordValue(Map.of("wiring", new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view),
            "revision", new DataValue.IntegerValue(0), "mode", new DataValue.TextValue("declarations_only"), "discovery", new DataValue.TextValue("ready"),
            "local_pins", new DataValue.ListValue(List.of(new DataValue.TextValue("x0"), new DataValue.TextValue("x1"))),
            "peer_pins", new DataValue.ListValue(List.of(pin, pin)), "entries", new DataValue.ListValue(List.of())))));
    }
    static ComputerWiringScreen screen(int width) throws Exception {
        var s = allocate(ComputerWiringScreen.class); var m = allocate(ComputerProgramMenu.class); var nonce = UUID.randomUUID();
        set(m,"containerId",1); set(m,"nonce",nonce); set(m,"controlResponse",Optional.empty()); set(m,"status",Optional.empty());
        set(m,"debuggerInputs",new DataValue.RecordValue(Map.of("keep",new DataValue.IntegerValue(9))));
        set(m,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",new ResourceLimits(1000,100000,100),WiringControlTest.LIMITS,new byte[0])));
        set(s,"menu",m); set(s,"nameText",""); set(s,"notice",Component.empty()); set(s,"lines",List.of()); set(s,"requested",true);
        set(s,"title",Component.literal("Wiring")); set(s,"playerInventoryTitle",Component.empty()); set(s,"children",new ArrayList<>());
        set(s,"renderables",new ArrayList<>()); set(s,"narratables",new ArrayList<>()); set(s,"width",width); set(s,"height",240);
        set(s,"minecraft",Minecraft.getInstance()); set(s,"font",Minecraft.getInstance().font); s.init(); return s;
    }
    static void reply(ComputerWiringScreen s, DataValue.RecordValue value, long intent, String code) throws Exception {
        var m = (ComputerProgramMenu)get(s,"menu"); set(m,"latestControlIntent",intent); set(m,"latestControlAction",ComputerControlSession.Action.READ_WIRING);
        m.accept(new ProgramStatusResponse(1,(UUID)get(m,"nonce"),intent,1,code,"",ProgramEditorWire.encodeValue(value,WiringControlTest.LIMITS)));
        s.containerTick();
    }
    @SuppressWarnings("removal") private static void setDist(Dist value) throws Exception {
        var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); var unsafe=(sun.misc.Unsafe)f.get(null);
        var field=FMLEnvironment.class.getDeclaredField("dist"); unsafe.putObject(unsafe.staticFieldBase(field),unsafe.staticFieldOffset(field),value);
    }
    private static final class CaptureListener extends ClientPacketListener {
        List<Packet<?>> sent; private CaptureListener(){super(null,null,null);}
        @Override public void send(Packet<?> packet){sent.add(packet);}
    }
    private static final class Metrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor {
        Metrics(){super(id->null,false);}
        public java.util.function.Function<ResourceLocation,FontSet> create$getFonts(){return id->null;}
        @Override public int width(String value){return value.length()*6;}
        @Override public int width(FormattedText value){return width(value.getString());}
        @Override public String plainSubstrByWidth(String value,int width){return value.substring(0,Math.min(value.length(),Math.max(0,width/6)));}
        @Override public String plainSubstrByWidth(String value,int width,boolean reverse){int count=Math.min(value.length(),Math.max(0,width/6));return reverse?value.substring(value.length()-count):value.substring(0,count);}
    }
}
