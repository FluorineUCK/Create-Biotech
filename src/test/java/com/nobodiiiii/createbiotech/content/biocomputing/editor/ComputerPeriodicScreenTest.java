package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PeriodicSchedule;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerPeriodicScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @BeforeAll static void bootstrapPeriodic() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { client.client(); }
    @AfterEach void restoreClient() throws Exception { client.restore(); }

    @Test void savedInputsAreLoadedOnlyOnExplicitClickAndRepliesKeepTypedIntervalAndFocus() throws Exception {
        var screen = screen(320); var menu = (ComputerProgramMenu)get(screen, "menu");
        var original = menu.debuggerInputs(); var box = (EditBox)get(screen, "interval");
        box.setValue("123"); screen.setFocused(box);
        var stored = new DataValue.RecordValue(Map.of("saved", new DataValue.ErrorValue("missing", "still a value")));
        reply(screen, new PeriodicSchedule(5, 2, true, stored), 1);
        assertEquals(original, menu.debuggerInputs()); assertSame(box, screen.getFocused());
        assertSame(box, get(screen, "interval")); assertEquals("123", box.getValue());
        button(screen, "load_inputs").onClick(0, 0);
        assertEquals(stored, menu.debuggerInputs()); assertEquals(stored, get(screen, "frozenInputs"));
        // Late old response cannot replace the accepted saved snapshot.
        var earlier = new PeriodicSchedule(4, 1, false, original);
        menu.accept(new ProgramStatusResponse(1, (UUID)get(menu, "nonce"), 0, 1, "ok", "",
            ProgramEditorWire.encodeValue(ComputerStatusView.periodic(Optional.of(earlier)), LIMITS)));
        screen.containerTick(); button(screen, "load_inputs").onClick(0, 0);
        assertEquals(stored, menu.debuggerInputs()); assertEquals("123", box.getValue());
    }

    @Test void initialReplySeedsUntouchedIntervalButEmptyScheduleNeverOverwritesLocalInputs() throws Exception {
        var screen = screen(426); var menu = (ComputerProgramMenu)get(screen, "menu");
        var inputs = menu.debuggerInputs(); reply(screen, new PeriodicSchedule(7, 3, false, inputs), 1);
        assertEquals("7", ((EditBox)get(screen, "interval")).getValue());
        set(menu, "latestControlIntent", 2L);
        menu.accept(new ProgramStatusResponse(1, (UUID)get(menu, "nonce"), 2, 2, "ok", "",
            ProgramEditorWire.encodeValue(ComputerStatusView.periodic(Optional.empty()), LIMITS)));
        screen.containerTick(); assertFalse(button(screen, "load_inputs").active);
        assertEquals(inputs, menu.debuggerInputs());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "0", "-1", "1.5", "9223372036854775808"})
    void invalidIntervalIsReportedWithoutSendingOrChangingLocalInputs(String text) throws Exception {
        var screen = screen(320); var menu = (ComputerProgramMenu)get(screen, "menu");
        var before = menu.debuggerInputs(); ((EditBox)get(screen, "interval")).setValue(text);
        assertDoesNotThrow(() -> button(screen, "save_enable").onClick(0, 0));
        assertEquals(0L, get(menu, "latestControlIntent")); assertEquals(before, menu.debuggerInputs());
        assertEquals("create_biotech.biocomputing.debug.invalid",
            assertInstanceOf(TranslatableContents.class, ((Component)get(screen, "notice")).getContents()).getKey());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426})
    void compactSettingsReuseCreateButtonsWithoutOverlap(int width) throws Exception {
        var screen = screen(width);
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        assertEquals(6, widgets.stream().filter(CreateTextButton.class::isInstance).count());
        for (var w : widgets) assertTrue(w.getX() >= 0 && w.getY() >= 0 && w.getWidth() > 0
            && w.getX() + w.getWidth() <= width && w.getY() + w.getHeight() <= 240);
        for (int i=0; i<widgets.size(); i++) for (int j=i+1; j<widgets.size(); j++) {
            var a=widgets.get(i); var b=widgets.get(j);
            assertFalse(a.getX()<b.getX()+b.getWidth() && a.getX()+a.getWidth()>b.getX()
                && a.getY()<b.getY()+b.getHeight() && a.getY()+a.getHeight()>b.getY());
        }
    }

    private static ComputerPeriodicScreen screen(int width) throws Exception {
        var screen=allocate(ComputerPeriodicScreen.class); var menu=allocate(ComputerProgramMenu.class); var nonce=UUID.randomUUID();
        set(menu,"containerId",1); set(menu,"nonce",nonce); set(menu,"controlResponse",Optional.empty()); set(menu,"status",Optional.empty());
        set(menu,"response",Optional.of(new ProgramEditorResponse(1,nonce,1,1,"ready","",new ResourceLimits(1000,100000,100),LIMITS,new byte[0])));
        var inputs=new DataValue.RecordValue(Map.of("local",new DataValue.IntegerValue(3))); set(menu,"debuggerInputs",inputs);
        set(screen,"menu",menu); set(screen,"frozenInputs",inputs); set(screen,"intervalText",""); set(screen,"saved",Optional.empty());
        set(screen,"notice",Component.empty()); set(screen,"requested",true);
        set(screen,"title",Component.literal("Periodic")); set(screen,"playerInventoryTitle",Component.empty());
        set(screen,"children",new ArrayList<>()); set(screen,"renderables",new ArrayList<>()); set(screen,"narratables",new ArrayList<>());
        set(screen,"width",width); set(screen,"height",240); set(screen,"minecraft",Minecraft.getInstance()); set(screen,"font",Minecraft.getInstance().font);
        screen.init(); return screen;
    }
    private static void reply(ComputerPeriodicScreen screen, PeriodicSchedule schedule, long intent) throws Exception {
        var menu=(ComputerProgramMenu)get(screen,"menu"); set(menu,"latestControlIntent",intent);
        set(menu,"latestControlAction",ComputerControlSession.Action.READ_PERIOD);
        menu.accept(new ProgramStatusResponse(1,(UUID)get(menu,"nonce"),intent,1,"ok","",
            ProgramEditorWire.encodeValue(ComputerStatusView.periodic(Optional.of(schedule)),LIMITS)));
        screen.containerTick();
    }
    private static CreateTextButton button(ComputerPeriodicScreen screen,String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.periodic."+key))
            .findFirst().orElseThrow();
    }
}
