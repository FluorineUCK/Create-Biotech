package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class LibraryBookScreenTest {
    @Test void controlPageShowsMechanicalWorkAndRetainedFailureWithoutIssuingDeviceCommands() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        set(screen, "controlPage", true);
        for (String phase : List.of("writing", "result_pending")) {
            var drive = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(phase),
                "sequence", new DataValue.IntegerValue(53), "remaining", new DataValue.IntegerValue(phase.equals("writing") ? 91 : 0),
                "result", phase.equals("writing") ? DataValue.NullValue.INSTANCE : new DataValue.TextValue("denied")));
            var state = new DataValue.RecordValue(Map.of("control", new DataValue.RecordValue(Map.of(
                "revision", new DataValue.IntegerValue(2), "binding", DataValue.NullValue.INSTANCE, "drive", drive))));
            set(menu, "state", state); invoke(screen, "init");
            var label = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals("create_biotech.biocomputing.library.control.drive_status")).findFirst().orElseThrow();
            assertFalse(label.active, "Mechanical observation is not a control/ack button");
            var args = ((TranslatableContents)label.getMessage().getContents()).getArgs();
            assertEquals(53L, args[1]); assertEquals(phase.equals("writing") ? 91L : 0L, args[2]);
            assertEquals(phase.equals("writing") ? "—" : "denied", ((Component)args[3]).getString());
            assertEquals(0L, get(menu, "latestIntent")); assertSame(state, menu.state().orElseThrow());
            assertEquals("42", get(screen, "payload"));
            assertTrue(screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                .anyMatch(b -> b.active && b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals("create_biotech.pattern_core.browser.refresh")));
        }
    }
    @Test void librarianControlPagePreviewsOnePhysicalMemberWithoutSavingOrChangingTheBookDraft() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        var target = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("cluster"),
            "member", new DataValue.TextValue("4, 2, 3"), "terminal", new DataValue.TextValue("3, 2, 3"), "face", new DataValue.TextValue("west")));
        set(menu, "state", new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(List.of()), "selected", new DataValue.IntegerValue(-1),
            "entries", new DataValue.ListValue(List.of()), "loaded", DataValue.NullValue.INSTANCE,
            "control", new DataValue.RecordValue(Map.of("revision", new DataValue.IntegerValue(2), "code", new DataValue.TextValue("unbound"), "binding", DataValue.NullValue.INSTANCE)),
            "control_choices", new DataValue.ListValue(List.of(target)))));
        invoke(screen, "init");
        var open = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.library.control.title"))
            .findFirst().orElseThrow();
        open.onClick(0, 0);
        var choice = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.library.control.cluster"))
            .findFirst().orElseThrow();
        choice.onClick(0, 0);
        assertEquals(0L, get(menu, "latestIntent"), "Opening or previewing a member must not send a bind command");
        assertEquals("42", get(screen, "payload")); assertEquals("reading", get(screen, "name"));
        assertTrue(screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .anyMatch(b -> b.active && b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.library.control.bind")));
        set(menu, "state", new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(List.of()), "selected", new DataValue.IntegerValue(-1),
            "entries", new DataValue.ListValue(List.of()), "loaded", DataValue.NullValue.INSTANCE)));
        invoke(screen, "init");
        assertFalse(screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .anyMatch(b -> b.active && b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.library.control.bind")));
    }
    @Test void physicalAddressIsAvailableOnlyForTheCurrentAuthoritativeSelection() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        var method = LibraryBookScreen.class.getDeclaredMethod("selectedAddress"); method.setAccessible(true);
        assertEquals(Optional.empty(), method.invoke(screen));
        var address = new DataValue.RecordValue(Map.of("track", new DataValue.RecordValue(Map.of(
            "x", new DataValue.IntegerValue(2), "y", new DataValue.IntegerValue(0), "z", new DataValue.IntegerValue(1))),
            "slot", new DataValue.IntegerValue(2)));
        var fields = new HashMap<String, DataValue>();
        fields.put("books", new DataValue.ListValue(List.of(new DataValue.TextValue("32,64,1 / 3"))));
        fields.put("selected", new DataValue.IntegerValue(0)); fields.put("entries", new DataValue.ListValue(List.of()));
        fields.put("addresses", new DataValue.ListValue(List.of(address)));
        set(menu, "state", new DataValue.RecordValue(fields)); invoke(screen, "init");
        assertEquals(Optional.of(address), method.invoke(screen));
        var button = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.library.track_address")).findFirst().orElseThrow();
        assertTrue(button.active); assertNotNull(button.getTooltip());
        assertEquals(address, EditorValueFormat.parse(EditorValueFormat.Kind.JSON, EditorValueFormat.format(address)));
        assertEquals("42", get(screen, "payload")); assertEquals("reading", get(screen, "name"));
        fields.put("selected", new DataValue.IntegerValue(-1)); set(menu, "state", new DataValue.RecordValue(fields));
        assertEquals(Optional.empty(), method.invoke(screen));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "zh_cn"})
    void localizedEntryAndPermissionControlsPreserveTheirProtocolValuesAndUnsavedDraft(String locale) throws Exception {
        var translations = ComputerUiTranslationsTest.load(locale);
        var old = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
            var screen = screen(); String prefix = "create_biotech.biocomputing.library.";
            for (var kind : LibraryEntry.Kind.values()) {
                String key = prefix + "kind." + kind.name().toLowerCase(Locale.ROOT);
                assertTrue(translations.containsKey(key), key);
                var control = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                    .filter(b -> b.getMessage().getString().equals(translations.get(key))).findFirst().orElseThrow();
                assertEquals(kind, get(screen, "kind")); control.onClick(0, 0);
            }
            assertEquals(LibraryEntry.Kind.DATA, get(screen, "kind"));
            set(screen, "servicePage", true); invoke(screen, "init");
            var saved = get(screen, "serviceConfig");
            for (var access : com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access.values()) {
                String key = prefix + "access." + access.name().toLowerCase(Locale.ROOT);
                assertTrue(translations.containsKey(key), key);
                var control = (CreateTextButton) get(screen, "grantButton");
                assertEquals(translations.get(key), control.getMessage().getString());
                assertEquals(access, get(screen, "grant")); control.onClick(0, 0);
                assertEquals(saved, get(screen, "serviceConfig"), "A local toggle must not silently change saved authorization");
            }
            assertEquals("reading", get(screen, "name")); assertEquals("42", get(screen, "payload"));
            assertEquals(0L, get(get(screen, "menu"), "latestIntent"), "Changing display choices must not send a save request");
        } finally { net.minecraft.locale.Language.inject(old); }
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @Test void repeatedRefreshReplacesWidgetsInsteadOfStackingOldInputsAndButtons() throws Exception {
        var screen = screen(); int count = screen.children().size();
        var oldInput = (EditBox)get(screen, "valueBox"); oldInput.setValue("43"); screen.setFocused(oldInput);
        invoke(screen, "init"); invoke(screen, "init");
        assertEquals(count, screen.children().size());
        assertEquals(3, screen.children().stream().filter(EditBox.class::isInstance).count());
        assertFalse(screen.children().contains(oldInput)); assertNotSame(oldInput, screen.getFocused());
        assertEquals("43", ((EditBox)get(screen, "valueBox")).getValue());
    }
    @Test void freshReadReplacesUnsavedPayloadAndItsOldInputWidget() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        var oldInput = (EditBox)get(screen, "valueBox"); oldInput.setValue("43");
        var state = new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(List.of()),
            "selected", new DataValue.IntegerValue(-1), "entries", new DataValue.ListValue(List.of()),
            "loaded", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("DATA"), "value", new DataValue.IntegerValue(42)))));
        set(menu, "state", state); set(menu, "responseVersion", 1L);
        set(menu, "response", new LibraryBookResponse(0, UUID.randomUUID(), 0, 0, "ok", new ArchiveLimits(1000, 10000, 10000), new byte[0]));
        invoke(screen, "containerTick");
        assertEquals("42", ((EditBox)get(screen, "valueBox")).getValue());
        assertFalse(screen.children().contains(oldInput));
        assertEquals(3, screen.children().stream().filter(EditBox.class::isInstance).count());
    }
    @Test void loadAndSaveUseTheExistingVanillaTranslationKeys() throws Exception {
        var keys = screen().children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .map(button -> button.getMessage().getContents()).filter(TranslatableContents.class::isInstance)
            .map(TranslatableContents.class::cast).map(TranslatableContents::getKey).toList();
        assertTrue(keys.contains("structure_block.button.load")); assertTrue(keys.contains("structure_block.button.save"));
    }
    @Test void volumeInputIsIndependentAndAnAuthoritativeResponseUpdatesOnlyItsName() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        var input = (EditBox)get(screen, "volumeBox"); input.setValue("draft-volume");
        assertEquals("reading", ((EditBox)get(screen, "nameBox")).getValue());
        assertEquals("42", ((EditBox)get(screen, "valueBox")).getValue());
        var state = new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(List.of(new DataValue.TextValue("0,0,0 / 1"))),
            "selected", new DataValue.IntegerValue(0), "entries", new DataValue.ListValue(List.of()),
            "volume", new DataValue.TextValue("日志"), "volumeStatus", new DataValue.TextValue("ok"), "loaded", DataValue.NullValue.INSTANCE));
        set(menu, "state", state); set(menu, "responseVersion", 1L);
        set(menu, "response", new LibraryBookResponse(0, UUID.randomUUID(), 0, 0, "ok", new ArchiveLimits(1000, 10000, 10000), new byte[0]));
        invoke(screen, "containerTick");
        assertEquals("日志", ((EditBox)get(screen, "volumeBox")).getValue());
        assertEquals("reading", ((EditBox)get(screen, "nameBox")).getValue());
        assertEquals("42", ((EditBox)get(screen, "valueBox")).getValue());
        assertFalse(screen.children().contains(input));
    }
    @Test void servicePageHasSeparateEndpointInputAndPreservesBookDraft() throws Exception {
        var screen = screen(); set(screen, "servicePage", true); invoke(screen, "init");
        assertNotNull(get(screen, "endpointBox"));
        assertEquals("42", get(screen, "payload")); assertEquals("reading", get(screen, "name"));
        int count = screen.children().size(); invoke(screen, "init");
        assertEquals(count, screen.children().size());
        set(screen, "servicePage", false); invoke(screen, "init");
        assertEquals("42", ((EditBox)get(screen, "valueBox")).getValue());
    }
    @Test void directPasteCannotTurnAnOversizedArchiveIntoAnAcceptedPrefix() throws Exception {
        var screen = screen(); var menu = (LibraryBookMenu)get(screen, "menu");
        var limits = new ArchiveLimits(10000, 100000, 100000);
        set(menu, "nonce", UUID.randomUUID());
        set(menu, "response", new LibraryBookResponse(0, UUID.randomUUID(), 0, 0, "ok", limits, new byte[0]));
        var before = new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(List.of(new DataValue.TextValue("0,0,0 / 1"))),
            "selected", new DataValue.IntegerValue(0), "entries", new DataValue.ListValue(List.of()), "loaded", DataValue.NullValue.INSTANCE));
        set(menu, "state", before);
        var format = LibraryBookScreen.class.getDeclaredField("format"); format.setAccessible(true);
        format.set(screen, format.getType().getEnumConstants()[2]); invoke(screen, "init");
        int overhead = ProgramEditorWire.encodeValue(new DataValue.TextValue(""), limits).length;
        var value = new DataValue.TextValue("x".repeat((ProgramEditorWire.MAX_BYTES - overhead) / 2));
        String acceptedPrefix = Base64.getEncoder().encodeToString(ProgramEditorWire.encodeValue(value, limits));
        int maxText = ((ProgramEditorWire.MAX_BYTES + 2) / 3) * 4;
        assertEquals(maxText, acceptedPrefix.length());
        assertEquals(value, ProgramEditorWire.decodeValue(Base64.getDecoder().decode(acceptedPrefix), limits));
        String original = acceptedPrefix + "!";
        var input = (EditBox)get(screen, "valueBox"); input.setValue(""); input.insertText(original);
        assertEquals(original, get(screen, "payload"), "An overflow sentinel must survive direct paste rather than silently accepting its valid prefix");
        invoke(screen, "init"); assertEquals(original, ((EditBox)get(screen, "valueBox")).getValue());
        var save = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("structure_block.button.save"))
            .findFirst().orElseThrow();
        assertTrue(save.active); assertDoesNotThrow(() -> save.onClick(0, 0));
        assertEquals("too_large", get(screen, "notice"));
        assertEquals(0L, get(menu, "latestIntent")); assertSame(before, menu.state().orElseThrow());
        assertEquals(original, get(screen, "payload"));
    }
    private static LibraryBookScreen screen() throws Exception {
        var screen = allocate(LibraryBookScreen.class); set(screen, "menu", allocate(LibraryBookMenu.class));
        set(screen, "name", "reading"); set(screen, "payload", "42"); set(screen, "notice", "");
        set(screen, "kind", LibraryEntry.Kind.DATA);
        var format = LibraryBookScreen.class.getDeclaredField("format"); format.setAccessible(true); format.set(screen, format.getType().getEnumConstants()[0]);
        set(screen, "title", Component.literal("Library")); set(screen, "playerInventoryTitle", Component.empty());
        set(screen, "children", new ArrayList<>()); set(screen, "renderables", new ArrayList<>()); set(screen, "narratables", new ArrayList<>());
        set(screen, "width", 640); set(screen, "height", 360); set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font);
        invoke(screen, "init"); return screen;
    }
    private static void invoke(LibraryBookScreen screen, String method) throws Exception {
        var action = LibraryBookScreen.class.getDeclaredMethod(method); action.setAccessible(true); action.invoke(screen);
    }
}
