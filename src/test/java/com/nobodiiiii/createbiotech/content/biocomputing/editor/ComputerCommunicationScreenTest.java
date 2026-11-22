package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.locale.Language;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;

class ComputerCommunicationScreenTest {
	@Test void physicalSegmentSummaryReflowsWithoutEnablingPortsOrIssuingAnotherDiscovery() throws Exception {
		var s = screen(320); var menu = (ComputerProgramMenu)get(s, "menu");
		var segment = new DataValue.RecordValue(Map.of("source_faces", new DataValue.ListValue(List.of(
			new DataValue.TextValue("east"), new DataValue.TextValue("west"))),
			"cable_blocks", new DataValue.IntegerValue(7), "connected_devices", new DataValue.IntegerValue(2)));
		var directory = new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue("d"),
			"game_time", new DataValue.IntegerValue(42), "peers", new DataValue.RecordValue(Map.of()),
			"cable_segments", new DataValue.ListValue(List.of(segment))));
		int sent = connection.sent.size(); var inputs = menu.debuggerInputs();
		reply(s, ComputerControlSession.Action.DISCOVER_PEERS, directory, 1);
		var lines = (List<String>)get(s, "peerLines");
		assertTrue(String.join("", lines).contains("cables 7, peers 2"));
		assertTrue(String.join("", lines).contains("Topology only"));
		assertTrue(lines.stream().allMatch(line -> Minecraft.getInstance().font.width(line) <= 292));
		set(s, "width", 426); s.init();
		assertTrue(String.join("", (List<String>)get(s, "peerLines")).contains("cables 7, peers 2"));
		assertEquals(sent, connection.sent.size()); assertEquals(inputs, menu.debuggerInputs());
		assertEquals(1L, get(menu, "latestControlIntent"));
	}

	@Test void malformedPhysicalSummaryClearsOldDirectoryInsteadOfDisplayingAFalseChannel() throws Exception {
		for (DataValue invalid : List.of(new DataValue.IntegerValue(1), new DataValue.ListValue(List.of(
			new DataValue.RecordValue(Map.of("source_faces", new DataValue.ListValue(List.of(new DataValue.TextValue("unknown"))),
				"cable_blocks", new DataValue.IntegerValue(1), "connected_devices", new DataValue.IntegerValue(0))))))) {
			var s = screen(320); set(s, "peerLines", List.of("previous"));
			var directory = new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue("d"),
				"game_time", new DataValue.IntegerValue(42), "peers", new DataValue.RecordValue(Map.of()), "cable_segments", invalid));
			reply(s, ComputerControlSession.Action.DISCOVER_PEERS, directory, 1);
			assertTrue(((List<?>)get(s, "peerLines")).isEmpty()); assertNull(get(s, "directorySnapshot"));
		}
	}

	@Test void malformedPinSnapshotClearsPreviousDiscoveryInsteadOfShowingPartialPeers() throws Exception {
		for (DataValue invalid : List.of(new DataValue.IntegerValue(1),
			new DataValue.ListValue(List.of(new DataValue.TextValue("x0"), new DataValue.TextValue("x0"))),
			new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))))) {
			var s = screen(320); var position = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(1),
				"y", new DataValue.IntegerValue(2), "z", new DataValue.IntegerValue(3)));
			set(s, "peerLines", List.of("previous result"));
			var endpoint = new DataValue.RecordValue(Map.of("position", position, "xbus_pins", invalid));
			var directory = new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue("d"), "game_time", new DataValue.IntegerValue(1),
				"peers", new DataValue.RecordValue(Map.of("io", new DataValue.RecordValue(Map.of("ambiguous", new DataValue.BooleanValue(false),
					"endpoints", new DataValue.ListValue(List.of(endpoint))))))));
			reply(s, ComputerControlSession.Action.DISCOVER_PEERS, directory, 1);
			assertTrue(((List<?>)get(s, "peerLines")).isEmpty()); assertNull(get(s, "directorySnapshot"));
		}
	}
	@Test void discoveredHardwarePinsRemainLiteralAndBrowsableWithoutIssuingWireOrExecutionActions() throws Exception {
		var s = screen(320); var menu = (ComputerProgramMenu)get(s, "menu");
		String pin = "§c" + "x".repeat(58) + "TAIL";
		var endpoint = new DataValue.RecordValue(Map.of("position", new DataValue.RecordValue(Map.of(
			"x", new DataValue.IntegerValue(1), "y", new DataValue.IntegerValue(2), "z", new DataValue.IntegerValue(3))),
			"xbus_pins", new DataValue.ListValue(List.of(new DataValue.TextValue("x0"), new DataValue.TextValue(pin)))));
		var directory = new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue("d"), "game_time", new DataValue.IntegerValue(1),
			"peers", new DataValue.RecordValue(Map.of("io", new DataValue.RecordValue(Map.of("ambiguous", new DataValue.BooleanValue(false),
				"endpoints", new DataValue.ListValue(List.of(endpoint))))))));
		int sent = connection.sent.size(); var before = menu.debuggerInputs();
		reply(s, ComputerControlSession.Action.DISCOVER_PEERS, directory, 1);
		var lines = (List<String>)get(s, "peerLines");
		assertTrue(String.join("", lines).contains(BrowserLiteralDisplay.quote(pin)));
		assertTrue(String.join("", lines).contains("Not a wire"));
		assertTrue(lines.stream().allMatch(line -> Minecraft.getInstance().font.width(line) <= 292));
		assertEquals(sent, connection.sent.size()); assertEquals(before, menu.debuggerInputs());
		set(s, "width", 426); s.init();
		assertTrue(String.join("", (List<String>)get(s, "peerLines")).contains(BrowserLiteralDisplay.quote(pin)));
		assertEquals(sent, connection.sent.size()); assertEquals(1L, get(menu, "latestControlIntent"));
	}
	@Test void messageObservationUsesDedicatedActionAndPreservesFocusedNameAcrossResize() throws Exception {
		var screen = screen(426); var menu = (ComputerProgramMenu)get(screen, "menu");
		var name = (EditBox)get(screen, "name"); name.setValue("unsaved"); screen.setFocused(name);
		button(screen, "messages").onClick(0, 0);
		assertEquals(ComputerControlSession.Action.READ_COMMUNICATION, get(menu, "latestControlAction"));
		assertInstanceOf(ServerboundCustomPayloadPacket.class, connection.sent.getLast());
		String target = "§c" + "x".repeat(58) + "TAIL";
		var limits = CommunicationStatusViewTest.LIMITS;
		var runtime = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime(UUID.randomUUID(),
			CommunicationStatusViewTest.sender(target, new DataValue.IntegerValue(7)), limits.hardware());
		runtime.begin(new DataValue.RecordValue(Map.of())); runtime.tick();
		var value = CommunicationStatusView.of(Optional.of(runtime.snapshot()), limits, true, Optional.empty(), Optional.empty());
		reply(screen, ComputerControlSession.Action.READ_COMMUNICATION, value, 1);
		assertEquals(Optional.of(value), menu.communicationStatus()); assertTrue(menu.peerDirectory().isEmpty());
		assertTrue(menu.endpointStatus().isEmpty()); assertSame(name, screen.getFocused()); assertEquals("unsaved", name.getValue());
		var lines = (List<String>)get(screen, "peerLines");
		assertTrue(String.join("", lines).contains(BrowserLiteralDisplay.quote(target)));
		long intent = (long)get(menu, "latestControlIntent");
		set(screen, "width", 320); screen.init();
		lines = (List<String>)get(screen, "peerLines");
		assertTrue(lines.stream().allMatch(line -> Minecraft.getInstance().font.width(line) <= 292));
		assertTrue(String.join("", lines).contains(BrowserLiteralDisplay.quote(target)));
		assertEquals(intent, get(menu, "latestControlIntent")); assertEquals("unsaved", ((EditBox)get(screen, "name")).getValue());
		assertEquals(new DataValue.RecordValue(Map.of("keep", new DataValue.IntegerValue(1))), menu.debuggerInputs());
		set(menu, "latestControlAction", ComputerControlSession.Action.READ_ENDPOINT);
		assertTrue(menu.communicationStatus().isEmpty());
	}
	@Test void rejectedMessageRefreshClearsPriorCommunicationObservation() throws Exception {
		var screen = screen(320); var menu = (ComputerProgramMenu)get(screen, "menu");
		var value = CommunicationStatusView.of(Optional.empty(), CommunicationStatusViewTest.LIMITS, false, Optional.empty(), Optional.empty());
		reply(screen, ComputerControlSession.Action.READ_COMMUNICATION, value, 1);
		assertFalse(((List<?>)get(screen, "peerLines")).isEmpty());
		button(screen, "messages").onClick(0, 0);
		menu.accept(new ProgramStatusResponse(1, (UUID)get(menu, "nonce"), (long)get(menu, "latestControlIntent"), 1, "stale", "", new byte[0]));
		screen.containerTick(); assertTrue(menu.communicationStatus().isEmpty()); assertTrue(((List<?>)get(screen, "peerLines")).isEmpty());
	}
	private final ComputerProgramScreenTest client=new ComputerProgramScreenTest();
	private static final ArchiveLimits LIMITS=new ArchiveLimits(10000,100000,1000000);
	@BeforeAll static void bootstrapUi()throws Exception{ComputerProgramScreenTest.bootstrap();}
	private Dist oldDist; private Language oldLanguage;
	private LocalPlayer oldPlayer;
	private CaptureListener connection;
	@BeforeEach void client()throws Exception{
		client.client(); oldLanguage=Language.getInstance(); Language.inject(new TestLanguage()); set(Minecraft.getInstance(),"font",new WideMetrics()); oldDist=FMLEnvironment.dist; setDist(Dist.CLIENT); oldPlayer=Minecraft.getInstance().player;
		var listener=allocate(CaptureListener.class); listener.sent=new ArrayList<>(); connection=listener;
		var player=allocate(LocalPlayer.class); set(player,"connection",listener); Minecraft.getInstance().player=player;
	}
	@AfterEach void restore()throws Exception{
		Minecraft.getInstance().player=oldPlayer; setDist(oldDist); Language.inject(oldLanguage); client.restore();
	}
	@SuppressWarnings("removal") private static void setDist(Dist value)throws Exception{
		var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
		var unsafe=(sun.misc.Unsafe)unsafeField.get(null); var field=FMLEnvironment.class.getDeclaredField("dist");
		unsafe.putObject(unsafe.staticFieldBase(field),unsafe.staticFieldOffset(field),value);
	}
	private static final class CaptureListener extends ClientPacketListener {
		List<Packet<?>> sent;
		private CaptureListener(){super(null,null,null);}
		@Override public void send(Packet<?> packet){sent.add(packet);}
	}
	private static final class TestLanguage extends Language {
		private static final Map<String,String> TEXT=Map.of(
			"create_biotech.biocomputing.communication.segments.header","Physical segments",
			"create_biotech.biocomputing.communication.segments.entry","Segment %s, faces %s, cables %s, peers %s",
			"create_biotech.biocomputing.communication.segments.note","Topology only; no port enabled",
			"create_biotech.biocomputing.communication.snapshot","Snapshot %s at %s",
			"create_biotech.biocomputing.communication.peer","Peer %s",
			"create_biotech.biocomputing.communication.peer_ambiguous","Peer %s ambiguous",
			"create_biotech.biocomputing.communication.xbus_pin","XBus pin %s",
			"create_biotech.biocomputing.communication.xbus_discovery_only","Not a wire; explicit connection required",
			"create_biotech.biocomputing.communication.position","Position %s, %s, %s");
		@Override public String getOrDefault(String key,String fallback){return TEXT.getOrDefault(key,fallback);}
		@Override public boolean has(String key){return TEXT.containsKey(key);}
		@Override public boolean isDefaultRightToLeft(){return false;}
		@Override public FormattedCharSequence getVisualOrder(FormattedText text){return FormattedCharSequence.forward(text.getString(),Style.EMPTY);}
	}
	private static final class WideMetrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor {
		WideMetrics(){super(id->null,false);}
		@Override public java.util.function.Function<ResourceLocation,FontSet> create$getFonts(){return id->null;}
		@Override public int width(String value){return value.length()*6;}
		@Override public int width(FormattedText value){return value.getString().length()*6;}
		@Override public String plainSubstrByWidth(String value,int width){return value.substring(0,Math.min(value.length(),Math.max(0,width/6)));}
		@Override public String plainSubstrByWidth(String value,int width,boolean reverse){int count=Math.min(value.length(),Math.max(0,width/6));return reverse?value.substring(value.length()-count):value.substring(0,count);}
	}

	@Test void nameAllows64AndReports65WithoutTruncatingOrSending()throws Exception{
		var s=screen(320);var menu=(ComputerProgramMenu)get(s,"menu");var box=(EditBox)get(s,"name");
		box.setValue("x".repeat(65));assertEquals(65,box.getValue().length());button(s,"save_enable").onClick(0,0);
		assertEquals("x".repeat(65),box.getValue());assertEquals(0L,get(menu,"latestControlIntent"));
		box.setValue("x".repeat(64));assertDoesNotThrow(()->button(s,"save_enable").onClick(0,0));
		assertEquals(ComputerControlSession.Action.CONFIGURE_ENDPOINT,get(menu,"latestControlAction"));assertInstanceOf(ServerboundCustomPayloadPacket.class,connection.sent.getLast());
	}
	@Test void longEscapedPeerNameRemainsCompletelyBrowsableAcrossPages()throws Exception{
		var s=screen(320);String peer="§c"+"x".repeat(58)+"TAIL";
		var value=new DataValue.RecordValue(Map.of("dimension",new DataValue.TextValue("d"),"game_time",new DataValue.IntegerValue(1),"peers",new DataValue.RecordValue(Map.of(peer,new DataValue.RecordValue(Map.of("ambiguous",new DataValue.BooleanValue(false),"endpoints",new DataValue.ListValue(List.of())))))));
		reply(s,ComputerControlSession.Action.DISCOVER_PEERS,value,1);
		var lines=(List<String>)get(s,"peerLines");int available=(int)get(s,"imageWidth")-16;
		assertTrue(lines.stream().allMatch(line->Minecraft.getInstance().font.width(line)<=available));
		assertTrue(String.join("",lines).contains(BrowserLiteralDisplay.quote(peer)));
		while(((CreateTextButton)get(s,"next")).active)((CreateTextButton)get(s,"next")).onClick(0,0);
		assertTrue(((int)get(s,"page"))>0);assertTrue(String.join("",lines).contains("TAIL"));
	}
	@Test void resizeRewrapsTheSameSnapshotWithoutAnotherRequestOrLosingNameSuffix() throws Exception {
		var screen = screen(426);
		String peer = "§c" + "x".repeat(58) + "TAIL";
		var entry = new DataValue.RecordValue(Map.of("ambiguous", new DataValue.BooleanValue(false),
			"endpoints", new DataValue.ListValue(List.of())));
		var directory = new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue("d"),
			"game_time", new DataValue.IntegerValue(1), "peers", new DataValue.RecordValue(Map.of(peer, entry))));
		reply(screen, ComputerControlSession.Action.DISCOVER_PEERS, directory, 1);
		var menu = (ComputerProgramMenu)get(screen, "menu");
		((EditBox)get(screen, "name")).setValue("unsaved local name");
		long intent = (long)get(menu, "latestControlIntent");
		set(screen, "width", 320); screen.init();
		var lines = (List<String>)get(screen, "peerLines");
		int available = (int)get(screen, "imageWidth") - 16;
		assertTrue(lines.stream().allMatch(line -> Minecraft.getInstance().font.width(line) <= available));
		assertTrue(String.join("", lines).contains(BrowserLiteralDisplay.quote(peer)));
		assertEquals(intent, get(menu, "latestControlIntent"));
		assertEquals("unsaved local name", ((EditBox)get(screen, "name")).getValue());
	}
	@Test void statusReplySeedsUntouchedNameOnceAndNeverRebuildsFocusedEditor()throws Exception{
		var s=screen(426);var menu=(ComputerProgramMenu)get(s,"menu");var box=(EditBox)get(s,"name");s.setFocused(box);
		reply(s,ComputerControlSession.Action.READ_ENDPOINT,new DataValue.RecordValue(Map.of("endpoint",
			new DataValue.RecordValue(Map.of("name",new DataValue.TextValue("saved"),"enabled",new DataValue.BooleanValue(true))),
			"endpoint_diagnostic",DataValue.NullValue.INSTANCE)),1);
		assertSame(box,s.getFocused());assertSame(box,get(s,"name"));assertEquals("saved",box.getValue());
		box.setValue("editing");reply(s,ComputerControlSession.Action.READ_ENDPOINT,new DataValue.RecordValue(Map.of("endpoint",DataValue.NullValue.INSTANCE,"endpoint_diagnostic",DataValue.NullValue.INSTANCE)),2);
		assertEquals("editing",box.getValue());assertSame(box,s.getFocused());
	}
	@Test void peerReplyUsesSafeLiteralNamesShowsAmbiguityAndPagesWithoutTouchingName()throws Exception{
		var s=screen(320);var box=(EditBox)get(s,"name");box.setValue("local edit");s.setFocused(box);
		var entries=new LinkedHashMap<String,DataValue>();
		for(int i=0;i<30;i++)entries.put("peer§c\t"+i,new DataValue.RecordValue(Map.of("ambiguous",new DataValue.BooleanValue(i==0),"endpoints",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("position",new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(i),"y",new DataValue.IntegerValue(2),"z",new DataValue.IntegerValue(3))))))))));
		reply(s,ComputerControlSession.Action.DISCOVER_PEERS,new DataValue.RecordValue(Map.of("dimension",new DataValue.TextValue("minecraft:overworld"),"game_time",new DataValue.IntegerValue(9),"peers",new DataValue.RecordValue(entries))),1);
		var lines=(List<String>)get(s,"peerLines");assertTrue(lines.stream().anyMatch(v->v.contains("\\u00a7c\\t")&&v.contains("ambiguous")));
		assertTrue(((CreateTextButton)get(s,"next")).active);int first=(int)get(s,"page");((CreateTextButton)get(s,"next")).onClick(0,0);assertEquals(first+1,get(s,"page"));((CreateTextButton)get(s,"previous")).onClick(0,0);assertEquals(first,get(s,"page"));
		assertSame(box,s.getFocused());assertEquals("local edit",box.getValue());assertSame(lines,get(s,"peerLines"));
	}
	@Test void noPayloadButtonsSendOnlyTheirActionAndNeverTouchDebuggerInputs()throws Exception{
		var expected=Map.of("disable",ComputerControlSession.Action.DISABLE_ENDPOINT,"clear",ComputerControlSession.Action.CLEAR_ENDPOINT,"read",ComputerControlSession.Action.READ_ENDPOINT,"discover",ComputerControlSession.Action.DISCOVER_PEERS);
		for(var entry:expected.entrySet()){
			var s=screen(320);var menu=(ComputerProgramMenu)get(s,"menu");var before=menu.debuggerInputs();int sent=connection.sent.size();
			assertDoesNotThrow(()->button(s,entry.getKey()).onClick(0,0));assertEquals(entry.getValue(),get(menu,"latestControlAction"));
			assertEquals(before,menu.debuggerInputs());assertEquals(sent+1,connection.sent.size());assertInstanceOf(ServerboundCustomPayloadPacket.class,connection.sent.getLast());
		}
	}
	@ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426})
	void compactLayoutKeepsAllWidgetsInsideAndNonOverlapping(int width)throws Exception{
		var s=screen(width);var widgets=s.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
		for(var w:widgets){assertTrue(w.getX()>=0&&w.getY()>=0&&w.getX()+w.getWidth()<=width&&w.getY()+w.getHeight()<=240);}
		for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY());}
	}
	private static ComputerCommunicationScreen screen(int width)throws Exception{var s=allocate(ComputerCommunicationScreen.class);var m=allocate(ComputerProgramMenu.class);var nonce=UUID.randomUUID();set(m,"containerId",1);set(m,"nonce",nonce);set(m,"controlResponse",Optional.empty());set(m,"status",Optional.empty());set(m,"debuggerInputs",new DataValue.RecordValue(Map.of("keep",new DataValue.IntegerValue(1))));set(m,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",new ResourceLimits(1000,100000,100),LIMITS,new byte[0])));set(s,"menu",m);set(s,"nameText","");set(s,"notice",Component.empty());set(s,"peerLines",List.of());set(s,"requested",true);set(s,"title",Component.literal("Communication"));set(s,"playerInventoryTitle",Component.empty());set(s,"children",new ArrayList<>());set(s,"renderables",new ArrayList<>());set(s,"narratables",new ArrayList<>());set(s,"width",width);set(s,"height",240);set(s,"minecraft",Minecraft.getInstance());set(s,"font",Minecraft.getInstance().font);s.init();return s;}
	private static void reply(ComputerCommunicationScreen s,ComputerControlSession.Action action,DataValue.RecordValue value,long intent)throws Exception{var m=(ComputerProgramMenu)get(s,"menu");set(m,"latestControlIntent",intent);set(m,"latestControlAction",action);m.accept(new ProgramStatusResponse(1,(UUID)get(m,"nonce"),intent,1,"ok","",ProgramEditorWire.encodeValue(value,LIMITS)));s.containerTick();}
	private static CreateTextButton button(ComputerCommunicationScreen s,String key){return s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getContents()instanceof TranslatableContents t&&t.getKey().equals("create_biotech.biocomputing.communication."+key)).findFirst().orElseThrow();}
}
