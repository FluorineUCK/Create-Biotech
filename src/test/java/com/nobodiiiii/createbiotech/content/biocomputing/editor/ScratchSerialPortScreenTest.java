package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchSerialPortScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    private net.neoforged.api.distmarker.Dist oldDist;
    private LocalPlayer oldPlayer;
    @BeforeEach void setUp() throws Exception {
        client.client();
        oldDist = net.neoforged.fml.loading.FMLEnvironment.dist;
        oldPlayer = Minecraft.getInstance().player;
        setDist(net.neoforged.api.distmarker.Dist.CLIENT);
    }
    @AfterEach void tearDown() throws Exception {
        try {
            Minecraft.getInstance().player = oldPlayer;
            setDist(oldDist);
        } finally {
            client.restore();
        }
    }
    private static void setDist(net.neoforged.api.distmarker.Dist value) throws Exception {
        var setter = ComputerWiringScreenTest.class.getDeclaredMethod("setDist", net.neoforged.api.distmarker.Dist.class);
        setter.setAccessible(true);
        setter.invoke(null, value);
    }

    @ParameterizedTest @ValueSource(strings = {"send", "receive", "status"})
    void chooseAnObservedLocalPortWithoutDroppingManualFieldsOrInstalling(String kind) throws Exception {
        var step = switch (kind) {
            case "send" -> new FlowProgram.SerialSend("serial", "portable", "packet", "sent");
            case "receive" -> new FlowProgram.SerialReceive("serial", "portable", "packet", true);
            default -> new FlowProgram.SerialStatus("serial", "portable", "packet", true);
        };
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(step), new DataValue.RecordValue(Map.of()))));
        var original = workspace.snapshot();
        var screen = FlowCallScreenTest.editor(workspace, 320);
        var menu = (ComputerProgramMenu)get(screen, "menu");
        set(menu, "nonce", menu.response().orElseThrow().nonce());
        set(menu, "controlResponse", Optional.empty());
        set(menu, "status", Optional.empty());
        var listener = (ClientPacketListener)allocate(Class.forName(ComputerWiringScreenTest.class.getName() + "$CaptureListener"));
        var sent = new ArrayList<>(); set(listener, "sent", sent);
        var player = allocate(LocalPlayer.class); set(player, "connection", listener); Minecraft.getInstance().player = player;
        selectBlock(screen, 0); click(screen, "edit");
        assertEquals("portable", boxes(screen).getFirst().getValue());
        click(screen, "serial_ports"); bounds(screen);
        assertEquals(1, sent.size());
        assertEquals(ComputerControlSession.Action.READ_SERIAL, get(menu, "latestControlAction"));
        assertTrue(options(screen).isEmpty());
        var payload = SerialControlData.encode(new SerialDevice.View(Optional.empty(), Map.of(
            "beta", port(false), "alpha", port(false))));
        reply(screen, menu, 0, "ok", payload);
        assertTrue(options(screen).isEmpty(), "A previous request cannot supply choices");
        reply(screen, menu, 1, "too_large", new DataValue.RecordValue(Map.of()));
        assertTrue(options(screen).isEmpty());
        assertTrue(get(screen, "notice").toString().contains("too_large"));
        click(screen, "serial_ports_refresh");
        reply(screen, menu, 2, "ok", payload); bounds(screen);
        assertEquals(List.of("alpha", "beta"), options(screen).stream().map(b ->
            ((TranslatableContents)b.getMessage().getContents()).getArgs()[0]).toList());
        options(screen).getLast().onClick(0, 0);
        assertEquals("beta", boxes(screen).getFirst().getValue());
        assertEquals(original, workspace.snapshot(), "Choosing only edits the form until Apply");
        if (kind.equals("send")) assertEquals("sent", boxes(screen).get(2).getValue());
        if (kind.equals("receive")) assertTrue(button(screen, "serial_format_nbt").active);
        if (kind.equals("status")) assertTrue(button(screen, "serial_status_claim").active);
        click(screen, "cancel");
        assertEquals(2, sent.size(), "Only the two read-only lookups used the menu");
        assertEquals(original, workspace.snapshot());
        click(screen, "edit");
        assertEquals("portable", boxes(screen).getFirst().getValue());
    }


    @ParameterizedTest @ValueSource(booleans = {true, false})
    void radioTabListsTheFirmwarePortOnlyOnEquippedResidents(boolean equipped) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.SerialReceive("rx", "manual", "packet", true)),
            new DataValue.RecordValue(Map.of()))));
        var screen = FlowCallScreenTest.editor(workspace, 320);
        var menu = (ComputerProgramMenu)get(screen, "menu");
        set(menu, "nonce", menu.response().orElseThrow().nonce());
        set(menu, "controlResponse", Optional.empty());
        set(menu, "status", Optional.empty());
        var listener = (ClientPacketListener)allocate(Class.forName(ComputerWiringScreenTest.class.getName() + "$CaptureListener"));
        set(listener, "sent", new ArrayList<>());
        var player = allocate(LocalPlayer.class); set(player, "connection", listener); Minecraft.getInstance().player = player;
        selectBlock(screen, 0); click(screen, "edit"); click(screen, "serial_ports"); click(screen, "serial_ports_wireless");
        assertEquals(ComputerControlSession.Action.READ_RADIO, get(menu, "latestControlAction"));
        reply(screen, menu, 1, "ok", SerialControlData.encode(new SerialDevice.View(Optional.empty(), Map.of("s0", port(false)))));
        assertTrue(options(screen).isEmpty(), "Late wired reply must not repopulate the radio panel");
        Map<String, DataValue> fields = new HashMap<>();
        fields.put("equipped", new DataValue.BooleanValue(equipped));
        fields.put("armed", new DataValue.BooleanValue(false));
        for (String f : List.of("preset", "wanted", "interference", "revision", "frames"))
            fields.put(f, new DataValue.IntegerValue(0));
        for (String f : List.of("preset_count", "first_center", "center_spacing", "transmit_width", "receive_width"))
            fields.put(f, new DataValue.IntegerValue(1));
        for (String f : List.of("reception", "diagnostic", "transmit"))
            fields.put(f, new DataValue.TextValue("idle"));
        reply(screen, menu, 2, "ok", new DataValue.RecordValue(fields));
        bounds(screen);
        if (equipped) {
            assertEquals(1, options(screen).size());
            options(screen).getFirst().onClick(0, 0);
            assertEquals("rf0", boxes(screen).getFirst().getValue());
        } else {
            assertTrue(options(screen).isEmpty());
            click(screen, "back");
            assertEquals("manual", boxes(screen).getFirst().getValue());
        }
    }

    private static SerialDevice.PortView port(boolean enabled) {
        return new SerialDevice.PortView(Optional.empty(), enabled, new SerialDevice.Status("idle", "closed", "",
            new SerialReceiveBuffer.Status(0, 0, Set.of())));
    }
    private static List<CreateTextButton> options(ComputerFlowScreen screen) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".serial_port_candidate")).toList();
    }
    private static void reply(ComputerFlowScreen screen, ComputerProgramMenu menu, long intent, String code, DataValue.RecordValue value) throws Exception {
        var authority = menu.response().orElseThrow();
        menu.accept(new ProgramStatusResponse(authority.containerId(), authority.nonce(), intent, authority.revision(), code, "",
            ProgramEditorWire.encodeValue(value, authority.archiveLimits())));
        screen.containerTick();
    }
}
