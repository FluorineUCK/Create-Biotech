package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerSignalScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @BeforeAll static void bootstrapUi() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { client.client(); }
    @AfterEach void restore() throws Exception { client.restore(); }
    @Test void gatewayMetadataLocksFaceAndDoesNotSubmitOnRefreshOrRename() throws Exception {
        var s = screen(320);
        var binding = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding(UUID.randomUUID(), new net.minecraft.core.BlockPos(2, 3, 4), net.minecraft.core.Direction.EAST, "status");
        var pin = new HashMap<>(((DataValue.RecordValue)((DataValue.RecordValue)snapshot().field("ports")).field("p0")).fields());
        pin.put("side", new DataValue.TextValue("east")); pin.put("gateway", binding.value());
        var view = new DataValue.RecordValue(Map.of("diagnostic", DataValue.NullValue.INSTANCE, "ports", new DataValue.RecordValue(Map.of("p0", new DataValue.RecordValue(pin)))));
        reply(s, view, "ok", 1);
        assertFalse(button(s, "face").active); assertTrue(button(s, "clear_gateway").active); assertTrue(button(s, "enable").active);
        ((net.minecraft.client.gui.components.EditBox)get(s, "alias")).setValue("edited");
        reply(s, view, "ok", 2); set(s, "width", 426); s.init();
        assertEquals("edited", ((net.minecraft.client.gui.components.EditBox)get(s, "alias")).getValue());
        assertEquals(2L, get(get(s, "menu"), "latestControlIntent"));
    }
    @Test void noImplicitFaceAndDraftSurvivesRefreshAndResize() throws Exception {
        var s = screen(320); reply(s, snapshot(), "ok", 1);
        assertFalse(button(s, "enable").active);
        button(s, "face").onClick(0, 0);
        var chosen = ((Map<?, ?>)get(s, "faces")).get("p0"); assertNotNull(chosen);
        reply(s, snapshot(), "ok", 2); set(s, "width", 426); s.init();
        assertEquals(chosen, ((Map<?, ?>)get(s, "faces")).get("p0"));
        assertTrue(button(s, "enable").active);
        var menu = (ComputerProgramMenu)get(s, "menu");
        assertEquals(2L, get(menu, "latestControlIntent"));
        assertFalse(menu.endpointStatus().isPresent());
    }
    @Test void rejectedOrMalformedSnapshotsCannotLeaveOldConfigurationEnabled() throws Exception {
        for (var code : List.of("stale", "invalid", "ok")) {
            var s = screen(320); reply(s, snapshot(), "ok", 1);
            button(s, "face").onClick(0, 0);
            reply(s, new DataValue.RecordValue(Map.of()), code, 2);
            assertFalse(button(s, "enable").active); assertFalse(button(s, "disable").active);
        }
    }
    @Test void controlsFitCompactWindowsWithoutOverlap() throws Exception {
        for (int width : List.of(320, 426, 640)) {
            var s = screen(width); reply(s, snapshot(), "ok", 1);
            var widgets = s.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
            for (var w : widgets) assertTrue(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= width && w.getY() + w.getHeight() <= 240);
            for (var a : widgets) for (var b : widgets) if (a != b)
                assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY());
        }
    }
    private static DataValue.RecordValue snapshot() {
        return new DataValue.RecordValue(Map.of("diagnostic", DataValue.NullValue.INSTANCE,
            "ports", new DataValue.RecordValue(Map.of("p0", new DataValue.RecordValue(Map.of(
                "side", DataValue.NullValue.INSTANCE, "enabled", new DataValue.BooleanValue(false),
                "level", new DataValue.IntegerValue(12), "status", new DataValue.TextValue("closed")))))));
    }
    private static ComputerSignalScreen screen(int width) throws Exception {
        var s = allocate(ComputerSignalScreen.class); var menu = allocate(ComputerProgramMenu.class); var nonce = UUID.randomUUID();
        set(menu, "containerId", 1); set(menu, "nonce", nonce); set(menu, "controlResponse", Optional.empty()); set(menu, "status", Optional.empty());
        set(menu, "debuggerInputs", new DataValue.RecordValue(Map.of()));
        set(menu, "response", Optional.of(new ProgramEditorResponse(1, nonce, 1, 1, "ready", "", new ResourceLimits(1000, 100000, 100), LIMITS, new byte[0])));
        set(s, "menu", menu); set(s, "faces", new HashMap<>()); set(s, "lines", List.of()); set(s, "notice", Component.empty());
        set(s, "requested", true); set(s, "title", Component.literal("SIO")); set(s, "playerInventoryTitle", Component.empty());
        set(s, "children", new ArrayList<>()); set(s, "renderables", new ArrayList<>()); set(s, "narratables", new ArrayList<>());
        set(s, "width", width); set(s, "height", 240); set(s, "minecraft", Minecraft.getInstance()); set(s, "font", Minecraft.getInstance().font);
        s.init(); return s;
    }
    private static void reply(ComputerSignalScreen s, DataValue.RecordValue value, String code, long intent) throws Exception {
        var menu = (ComputerProgramMenu)get(s, "menu"); set(menu, "latestControlIntent", intent); set(menu, "latestControlAction", ComputerControlSession.Action.READ_SIMPLE_IO);
        menu.accept(new ProgramStatusResponse(1, (UUID)get(menu, "nonce"), intent, 1, code, "", ProgramEditorWire.encodeValue(value, LIMITS)));
        s.containerTick();
    }
    private static CreateTextButton button(ComputerSignalScreen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.signal." + key)).findFirst().orElseThrow();
    }
}
