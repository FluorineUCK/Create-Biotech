package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Debugger for the installed program; local editor drafts are never submitted from this screen. */
public final class ComputerExecutionScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    static final int MONITOR_INTERVAL = 10; // Client observation cadence, never execution cycles.
    private static final int MONITOR_REPLY_TIMEOUT = 80; // Only re-reads status; never retries a control action.
    private enum MonitorScope {
        PRIVATE("locals", "monitor_private"), PUBLIC("memory", "monitor_public");
        final String field, label;
        MonitorScope(String field, String label) { this.field = field; this.label = label; }
    }
    private record MonitorKey(MonitorScope scope, String name) {}
    private boolean monitorPanel, controlPending, selectedMonitorsOnly;
    private int monitorPage, monitorDelay, replyTicks;
    private Long monitorRevision;
    private Set<MonitorKey> programWatches = Set.of();
    private final Set<MonitorKey> watches = new LinkedHashSet<>();
    private List<CreateTextButton> monitorRows;
    private CreateTextButton monitorPrevious, monitorNext, monitorInspect;
    private StringWidget monitorHint;
    private DataValue.RecordValue monitorInspection;
    private List<MonitorKey> monitorKeys = List.of();
    private static final EditorValueFormat.Kind[] KINDS = EditorValueFormat.Kind.values();
    private static final int MAX_LITERAL_CHARS = 8192;
    private static final int MAX_VARIABLE_PREVIEW_CODE_POINTS = 80;
    private final net.minecraft.client.gui.screens.Screen parent;
    private final Inventory editorInventory;
    private final java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> navigator;
    private final java.util.function.BiConsumer<ComputerControlSession.Action, DataValue.RecordValue> controlSender;
    private final LinkedHashMap<String, DataValue> inputs = new LinkedHashMap<>();
    private String nameText = "", valueText = "", selectedName;
    private int kind, inputPage, statusPage, observationTab;
    private List<String> statusLines = List.of();
    private CreateTextButton statusNext, inspectPrivate, inspectPublic, inspectTasks;
    private List<CreateTextButton> inputButtons;
    private CreateTextButton inputPrevious, inputNext;
    private boolean inputsRestored;
    private static final int CONTENT_TOP = 78;
    private boolean inputPanel, restartConfirmation, maintenanceReset, resourceReset;
    private Component restartExplanation;
    private int rightX, rightWidth;
    private CreateTextButton mainControl, stepControl, resetControl, statusPrevious;
    private ComputerControlSession.Action primaryAction, stepAction;
    private EditBox nameBox, valueBox;
    private boolean requested, hasObservedExecution;
    private DataValue.RecordValue executionStatus;
    private long executionRevision;
    private ProgramEditorResponse describedSource;
    private ScratchDebugSource installedDescription;
    private boolean flow;
    private CreateTextButton periodic;
    private long responseVersion, sourceVersion;
    private Component notice = text("loading");
    private StringWidget noticeLabel, statusPageLabel;

    public ComputerExecutionScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
        net.minecraft.client.gui.screens.Screen parent) {
        this(menu, inventory, title, parent, menu::control);
    }
    ComputerExecutionScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
        net.minecraft.client.gui.screens.Screen parent,
        java.util.function.BiConsumer<ComputerControlSession.Action, DataValue.RecordValue> controlSender) {
        super(menu, inventory, title);
        this.controlSender = Objects.requireNonNull(controlSender);
        this.navigator = screen -> minecraft.setScreen(screen);
        this.parent = Objects.requireNonNull(parent);
        if (!(parent instanceof ProgramSourceEditor)) throw new IllegalArgumentException("Debugger needs a source editor");
        this.editorInventory = inventory;
    }

    @Override protected void init() {
        if (!inputsRestored) { inputs.putAll(menu.debuggerInputs().fields()); inputsRestored = true; }
        var focused = getFocused();
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12));
        super.init();
        clearWidgets();
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.DEBUG));
        var header = addRenderableWidget(new StringWidget(leftPos + 10, topPos + 10, imageWidth - 90, 12,
            text("installed_program", title), font).alignLeft().setColor(0xffeeeeee));
        header.setTooltip(Tooltip.create(header.getMessage()));
        int leftWidth = Math.min(148, (imageWidth - 28) * 45 / 100);
        rightX = leftWidth + 16; rightWidth = imageWidth - rightX - 8;
        if (restartConfirmation) {
            noticeLabel = null;
            button(8, imageHeight - 28, (imageWidth - 20) / 2, text("cancel_restart"), () -> { restartConfirmation = false; init(); });
            button(12 + (imageWidth - 20) / 2, imageHeight - 28, (imageWidth - 20) / 2, text("confirm_restart"), () -> {
                restartConfirmation = false; init(); send(ComputerControlSession.Action.RESET);
            });
        } else if (monitorPanel) {
            noticeLabel = null; initMonitors();
        } else if (inputPanel) {
            initInputs();
            if (focused == nameBox || focused == valueBox) setFocused(focused);
        } else {
            setFocused(null);
            mainControl = button(8, 30, leftWidth, text("start"), () -> { if (primaryAction != null) send(primaryAction); });
            stepControl = button(8, 52, (leftWidth - 4) / 2, text("step"), () -> { if (stepAction != null) send(stepAction); });
            resetControl = button(12 + (leftWidth - 4) / 2, 52, leftWidth - 4 - (leftWidth - 4) / 2,
                text("restart"), () -> { restartExplanation = text(resourceReset ? "restart_resource_hint" : maintenanceReset ? "restart_maintenance_hint" : flow ? "restart_flow_hint" : "restart_compat_hint"); restartConfirmation = true; init(); });
            button(8, 74, leftWidth, text("inputs"), () -> { inputPanel = true; init(); });
            button(8, 96, leftWidth, text("read_peripherals"), () -> send(ComputerControlSession.Action.READ_PERIPHERALS));
            button(8, 118, leftWidth, text("read_network"), () -> send(ComputerControlSession.Action.READ_NETWORK))
                .setTooltip(Tooltip.create(text("read_network_hint")));
            button(8, 140, leftWidth, text("communication"), () -> {
                menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
                navigator.accept(new ComputerCommunicationScreen(menu, editorInventory, title, this));
            });
            periodic = button(8, 162, leftWidth, text("periodic"), () -> {
                menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
                navigator.accept(new ComputerPeriodicScreen(menu, editorInventory, title, this));
            });
            button(8, 184, leftWidth, com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventScreen.title(), () -> {
                menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
                navigator.accept(new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventScreen(menu, editorInventory,
                    () -> { send(ComputerControlSession.Action.REFRESH); navigator.accept(this); }));
            });
            button(8, imageHeight - 22, leftWidth, text("refresh"), () -> send(ComputerControlSession.Action.REFRESH));
            button(imageWidth - 76, imageHeight - 22, 68, text("back"), this::onClose);
            int tabWidth = (rightWidth - 4) / 3;
            button(rightX, 30, tabWidth, text("overview"), () -> { observationTab = 0; statusPage = 0; init(); });
            button(rightX + tabWidth + 2, 30, tabWidth, text("variables"), () -> { observationTab = 1; statusPage = 0; init(); });
            button(rightX + 2 * (tabWidth + 2), 30, rightWidth - 2 * (tabWidth + 2), text("advanced"), () -> { observationTab = 2; statusPage = 0; init(); });
            if (observationTab == 1) {
                inspectPrivate = button(rightX, CONTENT_TOP, (rightWidth - 4) / 2, text("inspect_private"), () -> inspect(variableGroup("locals")));
                int half = (rightWidth - 4) / 2;
                button(rightX, CONTENT_TOP + 22, half, text("open_monitors"), this::openMonitors);
                inspectPublic = button(rightX + half + 4, CONTENT_TOP, rightWidth - half - 4, text("inspect_public"), () -> inspect(variableGroup("memory")));
                inspectTasks = button(rightX + half + 4, CONTENT_TOP + 22, rightWidth - half - 4, text("task_debug"), () -> {
                    menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
                    navigator.accept(new ComputerTaskDebugScreen(menu, editorInventory, navigator,
                        () -> { send(ComputerControlSession.Action.REFRESH); navigator.accept(this); }));
                });
            } else { inspectPrivate = null; inspectPublic = null; inspectTasks = null; }
            button(rightX, imageHeight - 46, rightWidth, text("inspect_snapshot"), () -> inspect(inspectionValues()));
            noticeLabel = addRenderableWidget(new StringWidget(leftPos + rightX, topPos + 56, rightWidth, 12, notice, font).alignLeft().setColor(0xffffddaa));
            noticeLabel.setTooltip(Tooltip.create(notice));
            statusPrevious = button(rightX, imageHeight - 22, 28, Component.literal("<"), () -> { statusPage--; refreshStatus(); });
            statusNext = button(rightX + 32, imageHeight - 22, 28, Component.literal(">"), () -> { statusPage++; refreshStatus(); });
            statusPageLabel = addRenderableWidget(new StringWidget(leftPos + rightX + 64, topPos + imageHeight - 17,
                Math.max(1, rightWidth - 140), 12, Component.empty(), font).alignLeft().setColor(0xffaaccee));
            refreshStatus(); refreshActions();
        }
    }
    private void initMonitors() {
        setFocused(null);
        int rows = Math.max(1, (imageHeight - 100) / 22);
        monitorRows = new ArrayList<>(rows);
        monitorHint = addRenderableWidget(new StringWidget(leftPos + 8, topPos + 33, imageWidth - 16, 12,
            text("monitor_hint"), font).alignLeft().setColor(0xffddcba9));
        monitorHint.setTooltip(Tooltip.create(monitorHint.getMessage()));
        int half = (imageWidth - 20) / 2;
        var filter = button(8, 49, half, monitorFilterLabel(), () -> {
            selectedMonitorsOnly = !selectedMonitorsOnly;
            monitorPage = 0;
            init();
        });
        filter.setTooltip(Tooltip.create(text("monitor_filter_hint")));
        monitorInspect = button(12 + half, 49, imageWidth - half - 20, text("monitor_inspect"), () -> inspect(monitorInspection));
        for (int i = 0; i < rows; i++) {
            int row = i;
            monitorRows.add(button(8, 71 + i * 22, imageWidth - 16, Component.empty(), () -> toggleMonitor(row)));
        }
        monitorPrevious = button(8, imageHeight - 24, 28, Component.literal("<"), () -> { monitorPage--; refreshMonitors(); });
        monitorNext = button(40, imageHeight - 24, 28, Component.literal(">"), () -> { monitorPage++; refreshMonitors(); });
        button(76, imageHeight - 24, 92, text("refresh"), () -> send(ComputerControlSession.Action.REFRESH));
        button(imageWidth - 90, imageHeight - 24, 82, text("close_monitors"), this::closeMonitors);
        refreshMonitors();
    }
    private Component monitorFilterLabel() { return text(selectedMonitorsOnly ? "monitor_all" : "monitor_selected_only"); }
    private void openMonitors() {
        monitorPanel = true; init();
        if (!controlPending && menu.status().filter(status -> status.fields().containsKey("phase")).isEmpty())
            send(ComputerControlSession.Action.REFRESH);
    }
    private void closeMonitors() { monitorPanel = false; monitorDelay = 0; init(); }
    private void refreshMonitors() {
        var reply = menu.controlResponse();
        if (reply.isPresent()) {
            long revision = reply.orElseThrow().revision();
            if (monitorRevision != null && monitorRevision != revision) { watches.clear(); monitorPage = 0; }
            monitorRevision = revision;
        }
        var status = reply.filter(answer -> answer.code().equals("ok")).flatMap(ignored -> menu.status())
            .filter(value -> value.fields().get("phase") instanceof DataValue.TextValue
                && !new DataValue.BooleanValue(false).equals(value.fields().get("available")));
        var found = new LinkedHashMap<MonitorKey, DataValue>();
        status.ifPresent(values -> {
            for (var scope : MonitorScope.values()) if (values.fields().get(scope.field) instanceof DataValue.RecordValue record)
                new TreeMap<>(record.fields()).forEach((name, value) -> found.put(new MonitorKey(scope, name), value));
        });
        var scriptWatches = new LinkedHashSet<MonitorKey>();
        status.ifPresent(values -> {
            addProgramWatches(scriptWatches, values.fields().get("program_monitors"), MonitorScope.PUBLIC);
            addProgramWatches(scriptWatches, values.fields().get("private_monitors"), MonitorScope.PRIVATE);
        });
        programWatches = scriptWatches;
        var keys = new LinkedHashSet<>(watches);
        keys.addAll(programWatches);
        if (!selectedMonitorsOnly) keys.addAll(found.keySet());
        var all = List.copyOf(keys);
        monitorInspection = inspectedMonitors(found);
        monitorInspect.active = !monitorInspection.fields().isEmpty();
        int size = monitorRows.size(), pages = Math.max(1, Math.ceilDiv(all.size(), size));
        monitorPage = Math.clamp(monitorPage, 0, pages - 1);
        var hint = text(status.isEmpty() ? "monitor_no_snapshot"
            : keys.isEmpty() ? selectedMonitorsOnly ? "monitor_selected_empty" : "monitor_empty" : "monitor_hint");
        monitorHint.setMessage(hint); monitorHint.setTooltip(Tooltip.create(hint));
        for (int i = 0; i < size; i++) {
            var button = monitorRows.get(i);
            int index = monitorPage * size + i;
            button.active = button.visible = index < all.size();
            if (!button.visible) continue;
            var key = all.get(index);
            var value = found.get(key);
            var display = status.isEmpty() ? text("monitor_no_snapshot") : value == null ? text("monitor_missing") : monitorValue(value);
            var mark = watches.contains(key) ? "[x]" : "[ ]";
            var caption = text(key.scope().label, mark, key.name(), display);
            if (programWatches.contains(key)) caption = text("monitor_program", caption);
            button.setMessage(caption);
            button.setTooltip(Tooltip.create(caption));
        }
        monitorPrevious.visible = monitorNext.visible = pages > 1;
        monitorPrevious.active = monitorPage > 0; monitorNext.active = monitorPage + 1 < pages;
        monitorKeys = all;
    }
    private static void addProgramWatches(Set<MonitorKey> keys, DataValue value, MonitorScope scope) {
        if (value instanceof DataValue.ListValue list)
            for (var item : list.values()) if (item instanceof DataValue.TextValue name) keys.add(new MonitorKey(scope, name.value()));
    }
    private DataValue.RecordValue inspectedMonitors(Map<MonitorKey, DataValue> found) {
        var groups = new LinkedHashMap<String, DataValue>();
        for (var scope : MonitorScope.values()) {
            var values = new LinkedHashMap<String, DataValue>();
            found.forEach((key, value) -> {
                if (key.scope() == scope && (watches.contains(key) || programWatches.contains(key))) values.put(key.name(), value);
            });
            if (!values.isEmpty()) groups.put(scope.field, new DataValue.RecordValue(values));
        }
        // Give a single selected scope a direct path to the values. Never flatten names from both scopes together.
        return groups.size() == 1 ? (DataValue.RecordValue)groups.values().iterator().next() : new DataValue.RecordValue(groups);
    }
    private void toggleMonitor(int row) {
        int index = monitorPage * monitorRows.size() + row;
        if (index >= monitorKeys.size()) return;
        var key = monitorKeys.get(index);
        if (!watches.remove(key)) watches.add(key);
        refreshMonitors();
    }
    private static Component monitorValue(DataValue value) {
        if (value instanceof DataValue.ListValue list) return text("monitor_list", list.values().size());
        if (value instanceof DataValue.RecordValue record) return text("monitor_record", record.fields().size());
        if (value instanceof DataValue.MapValue map) return text("monitor_record", map.entries().size());
        if (value instanceof DataValue.NbtValue nbt) return Component.literal("NBT #" + nbt.tagId());
        if (value instanceof DataValue.ErrorValue error) return Component.literal(error.code());
        if (value instanceof DataValue.TextValue string) {
            var raw = string.value();
            int end = raw.offsetByCodePoints(0, Math.min(raw.codePointCount(0, Math.min(raw.length(), 81)), 80));
            return Component.literal(raw.substring(0, end).replace("\n", "\\n").replace("\r", "\\r") + (end < raw.length() ? "…" : ""));
        }
        return Component.literal(ComputerValueDisplay.lines(value, Integer.MAX_VALUE).getFirst());
    }
    private boolean programCanSetMonitors() {
        return menu.status().filter(status -> status.fields().get("program_monitors") instanceof DataValue.ListValue
            && !new DataValue.BooleanValue(true).equals(status.fields().get("paused"))).isPresent();
    }
    private void initInputs() {
        nameBox = field(nameBox, 8, 32, imageWidth - 90, "input_name", nameText, value -> nameText = value);
        button(imageWidth - 78, 31, 70, Component.literal(KINDS[kind].name()), () -> { kind = (kind + 1) % KINDS.length; init(); });
        valueBox = field(valueBox, 8, 56, imageWidth - 16, "input_value", valueText, value -> valueText = value);
        int third = (imageWidth - 24) / 3;
        button(8, 80, third, text("save"), this::save);
        button(12 + third, 80, third, text("remove"), this::remove);
        button(16 + 2 * third, 80, third, text("browse_fields"), this::browseFields);
        noticeLabel = addRenderableWidget(new StringWidget(leftPos + 8, topPos + 104, imageWidth - 16, 12, notice, font).alignLeft().setColor(0xffffddaa));
        noticeLabel.setTooltip(Tooltip.create(notice));
        int rows = Math.max(1, (imageHeight - 146) / 22);
        inputButtons = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            final int index = row;
            inputButtons.add(button(8, 120 + row * 22, imageWidth - 16, Component.empty(), () -> {
                var page = ComputerValueDisplay.page(new ArrayList<>(inputs.keySet()), inputPage, inputButtons.size());
                if (index < page.lines().size()) select(page.lines().get(index));
            }));
        }
        inputPrevious = button(8, imageHeight - 22, 28, Component.literal("<"), () -> { inputPage--; refreshInputButtons(); });
        inputNext = button(40, imageHeight - 22, 28, Component.literal(">"), () -> { inputPage++; refreshInputButtons(); });
        button(76, imageHeight - 22, 72, text("refresh"), () -> send(ComputerControlSession.Action.REFRESH));
        button(imageWidth - 90, imageHeight - 22, 82, text("close_inputs"), () -> { inputPanel = false; init(); });
        refreshInputButtons();
    }
    private void refreshStatus() {
        statusLines = statusLines();
        var page = ComputerValueDisplay.page(statusLines, statusPage, statusPageSize()); statusPage = page.index();
        if (inspectPrivate != null) inspectPrivate.active = variableGroup("locals").fields().size() > 0;
        if (inspectPublic != null) inspectPublic.active = variableGroup("memory").fields().size() > 0;
        if (statusPageLabel != null) {
            statusPageLabel.visible = page.count() > 1;
            statusPageLabel.setMessage(Component.literal((page.index() + 1) + "/" + page.count()));
            statusPageLabel.setTooltip(Tooltip.create(statusPageLabel.getMessage()));
        }
        if (statusPrevious != null) { statusPrevious.active = statusPage > 0; statusPrevious.visible = page.count() > 1; }
        if (statusNext != null) { statusNext.active = statusPage + 1 < page.count(); statusNext.visible = page.count() > 1; }
    }

    private EditBox field(EditBox existing, int x, int y, int width, String key, String value, java.util.function.Consumer<String> responder) {
        var box = existing == null ? new EditBox(font, leftPos + x, topPos + y, width, 18, Component.empty()) : existing;
        box.setPosition(leftPos + x, topPos + y); box.setWidth(width); addRenderableWidget(box);
        box.setMaxLength(MAX_LITERAL_CHARS); box.setValue(value); box.setHint(text(key)); box.setResponder(responder); return box;
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        var result = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
        result.setTooltip(Tooltip.create(label)); return result;
    }
    private void select(String name) {
        DataValue selected = inputs.get(name);
        try {
            var selectedKind = switch (selected) {
                case DataValue.IntegerValue ignored -> EditorValueFormat.Kind.INTEGER;
                case DataValue.DecimalValue ignored -> EditorValueFormat.Kind.DECIMAL;
                case DataValue.TextValue ignored -> EditorValueFormat.Kind.TEXT;
                case DataValue.BooleanValue ignored -> EditorValueFormat.Kind.BOOLEAN;
                case DataValue.NbtValue ignored -> EditorValueFormat.Kind.NBT;
                default -> EditorValueFormat.Kind.JSON;
            };
            String literal = selected instanceof DataValue.TextValue value ? value.value() : EditorValueFormat.format(selected);
            if (literal.length() > MAX_LITERAL_CHARS || name.length() > MAX_LITERAL_CHARS)
                throw new IllegalArgumentException("Value is too large for literal editing");
            // Compute the lossless representation first, so read-only values never replace an in-progress edit.
            selectedName = name; nameText = name; kind = selectedKind.ordinal(); valueText = literal;
            init();
        } catch (IllegalArgumentException readOnly) {
            setNotice(text("read_only", name));
        }
    }
    private void save() {
        try {
            if (nameText.isBlank()) throw new IllegalArgumentException("name required");
            DataValue parsed = EditorValueFormat.parse(KINDS[kind], valueText);
            if (selectedName != null && !selectedName.equals(nameText)) inputs.remove(selectedName);
            inputs.put(nameText, parsed); selectedName = nameText;
            menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
            setNotice(text("saved")); init();
        } catch (IllegalArgumentException error) { setNotice(text("invalid", error.getMessage())); }
    }
    private void remove() { if (selectedName != null) inputs.remove(selectedName); else inputs.remove(nameText); selectedName = null; nameText = valueText = ""; menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs)); init(); }
    private void send(ComputerControlSession.Action action) { controlPending = true; replyTicks = 0; controlSender.accept(action, new DataValue.RecordValue(inputs)); setNotice(text("waiting")); }
    private void setNotice(Component value) {
        notice = value;
        if (noticeLabel != null) {
            noticeLabel.setMessage(value);
            noticeLabel.setTooltip(Tooltip.create(value));
        }
    }
    private void browseFields() {
        var frozen = new DataValue.RecordValue(inputs);
        var editor = (ProgramSourceEditor)parent;
        navigator.accept(new ComputerValueTreeScreen<>(menu, editorInventory, title, this, frozen, path -> {
            editor.importValuePath(frozen, path);
            navigator.accept(parent);
        }, editor.valuePathActionTitle(), Component.translatable("create_biotech.biocomputing.tree.frozen"), null));
    }

    /** Called only on explicit return from settings; ordinary replies never replace local input edits. */
    void restoreLocalInputs() {
        inputs.clear(); inputs.putAll(menu.debuggerInputs().fields());
        inputsRestored = true;
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; send(ComputerControlSession.Action.REFRESH); }
        if (monitorPanel) {
            if (responseVersion != menu.controlResponseVersion()) {
                controlPending = false; monitorDelay = MONITOR_INTERVAL;
                refreshMonitors();
            } else if (controlPending && ++replyTicks >= MONITOR_REPLY_TIMEOUT) {
                controlPending = false; monitorDelay = MONITOR_INTERVAL;
            } else if (!controlPending && (!watches.isEmpty() || programCanSetMonitors()) && --monitorDelay <= 0) {
                controlPending = true; replyTicks = 0;
                controlSender.accept(ComputerControlSession.Action.REFRESH, new DataValue.RecordValue(Map.of()));
            }
        }
        executionSnapshot().filter(value -> value.fields().get("source") instanceof DataValue.TextValue)
            .ifPresent(value -> menu.requestInstalledSource(executionRevision));
        if (sourceVersion != menu.installedSourceVersion()) {
            sourceVersion = menu.installedSourceVersion();
            if (!inputPanel && !restartConfirmation && !monitorPanel) refreshStatus();
        }
        if (responseVersion == menu.controlResponseVersion()) return;
        responseVersion = menu.controlResponseVersion();
        controlPending = false;
        menu.controlResponse().ifPresent(reply -> setNotice(text("result." + reply.code(), reply.detail())));
        refreshActions();
        menu.capturedInputs().ifPresent(captured -> {
            // The menu admits only the root belonging to the latest successful capture action.
            inputs.putAll(captured.fields());
            menu.rememberDebuggerInputs(new DataValue.RecordValue(inputs));
            refreshInputButtons();
        });
        if (!inputPanel && !monitorPanel) refreshStatus();
    }
    private int displayTop() { return CONTENT_TOP + (observationTab == 1 ? 48 : 0); }
    private int statusPageSize() { return Math.max(1, (imageHeight - displayTop() - 50) / 12); }
    private void refreshActions() {
        var status = executionSnapshot();
        flow = status.map(value -> new DataValue.TextValue("flow").equals(value.fields().get("mode"))).orElse(flow);
        if (inspectTasks != null) inspectTasks.active = flow && status.filter(value ->
            !new DataValue.BooleanValue(false).equals(value.fields().get("available"))
                && !new DataValue.TextValue("UNAVAILABLE").equals(value.fields().get("phase"))).isPresent();
        if (periodic != null) periodic.active = !flow;
        if (mainControl == null) return;
        primaryAction = null; stepAction = null; maintenanceReset = false; resourceReset = false;
        String label = "unavailable";
        boolean restart = false, backgroundScheduling = false;
        if (status.isPresent()) {
            var fields = status.orElseThrow().fields();
            String phase = ((DataValue.TextValue) fields.get("phase")).value();
            boolean available = !new DataValue.BooleanValue(false).equals(fields.get("available"));
            boolean paused = new DataValue.BooleanValue(true).equals(fields.get("paused"));
            long work = fields.get("work") instanceof DataValue.IntegerValue value ? value.value() : 0;
            maintenanceReset = !available && maintainableFlowMismatch(fields, phase);
            resourceReset = !available && flow && new DataValue.BooleanValue(true).equals(fields.get("resource_reset_available"))
                && fields.get("diagnostic") instanceof DataValue.RecordValue diagnostic
                && new DataValue.TextValue("RESOURCE_LIMIT").equals(diagnostic.fields().get("code"));
            restart = available && !phase.equals("UNAVAILABLE") || maintenanceReset || resourceReset;
            if (!available) label = "unavailable";
            else if (phase.equals("UNAVAILABLE")) label = "not_installed";
            else if (terminalPhase(phase)) {
                // A terminal main result says nothing about remaining owner tasks, sends or periodic work.
                label = paused ? "resume_scheduling" : "pause_scheduling";
                primaryAction = paused ? ComputerControlSession.Action.RESUME : ComputerControlSession.Action.PAUSE;
                stepAction = ComputerControlSession.Action.STEP; backgroundScheduling = true;
            }
            else if (!flow && phase.equals("IDLE")) {
                label = "start"; primaryAction = ComputerControlSession.Action.RUN;
                stepAction = ComputerControlSession.Action.START_PAUSED;
            } else if (flow && phase.equals("READY") && work == 0 && paused) {
                label = "start"; primaryAction = ComputerControlSession.Action.RESUME; stepAction = ComputerControlSession.Action.STEP;
            } else if (Set.of("READY", "COMPUTING", "RUNNING", "WAITING_READ", "WAITING_WRITE", "WAITING_READABLE", "WAITING_TASK", "WAITING_ANSWER", "SLEEPING").contains(phase)) {
                label = paused ? "resume" : "pause";
                primaryAction = paused ? ComputerControlSession.Action.RESUME : ComputerControlSession.Action.PAUSE;
                stepAction = paused ? ComputerControlSession.Action.STEP : null;
            }
        } else if (!hasObservedExecution && menu.controlResponse().map(reply -> reply.code().equals("ok")).orElse(true)
                && menu.response().isPresent() && menu.response().orElseThrow().code().equals("ready")
                && menu.response().orElseThrow().archive().length == 0) label = "not_installed";
        mainControl.setMessage(text(label)); mainControl.active = primaryAction != null;
        mainControl.setTooltip(Tooltip.create(text("control_" + label)));
        stepControl.active = stepAction != null;
        stepControl.setTooltip(Tooltip.create(text(backgroundScheduling ? "terminal_step_hint"
            : stepAction == ComputerControlSession.Action.START_PAUSED ? "prepare_step_hint" : "step_hint")));
        resetControl.active = restart;
        resetControl.setTooltip(Tooltip.create(text(resourceReset ? "resource_reset_hint" : maintenanceReset ? "maintenance_reset_hint" : "restart")));
    }
    /** Update existing row widgets on capture; never replace a focused text editor on a network reply. */
    private void refreshInputButtons() {
        if (inputButtons == null) return;
        var page = ComputerValueDisplay.page(new ArrayList<>(inputs.keySet()), inputPage, inputButtons.size());
        inputPage = page.index();
        for (int row = 0; row < inputButtons.size(); row++) {
            var button = inputButtons.get(row); boolean present = row < page.lines().size();
            button.active = present; button.visible = present;
            button.setMessage(present ? Component.literal(page.lines().get(row)) : Component.empty());
            button.setTooltip(present ? Tooltip.create(button.getMessage()) : null);
        }
        inputPrevious.visible = page.count() > 1; inputNext.visible = page.count() > 1;
        inputPrevious.active = inputPage > 0;
        inputNext.active = inputPage + 1 < page.count();
    }
    @Override public void onClose() {
        if (restartConfirmation) { restartConfirmation = false; init(); }
        else if (monitorPanel) closeMonitors();
        else if (inputPanel) { inputPanel = false; init(); }
        else navigator.accept(parent);
    }
    @Override public void removed() { }

    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        if (restartConfirmation) {
            graphics.drawWordWrap(font, restartExplanation, leftPos + 10, topPos + 42, imageWidth - 20, 0xffdddddd);
            return;
        }
        if (monitorPanel) return;
        if (inputPanel) {
            if (inputs.isEmpty()) graphics.drawString(font, text("inputs_empty"), leftPos + 8, topPos + 120, 0xffaaaaaa, false);
            return;
        }
        var slice = ComputerValueDisplay.page(statusLines, statusPage, statusPageSize());
        for (int row = 0; row < slice.lines().size(); row++)
            graphics.drawString(font, slice.lines().get(row), leftPos + rightX, topPos + displayTop() + row * 12, 0xffdddddd, false);
    }
    private List<String> statusLines() {
        var result = new ArrayList<String>();
        int width = Math.max(24, rightWidth);
        executionSnapshot().ifPresent(status -> {
            if (observationTab == 0) {
                var fields = status.fields();
                String phase = fields.get("phase") instanceof DataValue.TextValue value ? value.value() : "UNKNOWN";
                boolean paused = new DataValue.BooleanValue(true).equals(fields.get("paused"));
                Component state = text("state." + phase.toLowerCase(Locale.ROOT));
                if ((phase.equals("READY") && integer(fields.get("work")) == 0 && paused) || phase.equals("IDLE")) state = text("not_started");
                if (new DataValue.BooleanValue(false).equals(fields.get("available"))) state = text("unavailable");
                if (paused && !terminalPhase(phase))
                    state = text("paused_state", state);
                wrapPixels(result, "", text("status_state", state).getString(), width);
                if (terminalPhase(phase)) wrapPixels(result, "", text(phase.equals("CANCELLED") ? "cancelled_schedule_hint" : "terminal_schedule_hint").getString(), width);
                wrapPixels(result, "", text("current_block", installedSource(status)).getString(), width);
                DataValue work = fields.containsKey("cycles") ? fields.get("cycles") : fields.get("work");
                if (work != null) wrapPixels(result, "", text(fields.containsKey("cycles") ? "cycles_value" : "work_value", integer(work)).getString(), width);
                if (fields.get("wait_reason") instanceof DataValue.TextValue reason)
                    wrapPixels(result, "", text(reason.value()).getString(), width);
                if (fields.get("sleep_ticks") instanceof DataValue.IntegerValue ticks)
                    wrapPixels(result, "", text("sleep_value", ticks.value()).getString(), width);
                if (fields.get("memory_units") instanceof DataValue.IntegerValue units)
                    wrapPixels(result, "", text("cells_storage", units.value()).getString(), width);
                else if (fields.get("memory") instanceof DataValue.RecordValue memory)
                    wrapPixels(result, "", text("cells_storage", memory.storageUnits()).getString(), width);
                for (String key : List.of("diagnostic", "installation_diagnostic", "output")) {
                    if (phase.equals("CANCELLED") && key.equals("diagnostic")) continue;
                    DataValue value = fields.get(key);
                    if (value != null && value != DataValue.NullValue.INSTANCE)
                        wrapPixels(result, text("field." + key).getString() + ": ", key.equals("diagnostic")
                            ? ScratchDebugSource.diagnostic(installedDescription(), value, fields.get("source")).getString()
                            : ComputerValueDisplay.lines(value, Integer.MAX_VALUE).getFirst(), width);
                }
                var calls = ScratchDebugSource.frames(installedDescription(), fields.get("call_stack"));
                if (!calls.isEmpty()) {
                    wrapPixels(result, "", text("stack_title").getString(), width);
                    for (int i = 0; i < calls.size(); i++) wrapPixels(result, (i + 1) + ". ", calls.get(i).getString(), width);
                }
            } else if (observationTab == 1) {
                for (String group : List.of("locals", "memory")) {
                    var variables = variableGroup(group);
                    wrapPixels(result, "", text(group.equals("locals") ? "private_count" : "public_count", variables.fields().size()).getString(), width);
                    if (group.equals("locals") && !(status.fields().get(group) instanceof DataValue.RecordValue))
                        wrapPixels(result, "", text("private_unavailable").getString(), width);
                    variables.fields().forEach((name, value) -> result.add(variableLine(name, value, width)));
                }
            } else status.fields().forEach((name, value) -> wrapPixels(result, name + ": ",
                ComputerValueDisplay.lines(value, Integer.MAX_VALUE).getFirst(), width));
        });
        return List.copyOf(result);
    }
    /** Dedicated capture/settings replies are not execution snapshots. Preserve only a same-revision observation. */
    private Optional<DataValue.RecordValue> executionSnapshot() {
        var incoming = menu.status().filter(value -> value.fields().get("phase") instanceof DataValue.TextValue);
        long revision = menu.controlResponse().map(ProgramStatusResponse::revision)
            .orElseGet(() -> menu.response().map(ProgramEditorResponse::revision).orElse(0L));
        if (incoming.isPresent()) {
            executionStatus = incoming.orElseThrow(); executionRevision = revision; hasObservedExecution = true;
        } else if (executionRevision != revision || menu.controlResponse()
                .map(reply -> reply.code().equals("unavailable") || reply.code().equals("too_large")).orElse(false)) {
            executionStatus = null;
        }
        return Optional.ofNullable(executionStatus);
    }
    private DataValue.RecordValue variableGroup(String name) {
        return executionSnapshot().map(status -> status.fields().get(name)).filter(DataValue.RecordValue.class::isInstance)
            .map(DataValue.RecordValue.class::cast).orElseGet(() -> new DataValue.RecordValue(Map.of()));
    }
    private DataValue.RecordValue inspectionValues() {
        var fields = new LinkedHashMap<String, DataValue>();
        executionSnapshot().ifPresent(status -> {
            if (observationTab == 2) fields.putAll(status.fields());
            else if (observationTab == 1) {
                fields.put("private", variableGroup("locals")); fields.put("local_public", variableGroup("memory"));
            } else {
                boolean cancelled = new DataValue.TextValue("CANCELLED").equals(status.fields().get("phase"));
                for (String key : List.of("phase", "paused", "work", "cycles", "wait_reason", "sleep_ticks", "memory_units", "diagnostic", "installation_diagnostic", "output")) {
                    if (cancelled && key.equals("diagnostic")) continue;
                    if (status.fields().containsKey(key)) {
                        var value = status.fields().get(key);
                        fields.put(key, key.equals("diagnostic") && value != DataValue.NullValue.INSTANCE
                            ? new DataValue.TextValue(ScratchDebugSource.diagnostic(installedDescription(), value, status.fields().get("source")).getString()) : value);
                    }
                }
                fields.put("current_block", new DataValue.TextValue(installedSource(status).getString()));
            }
        });
        fields.put("control_notice", new DataValue.TextValue(notice.getString()));
        return new DataValue.RecordValue(fields);
    }
    private void inspect(DataValue.RecordValue values) {
        navigator.accept(new ComputerValueTreeScreen(menu, editorInventory, title, this, values, null,
            Component.empty(), text("snapshot_frozen"), null));
    }
    /** The server's missing-pin maintenance exception requires a retained, otherwise valid Flow save. */
    private static boolean maintainableFlowMismatch(Map<String, DataValue> fields, String phase) {
        if (!new DataValue.TextValue("flow").equals(fields.get("mode"))
            || !(fields.get("cursor") instanceof DataValue.IntegerValue cursor) || cursor.value() < 0
            || !(fields.get("work") instanceof DataValue.IntegerValue work) || work.value() < 0
            || !(fields.get("locals") instanceof DataValue.RecordValue)
            || !(fields.get("diagnostic") instanceof DataValue.RecordValue diagnostic)
            || !new DataValue.TextValue("HARDWARE_MISMATCH").equals(diagnostic.fields().get("code"))) return false;
        try {
            com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.valueOf(phase);
            return true; // Execution stays disabled; RESET still goes through the original server maintenance gate.
        } catch (IllegalArgumentException unknownPhase) { return false; }
    }
    private static boolean terminalPhase(String phase) {
        return phase.equals("COMPLETE") || phase.equals("SUCCEEDED") || phase.equals("FAILED") || phase.equals("CANCELLED");
    }
    private static long integer(DataValue value) { return value instanceof DataValue.IntegerValue number ? number.value() : 0; }
    /** Only the acknowledged source at the observed revision can provide the label. */
    private ScratchDebugSource installedDescription() {
        var installed = menu.installedSource(executionRevision).orElse(null);
        if (installed == null) return null;
        if (installed != describedSource) {
            describedSource = installed;
            installedDescription = ScratchDebugSource.decode(installed);
        }
        return installedDescription;
    }

    private Component installedSource(DataValue.RecordValue status) {
        if (status.fields().get("source") == DataValue.NullValue.INSTANCE
            && (new DataValue.TextValue("COMPLETE").equals(status.fields().get("phase"))
                || new DataValue.TextValue("SUCCEEDED").equals(status.fields().get("phase")))) return text("source_inactive");
        return ScratchDebugSource.location(installedDescription(), status.fields().get("source"));
    }

    // Only format a shallow preview here. The existing field browser still gets the original snapshot.
    private String variableLine(String name, DataValue value, int width) {
        String prefix = variableTextPreview(name, false);
        String contents = switch (value) {
            case DataValue.RecordValue ignored -> containerPreview(value);
            case DataValue.MapValue ignored -> containerPreview(value);
            case DataValue.ListValue ignored -> containerPreview(value);
            case DataValue.RecipeValue ignored -> variableType(value);
            case DataValue.NbtValue nbt -> variableType(value) + " #" + nbt.tagId();
            case DataValue.ErrorValue error -> variableType(value) + " · " + variableTextPreview(error.code(), false);
            case DataValue.TextValue string -> variableTextPreview(string.value(), true);
            default -> BrowserLiteralDisplay.leaf(value);
        };
        String separator = ": ";
        if (font.width(prefix + separator + contents) <= width) return prefix + separator + contents;

        // Leave room for the value even when a variable name itself is very long.
        prefix = fitVariableText(prefix, width / 2) + separator;
        return prefix + fitVariableText(contents, width - font.width(prefix));
    }

    private String fitVariableText(String contents, int width) {
        if (font.width(contents) <= width) return contents;
        String ellipsis = "…";
        int remaining = width - font.width(ellipsis);
        return remaining < 0 ? "" : font.plainSubstrByWidth(contents, remaining) + ellipsis;
    }

    private static String containerPreview(DataValue value) {
        return variableType(value) + " · "
            + Component.translatable("create_biotech.biocomputing.tree.entries", ValueTree.childCount(value)).getString();
    }

    private static String variableType(DataValue value) {
        return Component.translatable("create_biotech.biocomputing.tree.type." + ValueTree.kind(value)).getString();
    }

    private static String variableTextPreview(String value, boolean quote) {
        int end = 0;
        // Cap before escaping so a long value doesn't produce an unbounded preview string.
        for (int points = 0; points < MAX_VARIABLE_PREVIEW_CODE_POINTS && end < value.length(); points++)
            end += Character.charCount(value.codePointAt(end));
        String preview = BrowserLiteralDisplay.quote(value.substring(0, end) + (end < value.length() ? "…" : ""));
        return quote ? preview : preview.substring(1, preview.length() - 1);
    }

    private void wrapPixels(List<String> result, String prefix, String text, int width) {
        String remaining = prefix + text;
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, width);
            if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
            result.add(line); remaining = remaining.substring(line.length());
        }
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.debug." + key, args); }
}
