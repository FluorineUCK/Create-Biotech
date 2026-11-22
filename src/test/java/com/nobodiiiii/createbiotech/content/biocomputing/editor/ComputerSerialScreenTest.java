package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerSerialScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @BeforeAll static void bootstrapUi() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { client.client(); }
    @AfterEach void restore() throws Exception { client.restore(); }
    @Test void gatewayMetadataLocksFaceWhileKeepingExplicitRemovalAndDisabledPortsVisible() throws Exception {
        var screen = screen(320);
        var binding = new SerialGatewayBinding(UUID.randomUUID(), new net.minecraft.core.BlockPos(2, 3, 4), Direction.EAST, "sensor");
        var view = new SerialDevice.View(Optional.empty(), Map.of("s0", new SerialDevice.PortView(Optional.of(Direction.EAST), false,
            new SerialDevice.Status("idle", "closed", "", new SerialReceiveBuffer.Status(0, 0, Set.of())), Optional.of(binding))));
        reply(screen, SerialControlData.encode(view), "ok", 1);
        assertFalse(button(screen, "face").active);
        assertTrue(button(screen, "clear_gateway").active);
        assertTrue(button(screen, "enable").active);
        var menu = (ComputerProgramMenu)get(screen, "menu");
        assertEquals(1L, get(menu, "latestControlIntent"), "Merely viewing a gateway cannot reroute or enable it");
    }

    @Test void panelGraphRequiresAMemberPinAndAnAvailableRecognizedPanel() throws Exception {
        var screen=screen(320); var menu=(ComputerProgramMenu)get(screen,"menu");
        reply(screen,snapshot(),"ok",1);
        assertFalse(button(screen,"bind_gateway").active,"Direct access has no cluster panel directory");
        set(menu,"memberPanelAccess",true); reply(screen,snapshot(),"ok",2);
        assertTrue(button(screen,"bind_gateway").active);
        set(screen,"graph",true); screen.init();
        var socket=new SerialControlData.PanelSocket(UUID.randomUUID(),new net.minecraft.core.BlockPos(4,5,6),Direction.EAST);
        var fields=new HashMap<String,DataValue>();
        fields.put("page",new DataValue.IntegerValue(0)); fields.put("count",new DataValue.IntegerValue(2));
        fields.put("panel",socket.encode());fields.put("state",new DataValue.TextValue("available"));
        reply(screen,new DataValue.RecordValue(fields),"ok",3);
        assertFalse(((CreateTextButton)get(screen,"targetSocket")).active);
        button(screen,"graph_source").onClick(0,0);
        assertTrue(((CreateTextButton)get(screen,"targetSocket")).active);
        assertEquals(3L,get(menu,"latestControlIntent"),"Selecting the source is not a bind request");
        fields.put("state",new DataValue.TextValue("gateway_conflict"));
        reply(screen,new DataValue.RecordValue(fields),"ok",4);
        assertFalse(((CreateTextButton)get(screen,"targetSocket")).active);
    }

    @Test void frozenObservationFaceDraftAndResizeNeverConfigureOrConsumeFrames() throws Exception {
        var screen = screen(320); var menu = (ComputerProgramMenu)get(screen, "menu");
        reply(screen, snapshot(), "ok", 1);
        assertEquals("s0", get(screen, "selected"));
        button(screen, "face").onClick(0, 0);
        var face = ((Map<?, ?>)get(screen, "faces")).get("s0");
        assertNotNull(face); assertEquals(1L, get(menu, "latestControlIntent"));
        set(screen, "width", 426); screen.init();
        assertEquals(face, ((Map<?, ?>)get(screen, "faces")).get("s0"));
        assertEquals(1L, get(menu, "latestControlIntent"));
        assertEquals(new DataValue.RecordValue(Map.of("private", new DataValue.IntegerValue(7))), menu.debuggerInputs());
        assertFalse(menu.endpointStatus().isPresent()); assertFalse(menu.communicationStatus().isPresent());
    }
    @Test void rejectionAndMalformedReplyClearOldMetadataAndDisableConfiguration() throws Exception {
        for (String code : List.of("stale", "invalid", "ok")) {
            var screen = screen(320); reply(screen, snapshot(), "ok", 1);
            reply(screen, new DataValue.RecordValue(Map.of()), code, 2);
            assertNull(get(screen, "view")); assertFalse(button(screen, "enable").active);
            assertFalse(button(screen, "disable").active);
        }
    }
    @Test void compactLayoutHasNoOverlappingOrOutOfBoundsControls() throws Exception {
        for (int width : List.of(320, 426)) {
          for (boolean breakpoint : List.of(false, true)) {
            var screen = screen(width); var data=snapshot();
            if(breakpoint) data=new DataValue.RecordValue(Map.of("serial",data.field("serial"),"breakpoint",trap()));
            reply(screen, data, "ok", 1);
            assertEquals(breakpoint,((CreateTextButton)get(screen,"route")).visible);
            var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w->w.visible).toList();
            for (var w : widgets) assertTrue(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= width && w.getY() + w.getHeight() <= 240);
            for (var a : widgets) for (var b : widgets) if (a != b)
                assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY());
          }
        }
    }
    private static DataValue.RecordValue trap() {
        return new DataValue.RecordValue(Map.of("state",new DataValue.TextValue("armed"),"xbus",new DataValue.TextValue("x0"),
            "pin",new DataValue.TextValue(""),"outcome",new DataValue.TextValue(""),"preview",new DataValue.TextValue("")));
    }
    @Test void xbusRouteCanBeSelectedWithoutAnySerialPort() throws Exception {
        var screen=screen(320);
        var serial=SerialControlData.encode(new SerialDevice.View(Optional.empty(),Map.of()));
        reply(screen,new DataValue.RecordValue(Map.of("serial",serial.field("serial"),"breakpoint",trap())),"ok",1);
        assertTrue(((CreateTextButton)get(screen,"route")).active);
        var method=ComputerSerialScreen.class.getDeclaredMethod("nextRoute");method.setAccessible(true);
        assertEquals("x0",method.invoke(screen));
    }
    private static DataValue.RecordValue snapshot() {
        return SerialControlData.encode(new SerialDevice.View(Optional.empty(), Map.of("s0", new SerialDevice.PortView(
            Optional.of(Direction.EAST), true, new SerialDevice.Status("idle", "ready", "", new SerialReceiveBuffer.Status(1, 128, Set.of()))))));
    }
    private static ComputerSerialScreen screen(int width) throws Exception {
        var s = allocate(ComputerSerialScreen.class); var menu = allocate(ComputerProgramMenu.class); var nonce = UUID.randomUUID();
        set(menu, "containerId", 1); set(menu, "nonce", nonce); set(menu, "controlResponse", Optional.empty()); set(menu, "status", Optional.empty());
        set(menu, "debuggerInputs", new DataValue.RecordValue(Map.of("private", new DataValue.IntegerValue(7))));
        set(menu, "response", Optional.of(new ProgramEditorResponse(1, nonce, 1, 1, "ready", "", new ResourceLimits(1000, 100000, 100), LIMITS, new byte[0])));
        set(s, "menu", menu); set(s, "faces", new HashMap<>()); set(s, "lines", List.of()); set(s, "notice", Component.empty());
        set(s, "requested", true); set(s, "title", Component.literal("Serial")); set(s, "playerInventoryTitle", Component.empty());
        set(s, "children", new ArrayList<>()); set(s, "renderables", new ArrayList<>()); set(s, "narratables", new ArrayList<>());
        set(s, "width", width); set(s, "height", 240); set(s, "minecraft", Minecraft.getInstance()); set(s, "font", Minecraft.getInstance().font);
        s.init(); return s;
    }
    private static void reply(ComputerSerialScreen s, DataValue.RecordValue value, String code, long intent) throws Exception {
        var menu = (ComputerProgramMenu)get(s, "menu"); set(menu, "latestControlIntent", intent); set(menu, "latestControlAction", ComputerControlSession.Action.READ_SERIAL);
        menu.accept(new ProgramStatusResponse(1, (UUID)get(menu, "nonce"), intent, 1, code, "", ProgramEditorWire.encodeValue(value, LIMITS)));
        s.containerTick();
    }
    private static CreateTextButton button(ComputerSerialScreen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.serial." + key))
            .findFirst().orElseThrow();
    }
}
