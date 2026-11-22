package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Player-owned pin/face grant. Reading this screen never starts a program or decodes a protocol. */
public final class ComputerSignalScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerCommunicationScreen parent;
    private final Map<String, Direction> faces = new HashMap<>();
    private record Port(Direction side, boolean enabled, OptionalInt level, String status, Optional<ClusterPortBinding> gateway) {}
    private Map<String, Port> ports;
    private String diagnostic, selected, submitted;
    private boolean requested, awaiting;
    private long responseVersion;
    private List<String> lines = List.of();
    private int page;
    private Component notice = Component.empty();
    private CreateTextButton portPrevious, portNext, face, enable, disable, clear, previous, next, refreshButton;
    private CreateTextButton bindGateway, clearGateway;
    private EditBox alias;
    private String aliasDraft = "";
    private boolean aliasEdited;
    public ComputerSignalScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerCommunicationScreen parent) {
        super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); responseVersion = menu.controlResponseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.QUICK));
        portPrevious = button(8, 30, 32, "previous_port", () -> select(-1));
        portNext = button(44, 30, 32, "next_port", () -> select(1));
        face = button(8, 54, imageWidth - 16, "face", this::cycleFace);
        int column = (imageWidth - 20) / 2;
        enable = button(8, 78, column, "enable", () -> configure(true));
        disable = button(12 + column, 78, column, "disable", () -> configure(false));
        alias = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 102, imageWidth - 16, 18, text("alias")));
        alias.setMaxLength(com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.MAX_NAME + 1);
        aliasDraft = aliasDraft == null ? "" : aliasDraft; alias.setValue(aliasDraft); alias.setHint(text("alias"));
        alias.setResponder(value -> { aliasDraft = value; aliasEdited = true; refresh(); });
        bindGateway = button(8, 126, column, "bind_gateway", () -> gateway(true));
        clearGateway = button(12 + column, 126, column, "clear_gateway", () -> gateway(false));
        clear = button(8, 150, imageWidth - 16, "clear", () -> request(ComputerControlSession.Action.CLEAR_SIMPLE_IO, new DataValue.RecordValue(Map.of())));
        previous = button(8, imageHeight - 27, 32, "previous", () -> { page--; refresh(); });
        next = button(44, imageHeight - 27, 32, "next", () -> { page++; refresh(); });
        refreshButton = button(84, imageHeight - 27, 84, "refresh", () -> request(ComputerControlSession.Action.READ_SIMPLE_IO, new DataValue.RecordValue(Map.of())));
        button(imageWidth - 76, imageHeight - 27, 68, "back", this::onClose);
        refresh();
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, text(key), action, true));
    }
    private List<String> names() { return ports == null ? List.of() : List.copyOf(ports.keySet()); }
    private void select(int delta) {
        var names = names(); int index = (selected == null ? -1 : names.indexOf(selected)) + delta;
        if (index >= 0 && index < names.size()) { selected = names.get(index); seedAlias(); page = 0; refresh(); }
    }
    private void seedAlias() {
        alias.setValue(ports == null || selected == null || !ports.containsKey(selected) ? "" : ports.get(selected).gateway().map(ClusterPortBinding::alias).orElse(""));
        aliasEdited = false;
    }
    private Direction chosenFace() {
        if (ports != null && selected != null && ports.containsKey(selected) && ports.get(selected).gateway().isPresent()) return ports.get(selected).side();
        return selected == null ? null : faces.containsKey(selected) ? faces.get(selected) : ports == null || !ports.containsKey(selected) ? null : ports.get(selected).side();
    }
    private void cycleFace() {
        if (awaiting || selected == null || ports == null || diagnostic != null || ports.get(selected).gateway().isPresent()) return;
        var old = chosenFace(); var directions = Direction.values();
        faces.put(selected, directions[old == null ? 0 : (old.ordinal() + 1) % directions.length]); refresh();
    }
    private void configure(boolean enabled) {
        if (awaiting || selected == null || chosenFace() == null || ports == null || diagnostic != null) return;
        submitted = selected;
        request(ComputerControlSession.Action.CONFIGURE_SIMPLE_IO, new SignalConnections.Configuration(selected, chosenFace(), enabled).value());
    }
    private void request(ComputerControlSession.Action action, DataValue.RecordValue inputs) {
        if (awaiting) return;
        if (action != ComputerControlSession.Action.CONFIGURE_SIMPLE_IO) submitted = null;
        awaiting = true; notice = Component.translatable("create_biotech.biocomputing.debug.waiting");
        menu.control(action, inputs); refresh();
    }
    private void gateway(boolean bind) {
        if (awaiting || selected == null || ports == null || diagnostic != null) return;
        try {
            var value = bind ? new SignalConnections.GatewayConfiguration(selected, aliasDraft).value()
                : new DataValue.RecordValue(Map.of("pin", new DataValue.TextValue(selected)));
            faces.remove(selected); aliasEdited = false;
            request(bind ? ComputerControlSession.Action.CONFIGURE_SIMPLE_IO_GATEWAY : ComputerControlSession.Action.CLEAR_SIMPLE_IO_GATEWAY, value);
        } catch (IllegalArgumentException invalid) { notice = text("invalid_alias"); refresh(); }
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; request(ComputerControlSession.Action.READ_SIMPLE_IO, new DataValue.RecordValue(Map.of())); }
        if (responseVersion == menu.controlResponseVersion()) return;
        responseVersion = menu.controlResponseVersion(); awaiting = false; ports = null; diagnostic = null;
        try {
            var reply = menu.controlResponse().orElseThrow();
            if (!reply.code().equals("ok")) notice = text("rejected", BrowserLiteralDisplay.quote(reply.code()), BrowserLiteralDisplay.quote(reply.detail()));
            else {
                var view = menu.signalStatus().orElseThrow();
                var decoded = new TreeMap<String, Port>();
                var problem = view.field("diagnostic");
                if (problem != DataValue.NullValue.INSTANCE) diagnostic = ((DataValue.TextValue)problem).value();
                for (var entry : ((DataValue.RecordValue)view.field("ports")).fields().entrySet()) {
                    var port = (DataValue.RecordValue)entry.getValue();
                    var sideValue = port.field("side");
                    Direction side = sideValue == DataValue.NullValue.INSTANCE ? null : Direction.byName(((DataValue.TextValue)sideValue).value());
                    if (sideValue != DataValue.NullValue.INSTANCE && side == null) throw new IllegalArgumentException("Invalid side");
                    var levelValue = port.field("level");
                    var level = levelValue == DataValue.NullValue.INSTANCE ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(((DataValue.IntegerValue)levelValue).value()));
                    if (level.isPresent() && (level.getAsInt() < 0 || level.getAsInt() > 15)) throw new IllegalArgumentException("Invalid level");
                    var route = port.fields().getOrDefault("gateway", DataValue.NullValue.INSTANCE);
                    if (route != DataValue.NullValue.INSTANCE && side == null) throw new IllegalArgumentException("Gateway needs a face");
                    var gateway = route == DataValue.NullValue.INSTANCE ? Optional.<ClusterPortBinding>empty() : Optional.of(ClusterPortBinding.read(route, side));
                    decoded.put(entry.getKey(), new Port(side, ((DataValue.BooleanValue)port.field("enabled")).value(), level, ((DataValue.TextValue)port.field("status")).value(), gateway));
                }
                ports = Collections.unmodifiableMap(decoded);
                if (submitted != null) faces.remove(submitted);
                if (selected == null || !ports.containsKey(selected)) selected = names().stream().findFirst().orElse(null);
                if (!aliasEdited) seedAlias();
                notice = text("frozen");
            }
        } catch (IllegalArgumentException | NoSuchElementException | ClassCastException | ArithmeticException
                | com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException invalid) { ports = null; notice = text("invalid"); }
        submitted = null; page = 0; refresh();
    }
    private void refresh() {
        var names = names(); int index = selected == null ? -1 : names.indexOf(selected);
        portPrevious.active = !awaiting && index > 0; portNext.active = !awaiting && index >= 0 && index + 1 < names.size();
        boolean usable = !awaiting && index >= 0 && diagnostic == null;
        boolean mapped = usable && ports.get(selected).gateway().isPresent();
        face.setMessage(text("face", chosenFace() == null ? text("unselected") : direction(chosenFace())));
        face.active = usable && !mapped; enable.active = disable.active = usable && chosenFace() != null;
        alias.setEditable(usable); clearGateway.active = mapped; bindGateway.active = false;
        if (usable) try { new SignalConnections.GatewayConfiguration(selected, aliasDraft); bindGateway.active = true; } catch (IllegalArgumentException ignored) { }
        clear.active = !awaiting && ports != null; refreshButton.active = !awaiting;
        var result = new ArrayList<String>(); append(result, notice);
        if (diagnostic != null) append(result, text("diagnostic", code(diagnostic)));
        if (index >= 0) {
            var port = ports.get(selected);
            append(result, text("selected_face", chosenFace() == null ? text("unselected") : direction(chosenFace())));
            append(result, text("saved", port.side() == null ? text("unselected") : direction(port.side()), text(port.enabled() ? "open" : "closed")));
            append(result, text("level", port.level().isEmpty() ? text("unknown") : port.level().getAsInt()));
            append(result, text("status", code(port.status())));
            port.gateway().ifPresent(route -> append(result, text("gateway", BrowserLiteralDisplay.quote(route.alias()),
                route.position().getX(), route.position().getY(), route.position().getZ())));
        }
        append(result, text("help")); lines = List.copyOf(result);
        var slice = ComputerValueDisplay.page(lines, page, pageSize()); page = slice.index();
        previous.active = page > 0; next.active = page + 1 < slice.count();
    }
    private void append(List<String> result, Component component) {
        String remaining = component.getString();
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, imageWidth - 16);
            if (!line.isEmpty() && Character.isHighSurrogate(line.charAt(line.length() - 1))) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
            result.add(line); remaining = remaining.substring(line.length());
        }
    }
    private int pageSize() { return Math.max(1, (imageHeight - 178 - 34) / 12); }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(text("title").getString(), imageWidth - 90), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        graphics.drawString(font, font.plainSubstrByWidth(selected == null ? text("empty").getString() : BrowserLiteralDisplay.quote(selected), imageWidth - 92), leftPos + 84, topPos + 36, 0xffaaccee, false);
        var slice = ComputerValueDisplay.page(lines, page, pageSize());
        for (int row = 0; row < slice.lines().size(); row++) graphics.drawString(font, slice.lines().get(row), leftPos + 8, topPos + 178 + 12 * row, 0xffdddddd, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() {}
    private static Component direction(Direction side) { return Component.translatable("create_biotech.biocomputing.communication.face." + side.getName()); }
    private static Component code(String value) {
        String key = "create_biotech.biocomputing.signal.code." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(BrowserLiteralDisplay.quote(value));
    }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.signal." + key, args); }
}
