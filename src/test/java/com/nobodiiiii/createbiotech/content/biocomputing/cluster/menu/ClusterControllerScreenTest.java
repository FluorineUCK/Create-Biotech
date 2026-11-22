package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;

/** Exercises the real Create widgets; only the headless Minecraft/font bootstrap is replaced. */
class ClusterControllerScreenTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hardware_mismatch", "resource_limit", "member_unloaded", "access_denied"})
    void unavailableNamedMemberHasOneDiagnosticRowWithoutInventingAnIdentity(String reason) throws Exception {
        UUID identity = UUID.randomUUID();
        var configuration = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(identity, BlockPos.ZERO)), Map.of());
        boolean editable = reason.equals("hardware_mismatch") || reason.equals("resource_limit");
        var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, editable, reason);
        var screen = screen(new ClusterControlView(configuration, List.of(candidate), "ready", 0, 0), "MEMBERS", 320, 240);
        long rows = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents t
                && List.of("create_biotech.biocomputing.cluster.member", "create_biotech.biocomputing.cluster.candidate").contains(t.getKey())).count();
        assertEquals(1, rows, "A failed identity observation is not a second physical Computer");
        assertTrue(button(screen, "member").getMessage().getString().contains(reason));
        click(screen, "member");
        assertEquals(editable, button(screen, "open_computer").active, "Keep the authorized diagnostic editor, not new access");
        assertEquals(configuration, ((ClusterControlDraft)get(screen, "draft")).configuration());
        assertFalse((boolean)get(screen, "dirty"));
        assertTrue(candidate.identity().isEmpty(), "Do not synthesize an identity from the saved binding");
    }

    @Test void replacementAtNamedMembersPositionCannotMasqueradeAsTheSavedMember() throws Exception {
        var initial = twoMemberView(); var screen = screen(initial, "MEMBERS", 320, 240);
        click(screen, "member"); String selected = (String)get(screen, "selectedMember");
        var saved = initial.configuration().members().get(selected);
        var summary = new ComputerHardwareView(HardwareSelection.Status.NO_RESIDENT, Optional.empty(), "", Optional.empty());
        var replacement = new ClusterControlView.Candidate(saved.position(), Optional.of(UUID.randomUUID()), List.of(), true, true, true, "ready", Optional.of(summary));
        set(screen, "waiting", true); setEnum(screen, "pending", "REFRESH");
        deliver(screen, new ClusterControlView(initial.configuration(), List.of(replacement), "ready", 0, 0), "ready"); screen.containerTick();
        assertFalse(button(screen, "hardware").active, "Do not label another physical instance as the saved member");
        assertFalse(button(screen, "open_computer").active);
        click(screen, "members"); click(screen, "candidate");
        assertTrue(button(screen, "hardware").active, "Replacement remains explicitly accessible as its own unnamed candidate");
        assertTrue(button(screen, "open_computer").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"access_denied", "member_unloaded", "member_missing", "empty_response"})
    void refreshedUnavailableMemberCannotKeepItsCapturedHardwareOrEditorAccess(String reason) throws Exception {
        var summary = new ComputerHardwareView(HardwareSelection.Status.NO_RESIDENT, Optional.empty(), "", Optional.empty());
        var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(UUID.randomUUID()), List.of(), true, true, true, "ready", Optional.of(summary));
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "candidate"); click(screen, "hardware");
        set(screen, "waiting", true); setEnum(screen, "pending", "REFRESH");
        var unavailable = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, false, reason);
        deliver(screen, new ClusterControlView(ClusterConfiguration.empty(), List.of(unavailable), "ready", 0, 0), reason);
        if (reason.equals("empty_response")) {
            var menu = (ClusterControllerMenu)get(screen, "menu"); var reply = menu.response().orElseThrow();
            set(menu, "response", Optional.of(new ClusterControlResponse(reply.containerId(), reply.nonce(), reply.intent(), reply.revision(), "too_large", "", reply.archiveLimits(), new byte[0])));
        }
        screen.containerTick();
        assertTrue(screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
            .anyMatch(label -> label.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.cluster.hardware_unavailable")), "No stale resident summary after failed refresh");
        click(screen, "back"); assertFalse(button(screen, "hardware").active);
        assertFalse(button(screen, "open_computer").active); assertFalse(button(screen, "apply").active);
        assertEquals(ClusterConfiguration.empty(), ((ClusterControlDraft)get(screen, "draft")).configuration());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 800})
    void memberHardwareHasReadablePagedFactsWithoutChangingConfiguration(int width) throws Exception {
        var summary = new ComputerHardwareView(HardwareSelection.Status.READY,
            Optional.of(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:librarian"), 3)),
            "program", Optional.of(new ComputerHardwareView.Effective(new ResourceLimits(128, 32768, 4),
                new HardwarePins(List.of("x0", "long_pin_" + "q".repeat(50)), List.of("p0", "p1")),
                new SerialHardware(Map.of("uplink", new SerialHardware.Port(32, 1024, 2, 2048))))));
        var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, true, "no_program", Optional.of(summary));
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0);
        var screen = screen(view, "MEMBERS", width, width == 320 ? 240 : 480);
        click(screen, "candidate"); assertContainedNonOverlapping(screen);
        assertTrue(button(screen, "hardware").active); click(screen, "hardware");
        var facts = new ArrayList<Component>(); int pages = 0;
        do {
            assertContainedNonOverlapping(screen);
            for (var child : screen.children()) if (child instanceof StringWidget label && label != get(screen, "noticeLabel")) {
                assertFalse(label.active); assertEquals(label.getMessage().getString(), tooltipText(label)); facts.add(label.getMessage());
            }
            assertTrue(++pages < 20, "Hardware view must not have an unbounded page loop");
            if (!button(screen, "next").active) break;
            click(screen, "next");
        } while (true);
        var keys = facts.stream().map(c -> ((TranslatableContents)c.getContents()).getKey()).toList();
        for (String key : List.of("hardware_type", "hardware_profession", "hardware_level", "hardware_profile", "hardware_program", "hardware_memory", "hardware_work", "hardware_xbus", "hardware_simple_io",
            "hardware_serial_pending", "hardware_serial_port", "hardware_serial_rate", "hardware_serial_frame", "hardware_serial_receive"))
            assertTrue(keys.contains("create_biotech.biocomputing.cluster." + key), key);
        assertEquals(2, Collections.frequency(keys, "create_biotech.biocomputing.cluster.hardware_xbus"));
        assertEquals(2, Collections.frequency(keys, "create_biotech.biocomputing.cluster.hardware_simple_io"));
        click(screen, "back"); assertTrue(button(screen, "open_computer").active);
        assertFalse((boolean)get(screen, "dirty")); assertEquals(view.configuration(), ((ClusterControlDraft)get(screen, "draft")).configuration());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"back", "members", "ports", "connections", "wires", "signals", "public_pins"})
    void leavingWiringDirectoryRefreshesTheConfigurationBeforeAnyFurtherEdit(String navigation) throws Exception {
        var original = twoMemberView(); var screen = screen(original, "WIRING_SOURCES", 320, 240);
        // Suppress real networking in this headless widget fixture; native tests exercise actual packets.
        var menu = (ClusterControllerMenu)get(screen, "menu");
        set(menu, "serverOwner", allocate(com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity.class));
        click(screen, navigation);
        assertTrue((boolean)get(screen, "waiting"), "Leaving via Back or a tab must fence edits until the complete new configuration arrives");
        // No editing or network action while the reply is pending. Offline help remains usable.
        var active = screen.children().stream().filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast).filter(widget -> widget.active).toList();
        assertEquals(1, active.size());
        assertEquals("create_biotech.biocomputing.manual.open",
            ((net.minecraft.network.chat.contents.TranslatableContents)active.getFirst().getMessage().getContents()).getKey());
        var other = new ClusterControlDraft(original.configuration());
        other.putPublicPin(null, "other_player", new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("source", "responses", "x0"), true));
        deliver(screen, new ClusterControlView(other.configuration(), original.candidates(), "ready", 0, 0), "ready"); screen.containerTick();
        assertEquals(other.configuration(), ((ClusterControlDraft)get(screen, "draft")).configuration());
        assertFalse((boolean)get(screen, "waiting"));
    }

    @Test void unappliedPublicPinFormEditsImmediatelyDisableItsWiringShortcut() throws Exception {
        var original = twoMemberView(); var draft = new ClusterControlDraft(original.configuration());
        draft.putPublicPin(null, "io", new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("source", "responses", "x0"), true));
        var screen = screen(new ClusterControlView(draft.configuration(), original.candidates(), "ready", 0, 0), "PUBLIC_PINS", 320, 240);
        click(screen, "public_pin_enabled"); assertTrue(button(screen, "external_wiring").active);
        edits(screen).getFirst().setValue("not_saved");
        assertFalse(button(screen, "external_wiring").active, "Even an unapplied field edit must not appear to rebind the saved source");
        edits(screen).getFirst().setValue("io"); assertTrue(button(screen, "external_wiring").active);
        edits(screen).get(2).setValue("x1"); assertFalse(button(screen, "external_wiring").active);
        assertContainedNonOverlapping(screen);
    }

    @Test void externalWiringEntryRemainsAvailableWithNoPublicPinsButRequiresASavedDraft() throws Exception {
        var screen = screen(new ClusterControlView(ClusterConfiguration.empty(), List.of(), "ready", 0, 0), "PUBLIC_PINS", 320, 240);
        assertTrue(button(screen, "external_wiring").active, "Deleted aliases may still own saved declarations needing cleanup");
        assertContainedNonOverlapping(screen);
        set(screen, "dirty", true); screen.init();
        assertFalse(button(screen, "external_wiring").active, "Unpublished configuration must not become a physical wiring source");
        assertContainedNonOverlapping(screen);
    }

    @Test void publicPinTooltipRetainsFullMappingAndAuthoritativeDiagnosis() throws Exception {
        var original = twoMemberView(); var draft = new ClusterControlDraft(original.configuration());
        String name = "a_long_public_pin_name";
        draft.putPublicPin(null, name, new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("source", "responses", "x0"), true));
        String diagnosis = "provider_specific_failure_with_distinct_detail_at_the_end";
        var view = new ClusterControlView(draft.configuration(), original.candidates(), "ready", 0, 0,
            Map.of(), Map.of(name, diagnosis));
        var screen = screen(view, "PUBLIC_PINS", 320, 240);
        var row = button(screen, "public_pin_enabled");
        String tooltip = row.getToolTip().stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(tooltip.startsWith(row.getMessage().getString()), "Truncated rows must expose their complete label and status");
        assertTrue(tooltip.contains(diagnosis));
        assertTrue(tooltip.contains("create_biotech.biocomputing.cluster.public_pin_help"));
        assertContainedNonOverlapping(screen);
    }

    @Test void completeNoticeIsAvailableAfterServerFailureAndImmediateLocalValidation() throws Exception {
        var view = twoMemberView(); var screen = screen(view, "MEMBERS", 320, 240);
        String serverFailure = "specific_save_failure_whose_final_detail_must_not_disappear";
        set(screen, "waiting", true); setEnum(screen, "pending", "SAVE");
        deliver(screen, view, "provider_failure", serverFailure); screen.containerTick();
        var label = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("Server notice needs a complete readable tooltip"));
        assertEquals("provider_failure · " + serverFailure, label.getMessage().getString());
        assertEquals(label.getMessage().getString(), tooltipText(label));
        assertFalse(label.active, "Notice must never become a configuration action");
        click(screen, "public_pins"); click(screen, "add_public_pin");
        label = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast).findFirst().orElseThrow();
        var boxes = edits(screen); boxes.get(0).setValue(""); boxes.get(2).setValue("x0"); click(screen, "apply");
        assertEquals("create_biotech.biocomputing.cluster.invalid", ((TranslatableContents)label.getMessage().getContents()).getKey());
        assertEquals(((Component)get(screen, "notice")).getString(), tooltipText(label));
        assertTrue(((ClusterControlDraft)get(screen, "draft")).configuration().publicPins().isEmpty());
        assertEquals("x0", boxes.get(2).getValue()); assertContainedNonOverlapping(screen);
    }

    private static String tooltipText(AbstractWidget widget) {
        var result = new StringBuilder(); assertNotNull(widget.getTooltip());
        widget.getTooltip().updateNarration(new net.minecraft.client.gui.narration.NarrationElementOutput() {
            @Override public void add(net.minecraft.client.gui.narration.NarratedElementType type,
                net.minecraft.client.gui.narration.NarrationThunk<?> thunk) { thunk.getText(result::append); }
            @Override public net.minecraft.client.gui.narration.NarrationElementOutput nest() { return this; }
        });
        return result.toString();
    }

    @Test void publicXBusPinPageEditsOnlyADraftAndCanDisableCancelAndRemoveItsMapping() throws Exception {
        var view = twoMemberView(); var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "public_pins"); click(screen, "add_public_pin"); assertContainedNonOverlapping(screen);
        var boxes = edits(screen); assertEquals(3, boxes.size());
        boxes.get(0).setValue("io"); assertEquals("responses", boxes.get(1).getValue()); boxes.get(2).setValue("x0");
        click(screen, "apply"); var draft = (ClusterControlDraft)get(screen, "draft");
        assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("source", "responses", "x0"), true), draft.configuration().publicPins().get("io"));
        assertEquals(view.configuration().ports(), draft.configuration().ports());
        assertTrue(((ClusterControlView)get(screen, "authority")).configuration().publicPins().isEmpty());
        assertTrue((boolean)get(screen, "dirty"));
        click(screen, "public_pin_enabled"); edits(screen).get(0).setValue("cancelled"); click(screen, "cancel");
        assertEquals(Set.of("io"), draft.configuration().publicPins().keySet());
        click(screen, "public_pin_enabled"); click(screen, "enabled"); click(screen, "apply");
        assertFalse(draft.configuration().publicPins().get("io").enabled());
        click(screen, "public_pin_disabled"); click(screen, "remove"); assertTrue(draft.configuration().publicPins().isEmpty());
        assertContainedNonOverlapping(screen);
    }
    @Test void physicalEditingRemainsAvailableAcrossHardwareAndCommunicationDiagnostics() throws Exception {
        for (String diagnostic : List.of("unavailable", "already_bound", "controller_unloaded")) {
            var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, true, diagnostic);
            var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0);
            var screen = screen(view, "MEMBERS", 320, 240);
            assertTrue(button(screen, "candidate").active); click(screen, "candidate");
            assertTrue(button(screen, "open_computer").active); assertFalse(button(screen, "apply").active);
            assertEquals(view, ClusterControlWire.decodeView(ClusterControlWire.encodeView(view,
                new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(256, 8192, 32768)),
                new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(256, 8192, 32768)));
        }
        var denied = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.empty(), List.of(), true, false, false, "access_denied");
        assertFalse(button(screen(new ClusterControlView(ClusterConfiguration.empty(), List.of(denied), "ready", 0, 0), "MEMBERS", 320, 240), "candidate").active);
    }
    @Test void openingMemberNeverDiscardsUnsavedControllerEditsAndFailureKeepsSelection() throws Exception {
        var initial = twoMemberView(); var screen = screen(initial, "MEMBERS", 320, 240);
        click(screen, "member"); assertTrue(button(screen, "open_computer").active);
        set(screen, "dirty", true); screen.init(); assertFalse(button(screen, "open_computer").active);
        set(screen, "dirty", false); set(screen, "waiting", true); setEnum(screen, "pending", "OPEN");
        deliver(screen, initial, "stale"); screen.containerTick();
        assertEquals(initial.configuration(), ((ClusterControlDraft)get(screen, "draft")).configuration());
        assertTrue(button(screen, "open_computer").active); assertContainedNonOverlapping(screen);
    }
    @Test void signalPinLabelsNeverSuggestXbusPinsAndSwitchBackForXbus() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "signals"); click(screen, "add_signal");
        assertEquals("create_biotech.biocomputing.cluster.left_signal_pin", ((TranslatableContents)edits(screen).get(3).getMessage().getContents()).getKey());
        assertEquals("create_biotech.biocomputing.cluster.right_signal_pin", ((TranslatableContents)edits(screen).get(4).getMessage().getContents()).getKey());
        click(screen, "cancel"); click(screen, "wires"); click(screen, "graph_list"); click(screen, "add_wire");
        assertEquals("create_biotech.biocomputing.cluster.left_pin", ((TranslatableContents)edits(screen).get(3).getMessage().getContents()).getKey());
        assertEquals("create_biotech.biocomputing.cluster.right_pin", ((TranslatableContents)edits(screen).get(4).getMessage().getContents()).getKey());
    }
    @Test void compoundSignalDiagnosticsReuseEachReasonTranslation() throws Exception {
        var method = ClusterControllerScreen.class.getDeclaredMethod("code", String.class); method.setAccessible(true);
        var label = (Component) method.invoke(null, "signal_closed+hardware_mismatch+resource_limit");
        var keys = new ArrayList<String>(); var queue = new ArrayDeque<Component>(); queue.add(label);
        while (!queue.isEmpty()) {
            var part = queue.remove();
            if (part.getContents() instanceof TranslatableContents translation) keys.add(translation.getKey());
            queue.addAll(part.getSiblings());
        }
        assertEquals(List.of("create_biotech.biocomputing.cluster.code.signal_closed",
            "create_biotech.biocomputing.cluster.code.signal_hardware_mismatch",
            "create_biotech.biocomputing.cluster.code.signal_resource_limit"), keys);
        var levels = (Component) method.invoke(null, "signal_0_15");
        assertEquals("create_biotech.biocomputing.cluster.signal_levels", ((TranslatableContents) levels.getContents()).getKey());
        assertEquals("signal_future+unknown", ((Component) method.invoke(null, "signal_future+unknown")).getString());
    }
    @Test void signalPageUsesSeparateTypedDraftAndCanRemoveOnlyItsOwnWire() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "signals"); click(screen, "add_signal"); assertContainedNonOverlapping(screen);
        var boxes = edits(screen); boxes.get(0).setValue("voltage"); boxes.get(3).setValue("p0"); boxes.get(4).setValue("p1");
        click(screen, "apply"); var config = ((ClusterControlDraft)get(screen, "draft")).configuration();
        assertEquals(Set.of("voltage"), config.signals().keySet()); assertTrue(config.wires().isEmpty());
        assertEquals("p0", config.signals().get("voltage").left().pin());
        assertTrue((boolean)get(screen, "dirty"));
        click(screen, "signal_enabled"); click(screen, "remove");
        assertTrue(((ClusterControlDraft)get(screen, "draft")).configuration().signals().isEmpty());
        assertContainedNonOverlapping(screen);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 800})
    void graphClicksConnectOnlyTheChosenHardwarePinsInTheLocalDraft(int width) throws Exception {
        var view = graphView();
        var screen = screen(view, "MEMBERS", width, width == 320 ? 240 : 480);
        click(screen, "wires");
        assertContainedNonOverlapping(screen);
        assertTrue(edits(screen).isEmpty(), "Wiring the graph must not require typing a pin or a route name");
        var draft = (ClusterControlDraft)get(screen, "draft");
        select(screen, 1, 1); // Choose the second granted endpoint of the left member.
        var first = graphPin(screen, "left_b");
        var second = graphPin(screen, "right_a");
        assertTrue(first.getX() < second.getX(), "Both members' pins must be visible together");
        press(first);
        assertEquals(view.configuration(), draft.configuration(), "Selecting the first pin is not a wire edit");
        assertFalse(button(screen, "save").active);
        press(graphPin(screen, "right_a"));

        var wires = new LinkedHashMap<>(draft.configuration().wires());
        assertEquals(view.configuration().wires().get("old_pair"), wires.remove("old_pair"));
        assertEquals(1, wires.size(), "Only one new pair may be created");
        var expected = new XBusWire(new XBusWire.Pin("source", "alternate", "left_b"),
            new XBusWire.Pin("target", "messages", "right_a"), true);
        var actual = wires.values().iterator().next();
        assertTrue(actual.enabled());
        assertEquals(Set.of(expected.left(), expected.right()), Set.of(actual.left(), actual.right()));
        assertEquals(view, get(screen, "authority"), "A pin click does not publish a configuration");
        assertEquals(0L, get(get(screen, "menu"), "latestIntent"), "No configure or runtime action before Save");
        assertTrue(button(screen, "save").active);
        assertContainedNonOverlapping(screen);
    }

    @Test void graphAddsThirdComputerToAnAlreadyWiredPhysicalPin() throws Exception {
        var original = graphView();
        var extraId = UUID.randomUUID();
        var extraPos = new BlockPos(2, 0, 0);
        var members = new LinkedHashMap<>(original.configuration().members());
        members.put("third", new ClusterConfiguration.Member(extraId, extraPos));
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), original.configuration().wires());
        var candidates = new ArrayList<>(original.candidates());
        candidates.add(new ClusterControlView.Candidate(extraPos, Optional.of(extraId), List.of("third_grant"),
            true, true, true, "ready", candidates.getFirst().hardware()));
        var view = new ClusterControlView(config, candidates, "configured", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "wires");
        select(screen, 0, 2); // Third Computer on the left; keep the already wired target on the right.
        press(graphPin(screen, "left_a")); press(graphPin(screen, "right_b"));
        var wires = new LinkedHashMap<>(((ClusterControlDraft)get(screen, "draft")).configuration().wires());
        assertEquals(config.wires().get("old_pair"), wires.remove("old_pair"));
        assertEquals(List.of(new XBusWire(new XBusWire.Pin("third", "third_grant", "left_a"),
            new XBusWire.Pin("target", "messages", "right_b"), true)), List.copyOf(wires.values()));
        assertTrue(button(screen, "save").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void graphRejectsDuplicatePhysicalEdgeEvenWhenReversedOrUsingAnotherGrant(boolean enabled) throws Exception {
        var original = graphView();
        var old = original.configuration().wires().get("old_pair");
        var config = new ClusterConfiguration(original.configuration().members(), Map.of(), Map.of(),
            Map.of("old_pair", new XBusWire(old.left(), old.right(), enabled)));
        var view = new ClusterControlView(config, original.candidates(), "configured", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "wires");
        select(screen, 1, 1); // Same physical pin, different endpoint grant.
        press(graphPin(screen, "right_b")); press(graphPin(screen, "left_a"));
        assertEquals(config, ((ClusterControlDraft)get(screen, "draft")).configuration());
        assertFalse(button(screen, "save").active);
        assertEquals("create_biotech.biocomputing.cluster.graph_duplicate",
            ((TranslatableContents)((Component)get(screen, "notice")).getContents()).getKey());
    }

    @Test void graphDoesNotOfferPinsFromAReplacementAtASavedMembersPosition() throws Exception {
        var original = graphView();
        var source = original.candidates().getFirst(); var target = original.candidates().getLast();
        var replacement = new ClusterControlView.Candidate(target.position(), Optional.of(UUID.randomUUID()),
            target.endpoints(), true, true, true, "ready", target.hardware());
        var view = new ClusterControlView(original.configuration(), List.of(source, replacement), "configured", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "wires");
        assertTrue(graphPins(screen).stream().noneMatch(row -> row.active && pinLabel(row).contains("right_")),
            "A replacement computer may not supply hardware for the saved target binding");
        var ghost = button(screen, "graph_saved_pin");
        assertEquals("\"right_b\"", pinLabel(ghost), "Show the saved socket, not all replacement hardware");
        assertFalse(ghost.active);
        ghost.onClick(0, 0); assertNull(get(screen, "graphAnchor"), "A read-only socket has no selection callback");
        assertEquals(List.of(0xff858a8f, 0xff858a8f), graphEdgeColors(screen));
        assertEquals(view.configuration(), ((ClusterControlDraft)get(screen, "draft")).configuration());
        assertFalse(button(screen, "save").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void savedWireRemainsVisibleAndReadOnlyWhenBothGrantsCloseOrMembersDisappear(boolean present) throws Exception {
        var original = graphView();
        var candidates = present ? original.candidates().stream().map(c -> new ClusterControlView.Candidate(
            c.position(), c.identity(), List.of(), true, true, true, "ready", c.hardware())).toList() : List.<ClusterControlView.Candidate>of();
        var view = new ClusterControlView(original.configuration(), candidates, "configured", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "wires");
        var ghosts = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(w -> w.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().endsWith(".graph_saved_pin")).toList();
        assertEquals(List.of("\"left_a\"", "\"right_b\""), ghosts.stream().map(ClusterControllerScreenTest::pinLabel).toList());
        assertTrue(ghosts.stream().noneMatch(w -> w.active));
        assertTrue(graphPins(screen).stream().noneMatch(w -> w.active));
        assertEquals(List.of(0xff858a8f, 0xff858a8f), graphEdgeColors(screen), "Saved edge still drawn across both sockets in gray");
        assertFalse(button(screen, "save").active);
    }

    private static List<Integer> graphEdgeColors(ClusterControllerScreen screen) throws Exception {
        var colors = new ArrayList<Integer>();
        var graphics = new net.minecraft.client.gui.GuiGraphics(Minecraft.getInstance(), null) {
            @Override public void fill(int x1, int y1, int x2, int y2, int color) {}
            @Override public void hLine(int x1, int x2, int y, int color) { colors.add(color); }
            @Override public void vLine(int x, int y1, int y2, int color) {}
        };
        var draw = ClusterControllerScreen.class.getDeclaredMethod("drawWireGraph", net.minecraft.client.gui.GuiGraphics.class);
        draw.setAccessible(true); draw.invoke(screen, graphics);
        return colors;
    }

    private static ClusterControlView graphView() {
        var original = twoMemberView();
        var old = new XBusWire(new XBusWire.Pin("source", "responses", "left_a"),
            new XBusWire.Pin("target", "messages", "right_b"), true);
        var configuration = new ClusterConfiguration(original.configuration().members(), Map.of(), Map.of(), Map.of("old_pair", old));
        var candidates = new ArrayList<ClusterControlView.Candidate>();
        for (var candidate : original.candidates()) {
            var side = candidate.position().equals(BlockPos.ZERO) ? "left_" : "right_";
            var pins = new HardwarePins(List.of(side + "a", side + "b"), List.of("p0"));
            var hardware = new ComputerHardwareView(HardwareSelection.Status.READY, Optional.empty(), "test",
                Optional.of(new ComputerHardwareView.Effective(new ResourceLimits(128, 32768, 4), pins)));
            var grants = candidate.position().equals(BlockPos.ZERO) ? List.of("responses", "alternate") : candidate.endpoints();
            candidates.add(new ClusterControlView.Candidate(candidate.position(), candidate.identity(), grants,
                true, true, true, "ready", Optional.of(hardware)));
        }
        return new ClusterControlView(configuration, candidates, "configured", 0, 0);
    }

    private static List<CreateTextButton> graphPins(ClusterControllerScreen screen) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(row -> row.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.cluster.graph_pin")).toList();
    }

    private static CreateTextButton graphPin(ClusterControllerScreen screen, String name) {
        return graphPins(screen).stream().filter(row -> pinLabel(row).equals('"' + name + '"')).findFirst()
            .orElseThrow(() -> new AssertionError("Missing graphical pin: " + name));
    }

    private static String pinLabel(CreateTextButton row) {
        Object label = ((TranslatableContents)row.getMessage().getContents()).getArgs()[0];
        return label instanceof Component text ? text.getString() : label.toString();
    }

    private static void press(CreateTextButton control) {
        assertTrue(control.active && control.visible, control.getMessage().getString());
        control.onClick(control.getX() + control.getWidth() / 2d, control.getY() + control.getHeight() / 2d);
    }

    @Test void xbusPageEditsTwoPinsWithoutInventingInputOutputRoles() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "wires"); click(screen, "graph_list"); click(screen, "add_wire");
        assertContainedNonOverlapping(screen);
        var boxes = edits(screen); assertEquals(5, boxes.size());
        boxes.get(0).setValue("data");
        assertEquals("responses", boxes.get(1).getValue()); assertEquals("messages", boxes.get(2).getValue());
        boxes.get(3).setValue("x0"); boxes.get(4).setValue("x1"); click(screen, "apply");
        var config = ((ClusterControlDraft)get(screen, "draft")).configuration();
        assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire(
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("source", "responses", "x0"),
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire.Pin("target", "messages", "x1"), true), config.wires().get("data"));
        assertTrue(config.connections().isEmpty());
        click(screen, "wire_enabled"); click(screen, "remove");
        assertTrue(((ClusterControlDraft)get(screen, "draft")).configuration().wires().isEmpty());
    }
    @Test void unknownMemberAndServerDiagnosticsRemainVerbatimInsteadOfInventedTranslationKeys() throws Exception {
        String unknown = "addon_problem:%s";
        var candidate = new ClusterControlView.Candidate(new BlockPos(1, 2, 3), Optional.empty(), List.of(), true, false, unknown);
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), unknown, 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        var args = ((TranslatableContents) button(screen, "candidate").getMessage().getContents()).getArgs();
        assertEquals(unknown, ((Component) args[3]).getString());
        deliver(screen, new ClusterControlView(ClusterConfiguration.empty(), List.of(), "addon_problem", 0, 0), "addon_problem"); screen.containerTick();
        assertEquals("addon_problem", ((Component) get(screen, "notice")).getString());
        assertEquals(unknown, ((ClusterControlView) get(screen, "authority")).diagnostic());
    }
    @Test void memberWithoutProgramIsVisibleButCannotConfigureCommunication() throws Exception {
        var candidate = new ClusterControlView.Candidate(new BlockPos(1, 2, 3), Optional.empty(), List.of(), true, false, "no_program");
        var screen = screen(new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0), "MEMBERS", 320, 240);
        assertTrue(button(screen, "candidate").active, "A fresh touching Computer must be editable before its first installation");
        click(screen, "candidate");
        assertTrue(button(screen, "open_computer").active);
        assertFalse(button(screen, "apply").active, "Opening and communication membership are separate");
        assertContainedNonOverlapping(screen);
    }

    @Test void removingNameDoesNotRemoveComputerFromGeometricMemberPage() throws Exception {
        var id = UUID.randomUUID(); var pos = new BlockPos(1, 2, 3);
        var configuration = new ClusterConfiguration(Map.of("worker", new ClusterConfiguration.Member(id, pos)), Map.of());
        var view = new ClusterControlView(configuration,
            List.of(new ClusterControlView.Candidate(pos, Optional.of(id), List.of(), true, true, "ready")), "ready", 0, 0);
        var screen = screen(view, "MEMBERS", 320, 240);
        click(screen, "member"); click(screen, "remove_binding");
        assertTrue(((ClusterControlDraft)get(screen, "draft")).configuration().members().isEmpty());
        assertTrue(button(screen, "candidate").active);
    }
    @Test void unconfiguredTouchingComputerIsAlreadyListedOnDefaultMembersPage() throws Exception {
        var candidate = new ClusterControlView.Candidate(new BlockPos(1, 2, 3), Optional.of(UUID.randomUUID()), List.of(), true, true, "ready");
        var screen = screen(new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0), "MEMBERS", 320, 240);
        var row = button(screen, "candidate");
        assertTrue(row.active);
        assertTrue(((ClusterControlDraft)get(screen, "draft")).configuration().members().isEmpty(),
            "Displaying geometric membership must not silently save names or routes");
    }
    private Object oldClient;
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
            .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();field(net.minecraft.server.Bootstrap.class,"isBootstrapped").setBoolean(null,true);
    }
    @BeforeEach void client() throws Exception {
        oldClient=field(Minecraft.class,"instance").get(null);var client=allocate(Minecraft.class);set(client,"font",new Metrics());field(Minecraft.class,"instance").set(null,client);
    }
    @AfterEach void restore() throws Exception { field(Minecraft.class,"instance").set(null,oldClient); }

    @Test void connectionSelectorsRenderCurrentNamesAndUpdateTheirVisibleLabels() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "connections"); click(screen, "add_connection");
        assertVisibleSelections(screen, List.of("source", "target", "messages"));
        select(screen, 0, 1);
        assertVisibleSelections(screen, List.of("target", "target", "messages"));
        select(screen, 1, 0);
        assertVisibleSelections(screen, List.of("target", "source", "responses"));
    }

    @Test void publicPortSelectorsAlsoRenderTheirAdvertisedInputAndMember() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "ports"); click(screen, "add_port");
        assertVisibleSelections(screen, List.of("responses", "source"));
        select(screen, 1, 1);
        assertVisibleSelections(screen, List.of("messages", "target"));
    }

    private static void assertVisibleSelections(ClusterControllerScreen screen, List<String> expected) throws Exception {
        var selectors = screen.children().stream().filter(SelectionScrollInput.class::isInstance)
            .map(SelectionScrollInput.class::cast).toList();
        assertEquals(expected.size(), selectors.size());
        var renderables = (List<?>) get(screen, "renderables");
        for (int i = 0; i < selectors.size(); i++) {
            var selector = selectors.get(i);
            var label = (com.simibubi.create.foundation.gui.widget.Label) get(selector, "displayLabel");
            assertNotNull(label, "A scroll hotspot alone has no visible selection");
            assertTrue(renderables.contains(label), "Bound label must actually be rendered by the screen");
            assertTrue(label.visible);
            assertEquals(expected.get(i), label.text.getString());
            assertTrue(label.getX() >= selector.getX() && label.getY() >= selector.getY());
            assertTrue(label.getX() + label.getWidth() <= selector.getX() + selector.getWidth());
            assertTrue(label.getY() + label.getHeight() <= selector.getY() + selector.getHeight());
        }
    }

    @Test void connectionPageCreatesEditsAndRemovesNamedDirectedConnectionUsingRealWidgets() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "connections"); click(screen, "add_connection");
        var boxes = edits(screen); assertEquals(3, boxes.size());
        boxes.get(0).setValue("forward"); boxes.get(1).setValue("send");
        assertEquals("messages", boxes.get(2).getValue());
        click(screen, "apply");
        var config = ((ClusterControlDraft) get(screen, "draft")).configuration();
        assertEquals(new ClusterConfiguration.Connection("source", "send", "target", "messages", true), config.connections().get("forward"));
        click(screen, "connection_enabled");
        select(screen, 0, 1); select(screen, 1, 0);
        boxes = edits(screen); boxes.get(0).setValue("return"); boxes.get(1).setValue("reply");
        assertEquals("responses", boxes.get(2).getValue());
        click(screen, "apply");
        config = ((ClusterControlDraft) get(screen, "draft")).configuration();
        assertEquals(Set.of("return"), config.connections().keySet());
        assertEquals(new ClusterConfiguration.Connection("target", "reply", "source", "responses", true), config.connections().get("return"));
        click(screen, "connection_enabled"); click(screen, "remove");
        assertTrue(((ClusterControlDraft) get(screen, "draft")).configuration().connections().isEmpty());
        assertTrue((boolean) get(screen, "dirty"));
    }

    @Test void connectionEditorRejectsSelfRouteAtomicallyAndAllowsDisabledClosedInput() throws Exception {
        var initial = twoMemberView(); var screen = screen(initial, "MEMBERS", 320, 240);
        click(screen, "connections"); click(screen, "add_connection");
        var boxes = edits(screen); boxes.get(0).setValue("forward"); boxes.get(1).setValue("send");
        select(screen, 1, 0); click(screen, "apply");
        assertEquals(initial.configuration(), ((ClusterControlDraft) get(screen, "draft")).configuration());
        assertEquals("forward", edits(screen).get(0).getValue());
        select(screen, 1, 1); edits(screen).get(2).setValue("closed_input");
        click(screen, "enabled"); click(screen, "apply");
        assertEquals(new ClusterConfiguration.Connection("source", "send", "target", "closed_input", false),
            ((ClusterControlDraft) get(screen, "draft")).configuration().connections().get("forward"));
        click(screen, "connection_disabled");
        assertEquals("closed_input", edits(screen).get(2).getValue());
    }

    @Test void compactConnectionEditorKeepsEveryWidgetInsideAndNonOverlapping() throws Exception {
        var screen = screen(twoMemberView(), "MEMBERS", 320, 240);
        click(screen, "connections"); click(screen, "add_connection");
        assertContainedNonOverlapping(screen);
    }

    @Test void failedConnectionSavePreservesLocalConnectionWhileUpdatingAuthority() throws Exception {
        var initial = twoMemberView(); var screen = screen(initial, "MEMBERS", 320, 240);
        var local = new ClusterConfiguration(initial.configuration().members(), Map.of(),
            Map.of("forward", new ClusterConfiguration.Connection("source", "send", "target", "messages", true)));
        set(screen, "draft", new ClusterControlDraft(local)); set(screen, "dirty", true); set(screen, "waiting", true); setEnum(screen, "pending", "SAVE");
        deliver(screen, new ClusterControlView(initial.configuration(), initial.candidates(), "endpoint_changed", 0, 0), "endpoint_changed");
        screen.containerTick();
        assertEquals(local, ((ClusterControlDraft) get(screen, "draft")).configuration());
        assertTrue((boolean) get(screen, "dirty"));
        assertEquals("endpoint_changed", ((ClusterControlView) get(screen, "authority")).diagnostic());
    }

    @Test void newPortUsesDisplayedFirstAdvertisedInputWithoutRequiringScrollGesture() throws Exception {
        UUID id=UUID.randomUUID();var member=new ClusterConfiguration.Member(id,new BlockPos(1,2,3));
        var view=new ClusterControlView(new ClusterConfiguration(Map.of("worker",member),Map.of()),List.of(
            new ClusterControlView.Candidate(member.position(),Optional.of(id),List.of("messages"), true, true, "ready")),"configured",0,0);
        var screen=screen(view,"PORT",320,240);click(screen,"cancel");click(screen,"ports");click(screen,"add_port");
        var boxes=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
        assertEquals(3,boxes.size());boxes.get(0).setValue("public");boxes.get(1).setValue("reply");boxes.get(2).setValue("outside");
        assertEquals("messages",get(screen,"inputText"));
        click(screen,"apply");
        var config=((ClusterControlDraft)get(screen,"draft")).configuration();
        assertEquals(new ClusterConfiguration.Port("worker","messages","reply","outside",true),config.ports().get("public"));
    }

    @Test void nonContactingCandidateStaysVisibleWithDiagnosticButCannotBeClicked() throws Exception {
        var candidate=new ClusterControlView.Candidate(new BlockPos(4,5,6),Optional.of(UUID.randomUUID()),List.of("in"), false, false, "not_contacting");
        var screen=screen(new ClusterControlView(ClusterConfiguration.empty(),List.of(candidate),"configured",0,0),"CANDIDATES",320,240);
        var row=button(screen,"candidate");assertFalse(row.active);
        var args=((TranslatableContents)row.getMessage().getContents()).getArgs();
        assertInstanceOf(Component.class,args[3]);
        assertEquals("create_biotech.biocomputing.cluster.code.not_contacting",((TranslatableContents)((Component)args[3]).getContents()).getKey());
    }

    @Test void compactPortEditorKeepsEveryRealWidgetInsideAndNonOverlapping() throws Exception {
        UUID id=UUID.randomUUID();var member=new ClusterConfiguration.Member(id,BlockPos.ZERO);
        var view=new ClusterControlView(new ClusterConfiguration(Map.of("worker",member),Map.of()),List.of(
            new ClusterControlView.Candidate(BlockPos.ZERO,Optional.of(id),List.of("in"), true, true, "ready")),"configured",0,0);
        var screen=screen(view,"PORT",320,240);var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        int left=(int)get(screen,"leftPos"),top=(int)get(screen,"topPos"),width=(int)get(screen,"imageWidth"),height=(int)get(screen,"imageHeight");
        for(var widget:widgets){assertTrue(widget.getX()>=left&&widget.getY()>=top);assertTrue(widget.getX()+widget.getWidth()<=left+width&&widget.getY()+widget.getHeight()<=top+height);}
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);
            assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY(),a.getMessage().getString()+" overlaps "+b.getMessage().getString());}
    }

    @Test void failedSaveRefreshesAuthorityButPreservesDirtyDraft() throws Exception {
        UUID id=UUID.randomUUID();var local=new ClusterConfiguration(Map.of("local",new ClusterConfiguration.Member(id,BlockPos.ZERO)),Map.of());
        var initial=new ClusterControlView(ClusterConfiguration.empty(),List.of(),"ready",0,0);var screen=screen(initial,"MEMBERS",320,240);
        set(screen,"draft",new ClusterControlDraft(local));set(screen,"dirty",true);set(screen,"waiting",true);setEnum(screen,"pending","SAVE");
        deliver(screen,new ClusterControlView(ClusterConfiguration.empty(),List.of(),"busy",2,1),"busy");screen.containerTick();
        assertEquals(local,((ClusterControlDraft)get(screen,"draft")).configuration());assertTrue((boolean)get(screen,"dirty"));
        assertEquals("busy",((ClusterControlView)get(screen,"authority")).diagnostic());
    }

    @Test void successfulSaveResponseCannotOverwriteEditsMadeAfterItsDraftFence() throws Exception {
        UUID id=UUID.randomUUID();var edited=new ClusterConfiguration(Map.of("edited",new ClusterConfiguration.Member(id,BlockPos.ZERO)),Map.of());
        var screen=screen(new ClusterControlView(ClusterConfiguration.empty(),List.of(),"ready",0,0),"MEMBERS",320,240);
        set(screen,"draft",new ClusterControlDraft(edited));set(screen,"dirty",true);set(screen,"waiting",true);setEnum(screen,"pending","SAVE");
        set(screen,"pendingDraftVersion",1L);set(screen,"draftVersion",2L);
        deliver(screen,new ClusterControlView(ClusterConfiguration.empty(),List.of(),"configured",0,0),"configured");screen.containerTick();
        assertEquals(edited,((ClusterControlDraft)get(screen,"draft")).configuration());assertTrue((boolean)get(screen,"dirty"));
    }

    private static ClusterControllerScreen screen(ClusterControlView view,String panel,int width,int height)throws Exception{
        var screen=allocate(ClusterControllerScreen.class);var menu=allocate(ClusterControllerMenu.class);
        set(menu,"response",Optional.empty());set(menu,"view",Optional.empty());set(screen,"menu",menu);set(screen,"draft",new ClusterControlDraft(view.configuration()));set(screen,"authority",view);
        Class<?> panelType=field(ClusterControllerScreen.class,"panel").getType();set(screen,"panel",Enum.valueOf((Class)panelType,panel));set(screen,"notice",Component.empty());
        set(screen,"memberText","");set(screen,"publicText","");set(screen,"inputText","");set(screen,"outputText","");set(screen,"externalText","");set(screen,"enabled",true);
        set(screen,"requested",true);set(screen,"seeded",true);set(screen,"title",Component.literal("Cluster"));set(screen,"playerInventoryTitle",Component.empty());
        set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());set(screen,"width",width);set(screen,"height",height);
        set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);screen.init();return screen;
    }
    private static ClusterControlView twoMemberView() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        members.put("source", new ClusterConfiguration.Member(a, BlockPos.ZERO));
        members.put("target", new ClusterConfiguration.Member(b, new BlockPos(1, 0, 0)));
        return new ClusterControlView(new ClusterConfiguration(members, Map.of()), List.of(
            new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(a), List.of("responses"), true, true, "ready"),
            new ClusterControlView.Candidate(new BlockPos(1, 0, 0), Optional.of(b), List.of("messages"), true, true, "ready")), "configured", 0, 0);
    }
    private static List<EditBox> edits(ClusterControllerScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }
    private static void select(ClusterControllerScreen screen, int index, int value) {
        var control = screen.children().stream().filter(SelectionScrollInput.class::isInstance).map(SelectionScrollInput.class::cast).toList().get(index);
        control.setState(value); control.onChanged();
    }
    private static void assertContainedNonOverlapping(ClusterControllerScreen screen) throws Exception {
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        int left = (int) get(screen, "leftPos"), top = (int) get(screen, "topPos"), width = (int) get(screen, "imageWidth"), height = (int) get(screen, "imageHeight");
        for (var widget : widgets) {
            assertTrue(widget.getX() >= left && widget.getY() >= top);
            assertTrue(widget.getX() + widget.getWidth() <= left + width && widget.getY() + widget.getHeight() <= top + height);
        }
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var b = widgets.get(j);
            assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY(), a.getMessage().getString() + " overlaps " + b.getMessage().getString());
        }
    }
    private static void click(ClusterControllerScreen screen,String key){button(screen,key).onClick(0,0);}
    private static void deliver(ClusterControllerScreen screen,ClusterControlView view,String code)throws Exception{deliver(screen,view,code,"");}
    private static void deliver(ClusterControllerScreen screen,ClusterControlView view,String code,String detail)throws Exception{var menu=(ClusterControllerMenu)get(screen,"menu");
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(256,8192,32768);UUID nonce=UUID.randomUUID();
        set(menu,"latestIntent",1L);set(menu,"response",Optional.of(new ClusterControlResponse(1,nonce,1,1,code,detail,limits,ClusterControlWire.encodeView(view,limits))));set(menu,"view",Optional.of(view));set(menu,"responseVersion",1L);}
    private static void setEnum(Object target,String fieldName,String value)throws Exception{Class<?>type=field(target.getClass(),fieldName).getType();set(target,fieldName,Enum.valueOf((Class)type,value));}
    private static CreateTextButton button(ClusterControllerScreen screen,String key){return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
        .filter(value->value.getMessage().getContents()instanceof TranslatableContents text&&text.getKey().equals("create_biotech.biocomputing.cluster."+key)).findFirst().orElseThrow();}
    private static Object get(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}
    private static Field field(Class<?>type,String name)throws Exception{for(Class<?>current=type;current!=null;current=current.getSuperclass())try{var field=current.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    @SuppressWarnings("removal")private static<T>T allocate(Class<T>type)throws Exception{return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null)).allocateInstance(type));}
    private static final class Metrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor{
        Metrics(){super(id->null,false);}@Override public java.util.function.Function<ResourceLocation,net.minecraft.client.gui.font.FontSet> create$getFonts(){return id->null;}
        @Override public int width(String value){return value.length();}@Override public int width(FormattedText value){return value.getString().length();}
        @Override public String plainSubstrByWidth(String value,int width){return value.substring(0,Math.clamp(width,0,value.length()));}
        @Override public String plainSubstrByWidth(String value,int width,boolean reverse){return reverse?value.substring(Math.max(0,value.length()-width)):plainSubstrByWidth(value,width);}
    }
}
