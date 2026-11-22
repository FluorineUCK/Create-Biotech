package com.nobodiiiii.createbiotech.content.biocomputing.library.menu;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Manual medium selection and document exchange, not an automatic program installer. */
public final class LibraryBookScreen extends AbstractSimiContainerScreen<LibraryBookMenu> {
    private enum Format { JSON, TEXT, ARCHIVE }
    private static final int MAX_TEXT = ((ProgramEditorWire.MAX_BYTES + 2) / 3) * 4;
    private EditBox nameBox, valueBox, volumeBox;
    private EditBox endpointBox;
    private boolean servicePage, serviceEnabled;
    private boolean controlPage;
    private int controlChoice = -1, controlPageIndex;
    private String endpointName = "";
    private LibraryServiceConfiguration serviceConfig = LibraryServiceConfiguration.closed();
    private LibraryServiceConfiguration.Access grant = LibraryServiceConfiguration.Access.CLOSED;
    private CreateTextButton grantButton;
    private String volume = "", volumeStatus = "";
    private String name = "", payload = "", notice = "";
    private LibraryEntry.Kind kind = LibraryEntry.Kind.DATA;
    private Format format = Format.JSON;
    private long observed = -1;
    private int bookIndex, entryPage;
    public LibraryBookScreen(LibraryBookMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    @Override protected void init() {
        setWindowSize(Math.min(460, width - 12), Math.min(312, height - 12)); super.init();
        setFocused(null); clearWidgets();
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 244, topPos + 5, 68, 20, ManualFile.LIBRARY));
        button(imageWidth - 86, 5, 78, Component.translatable("options.title"), () -> { controlPage = false; servicePage = !servicePage; init(); });
        button(imageWidth - 172, 5, 82, text("control.title"), () -> { controlPage = !controlPage; init(); });
        if (controlPage) { initControl(); return; }
        button(8, 32, 24, Component.literal("<"), () -> selectBook(-1));
        button(36, 32, 24, Component.literal(">"), () -> selectBook(1));
        button(imageWidth - 84, 32, 76, Component.translatable("create_biotech.pattern_core.browser.refresh"), () -> send(LibraryBookRequest.Action.REFRESH, null));
        selectedAddress().ifPresent(address -> {
            var track = LibraryTrackAddress.decode(address.field("track"));
            var slot = ((DataValue.IntegerValue)address.field("slot")).value();
            var copy = button(68, 32, Math.max(24, imageWidth - 156),
                text("track_address", track.x(), track.y(), track.z(), slot + 1),
                () -> selectedAddress().ifPresent(current -> minecraft.keyboardHandler.setClipboard(EditorValueFormat.format(current))));
            copy.setTooltip(Tooltip.create(text("track_address_hint")));
        });
        volumeBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 65, imageWidth - 104, 20, Component.translatable("create_biotech.biocomputing.library.volume")));
        volumeBox.setMaxLength(LibraryBookRequest.MAX_NAME); volumeBox.setValue(volume == null ? "" : volume);
        volumeBox.setHint(Component.translatable("create_biotech.biocomputing.library.volume")); volumeBox.setResponder(text -> {
            volume = text;
            if (servicePage) {
                grant = serviceConfig == null ? LibraryServiceConfiguration.Access.CLOSED : serviceConfig.access(text);
                if (grantButton != null) grantButton.setMessage(text("access." + grant.name().toLowerCase(Locale.ROOT)));
            }
        });
        button(imageWidth - 90, 65, 82, Component.literal("↵"), () -> send(LibraryBookRequest.Action.RENAME_VOLUME, null)).active = selected() >= 0;
        if (servicePage) { initService(); return; }
        nameBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 106, imageWidth - 16, 20, Component.translatable("create_biotech.biocomputing.library.name")));
        nameBox.setMaxLength(LibraryBookRequest.MAX_NAME); nameBox.setValue(name); nameBox.setResponder(text -> name = text);
        button(8, 132, 100, text("kind." + kind.name().toLowerCase(Locale.ROOT)), () -> {
            kind = LibraryEntry.Kind.values()[(kind.ordinal() + 1) % LibraryEntry.Kind.values().length];
            if (kind != LibraryEntry.Kind.DATA) format = Format.ARCHIVE; init();
        });
        button(112, 132, 92, Component.literal(format.name()), () -> {
            if (kind == LibraryEntry.Kind.DATA) format = Format.values()[(format.ordinal() + 1) % Format.values().length]; init();
        });
        button(imageWidth - 80, 132, 32, Component.literal("↑"), () -> minecraft.keyboardHandler.setClipboard(payload));
        button(imageWidth - 44, 132, 36, Component.literal("↓"), () -> {
            var text = minecraft.keyboardHandler.getClipboard();
            if (text.length() > MAX_TEXT) notice = "too_large"; else { payload = text; init(); }
        });
        valueBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 158, imageWidth - 16, 20, Component.translatable("create_biotech.biocomputing.library.value")));
        // Keep an overflow sentinel so direct paste cannot become a valid, silently truncated archive.
        valueBox.setMaxLength(MAX_TEXT + 1); valueBox.setValue(payload); valueBox.setResponder(text -> payload = text);
        boolean selected = selected() >= 0;
        button(8, 184, 82, Component.translatable("structure_block.button.load"), () -> send(LibraryBookRequest.Action.READ, null)).active = selected;
        button(94, 184, 82, Component.translatable("structure_block.button.save"), () -> {
            if (payload.length() > MAX_TEXT) { notice = "too_large"; return; }
            try {
                var limits = menu.response().orElseThrow().limits();
                var value = switch (format) {
                    case JSON -> EditorValueFormat.parse(EditorValueFormat.Kind.JSON, payload);
                    case TEXT -> new DataValue.TextValue(payload);
                    case ARCHIVE -> ProgramEditorWire.decodeValue(Base64.getDecoder().decode(payload), limits);
                };
                new LibraryEntry(kind, value); send(LibraryBookRequest.Action.PUT, value);
            } catch (IllegalArgumentException failure) { notice = "invalid"; }
        }).active = selected;
        button(180, 184, 82, Component.translatable("selectWorld.delete"), () -> send(LibraryBookRequest.Action.DELETE, null)).active = selected;
        var entries = entries();
        int first = entryPage * 3;
        for (int row = 0; row < 3 && first + row < entries.size(); row++) {
            var entry = (DataValue.RecordValue) entries.get(first + row);
            var entryName = ((DataValue.TextValue) entry.field("name")).value();
            button(8, 210 + row * 22, imageWidth - 62, Component.literal(entryName), () -> {
                name = entryName; nameBox.setValue(name); send(LibraryBookRequest.Action.READ, null);
            });
        }
        button(imageWidth - 48, 210, 40, Component.literal("<"), () -> { entryPage = Math.max(0, entryPage - 1); init(); });
        button(imageWidth - 48, 234, 40, Component.literal(">"), () -> { if ((entryPage + 1) * 3 < entries.size()) entryPage++; init(); });
        button(imageWidth - 76, imageHeight - 26, 68, Component.translatable("gui.done"), this::onClose);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (observed == -1) { observed = menu.responseVersion(); send(LibraryBookRequest.Action.REFRESH, null); }
        if (observed == menu.responseVersion()) return;
        observed = menu.responseVersion(); notice = menu.response().map(LibraryBookResponse::code).orElse("");
        controlChoice = -1; // A fresh server response invalidates a preview; never silently select a replacement.
        if (notice.equals("ok") || notice.equals("changed") || notice.equals("configured"))
            volume = menu.state().map(state -> state.fields().get("volume")).filter(DataValue.TextValue.class::isInstance)
                .map(DataValue.TextValue.class::cast).map(DataValue.TextValue::value).orElse("");
        volumeStatus = menu.state().map(state -> state.fields().get("volumeStatus")).filter(DataValue.TextValue.class::isInstance)
            .map(DataValue.TextValue.class::cast).map(DataValue.TextValue::value).orElse("");
        menu.state().map(state -> state.fields().get("service")).filter(DataValue.RecordValue.class::isInstance)
            .map(DataValue.RecordValue.class::cast).ifPresent(service -> {
                serviceConfig = LibraryServiceConfiguration.decode(service.field("configuration"));
                endpointName = serviceConfig.endpoint().map(EndpointConfiguration::name).orElse("");
                serviceEnabled = serviceConfig.enabled();
                grant = serviceConfig.access(volume == null ? "" : volume);
            });
        var loaded = menu.state().map(state -> state.field("loaded")).orElse(DataValue.NullValue.INSTANCE);
        if (loaded instanceof DataValue.RecordValue entry) {
            kind = LibraryEntry.Kind.valueOf(((DataValue.TextValue) entry.field("kind")).value());
            var value = entry.field("value");
            try {
                if (kind != LibraryEntry.Kind.DATA) throw new IllegalArgumentException("document");
                payload = EditorValueFormat.format(value); format = Format.JSON;
            } catch (IllegalArgumentException notJson) {
                payload = Base64.getEncoder().encodeToString(ProgramEditorWire.encodeValue(value, menu.response().orElseThrow().limits()));
                format = Format.ARCHIVE;
            }
        }
        init();
    }
    private List<DataValue> books() { return menu.state().map(state -> ((DataValue.ListValue) state.field("books")).values()).orElse(List.of()); }
    private Optional<DataValue.RecordValue> controlStatus() {
        return menu.state().map(s -> s.fields().get("control")).filter(DataValue.RecordValue.class::isInstance).map(DataValue.RecordValue.class::cast);
    }
    private List<DataValue> controlChoices() {
        return menu.state().map(s -> s.fields().get("control_choices")).filter(DataValue.ListValue.class::isInstance)
            .map(DataValue.ListValue.class::cast).map(DataValue.ListValue::values).orElse(List.of());
    }
    private Optional<DataValue.RecordValue> serialStatus() {
        return controlStatus().map(s -> s.fields().get("serial")).filter(DataValue.RecordValue.class::isInstance).map(DataValue.RecordValue.class::cast);
    }
    private Component controlLabel(DataValue.RecordValue value) {
        return ((DataValue.TextValue)value.field("kind")).value().equals("cluster")
            ? text("control.cluster", ((DataValue.TextValue)value.field("terminal")).value(), ((DataValue.TextValue)value.field("member")).value())
            : text("control.computer", ((DataValue.TextValue)value.field("member")).value());
    }
    private void initControl() {
        button(8, 34, 140, text("control.discover"), () -> {
            controlChoice = -1; controlPageIndex = 0; send(LibraryBookRequest.Action.DISCOVER_CONTROL, null); init();
        }).active = controlStatus().isPresent();
        button(154, 34, 100, Component.translatable("create_biotech.pattern_core.browser.refresh"),
            () -> send(LibraryBookRequest.Action.REFRESH, null));
        controlStatus().map(s -> s.fields().get("drive")).filter(DataValue.RecordValue.class::isInstance)
            .map(DataValue.RecordValue.class::cast).ifPresent(drive -> {
                var result = drive.field("result");
                var label = button(8, imageHeight - 145, imageWidth - 16, text("control.drive_status",
                    drivePhase(((DataValue.TextValue)drive.field("code")).value()),
                    ((DataValue.IntegerValue)drive.field("sequence")).value(),
                    ((DataValue.IntegerValue)drive.field("remaining")).value(),
                    result instanceof DataValue.TextValue value ? code(value.value()) : Component.literal("—")), () -> {});
                label.active = false;
                label.setTooltip(Tooltip.create(text("control.drive_hint")));
            });
        var choices = controlChoices(); int rows = Math.max(1, (imageHeight - 222) / 24);
        controlPageIndex = Math.min(controlPageIndex, Math.max(0, (choices.size() - 1) / rows));
        for (int row = 0; row < rows && controlPageIndex * rows + row < choices.size(); row++) {
            int index = controlPageIndex * rows + row;
            var choice = button(8, 91 + row * 24, imageWidth - 16, controlLabel((DataValue.RecordValue)choices.get(index)),
                () -> { controlChoice = index; init(); });
            if (controlChoice == index) choice.setTooltip(Tooltip.create(text("control.selected")));
            choice.active = controlChoice != index;
        }
        button(imageWidth - 94, 34, 40, Component.literal("<"), () -> { controlPageIndex = Math.max(0, controlPageIndex - 1); init(); });
        button(imageWidth - 50, 34, 40, Component.literal(">"), () -> { if ((controlPageIndex + 1) * rows < choices.size()) controlPageIndex++; init(); });
        serialStatus().ifPresent(serial -> {
            boolean enabled = ((DataValue.BooleanValue)serial.field("enabled")).value();
            button(8, imageHeight - 100, 186, text(enabled ? "control.serial_close" : "control.serial_open"),
                () -> controlStatus().ifPresent(control -> send(LibraryBookRequest.Action.CONFIGURE_SERIAL,
                    new DataValue.RecordValue(Map.of("revision", control.field("revision"), "enabled", new DataValue.BooleanValue(!enabled))))));
            button(200, imageHeight - 100, imageWidth - 208, text("control.copy_target"),
                () -> minecraft.keyboardHandler.setClipboard(EditorValueFormat.format(serial.field("target"))));
        });
        button(8, imageHeight - 74, 136, text("control.bind"), () -> configureControl(controlChoice))
            .active = controlStatus().isPresent() && controlChoice >= 0 && controlChoice < choices.size();
        button(150, imageHeight - 74, 110, text("control.release"), () -> configureControl(-1))
            .active = controlStatus().map(s -> s.field("binding") instanceof DataValue.RecordValue).orElse(false);
        button(imageWidth - 76, imageHeight - 26, 68, Component.translatable("gui.done"), this::onClose);
    }
    private void configureControl(int choice) {
        controlStatus().ifPresent(status -> send(LibraryBookRequest.Action.CONFIGURE_CONTROL, new DataValue.RecordValue(Map.of(
            "revision", status.field("revision"), "choice", new DataValue.IntegerValue(choice),
            "directory", menu.state().map(s -> s.fields().get("control_directory")).orElse(new DataValue.IntegerValue(0))))));
    }
    private void initService() {
        if (grant == null) grant = LibraryServiceConfiguration.Access.CLOSED;
        if (serviceConfig == null) serviceConfig = LibraryServiceConfiguration.closed();
        endpointBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 106, imageWidth - 16, 20,
            Component.translatable("create_biotech.biocomputing.endpoint.name")));
        endpointBox.setMaxLength(EndpointConfiguration.MAX_NAME_LENGTH);
        endpointBox.setValue(endpointName == null ? "" : endpointName); endpointBox.setResponder(text -> endpointName = text);
        button(8, 132, 130, Component.translatable(serviceEnabled ? "options.on" : "options.off"),
            () -> { serviceEnabled = !serviceEnabled; init(); });
        grantButton = button(8, 158, 170, text("access." + grant.name().toLowerCase(Locale.ROOT)), () -> {
            grant = LibraryServiceConfiguration.Access.values()[(grant.ordinal() + 1) % LibraryServiceConfiguration.Access.values().length];
            init();
        });
        button(182, 158, 36, Component.literal("<"), () -> selectGrant(-1));
        button(222, 158, 36, Component.literal(">"), () -> selectGrant(1));
        button(8, 184, 130, Component.translatable("structure_block.button.save"), this::configureService)
            .active = serviceStatus().filter(s -> ((DataValue.TextValue)s.field("code")).value().equals("ok")).isPresent();
        button(imageWidth - 76, imageHeight - 26, 68, Component.translatable("gui.done"), this::onClose);
    }
    private Optional<DataValue.RecordValue> serviceStatus() {
        return menu.state().map(s -> s.fields().get("service")).filter(DataValue.RecordValue.class::isInstance).map(DataValue.RecordValue.class::cast);
    }
    private void selectGrant(int delta) {
        var names = new ArrayList<>(serviceConfig.grants().keySet()); if (names.isEmpty()) return;
        int current = names.indexOf(volume); volume = names.get(Math.floorMod(current + delta, names.size()));
        grant = serviceConfig.access(volume); init();
    }
    private void configureService() {
        try {
            var grants = new LinkedHashMap<>(serviceConfig.grants());
            if (volume != null && !volume.isBlank()) {
                if (grant == LibraryServiceConfiguration.Access.CLOSED) grants.remove(volume); else grants.put(volume, grant);
            } else if (grant != LibraryServiceConfiguration.Access.CLOSED) throw new IllegalArgumentException("invalid_volume");
            var endpoint = endpointName.isBlank() && !serviceEnabled ? Optional.<EndpointConfiguration>empty()
                : Optional.of(new EndpointConfiguration(endpointName, serviceEnabled));
            var config = new LibraryServiceConfiguration(endpoint, grants);
            var state = serviceStatus().orElseThrow();
            send(LibraryBookRequest.Action.CONFIGURE_SERVICE, new DataValue.RecordValue(Map.of(
                "revision", state.field("revision"), "configuration", config.value())));
        } catch (IllegalArgumentException failure) { notice = "invalid_configuration"; }
    }
    private List<DataValue> entries() { return menu.state().map(state -> ((DataValue.ListValue) state.field("entries")).values()).orElse(List.of()); }
    private int selected() { return menu.state().map(state -> (int)((DataValue.IntegerValue) state.field("selected")).value()).orElse(-1); }
    private void selectBook(int delta) {
        if (books().isEmpty()) return;
        bookIndex = selected() < 0 ? 0 : Math.floorMod(selected() + delta, books().size());
        entryPage = 0; send(LibraryBookRequest.Action.SELECT, null);
    }
    /** Missing/old snapshots remain viewable, but cannot fabricate a local physical address. */
    private Optional<DataValue.RecordValue> selectedAddress() {
        int index = selected();
        if (index < 0 || index >= books().size()) return Optional.empty();
        var value = menu.state().map(state -> state.fields().get("addresses")).orElse(null);
        if (!(value instanceof DataValue.ListValue addresses) || index >= addresses.values().size()
            || !(addresses.values().get(index) instanceof DataValue.RecordValue address)
            || !address.fields().keySet().equals(Set.of("track", "slot"))
            || !(address.field("slot") instanceof DataValue.IntegerValue slot) || slot.value() < 0 || slot.value() >= 6)
            return Optional.empty();
        try { LibraryTrackAddress.decode(address.field("track")); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        return Optional.of(address);
    }
    private void send(LibraryBookRequest.Action action, DataValue value) {
        try { menu.request(action, bookIndex, action == LibraryBookRequest.Action.RENAME_VOLUME ? volume : name, kind, value); }
        catch (IllegalArgumentException failure) { notice = "too_large"; }
    }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), Math.max(0, imageWidth - 260)), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        if (controlPage) {
            var status = controlStatus();
            String summary = status.map(s -> s.field("binding")).filter(DataValue.RecordValue.class::isInstance)
                .map(DataValue.RecordValue.class::cast).map(this::controlLabel).map(Component::getString).orElse(text("control.none").getString());
            graphics.drawString(font, font.plainSubstrByWidth(summary, imageWidth - 16), leftPos + 8, topPos + 65, 0xffeeeeee, false);
            serialStatus().ifPresent(serial -> graphics.drawString(font,
                font.plainSubstrByWidth(text("control.serial_status", ((DataValue.TextValue)serial.field("connection")).value(),
                    ((DataValue.TextValue)serial.field("transmit")).value(), ((DataValue.IntegerValue)serial.field("frames")).value(),
                    ((DataValue.TextValue)serial.field("last")).value()).getString(), imageWidth - 16), leftPos + 8, topPos + imageHeight - 120, 0xffcccccc, false));
            graphics.drawString(font, font.plainSubstrByWidth(text("control.hint").getString(), imageWidth - 16), leftPos + 8, topPos + imageHeight - 47, 0xffaaaaaa, false);
            var stateCode = status.map(s -> ((DataValue.TextValue)s.field("code")).value()).orElse("unavailable");
            graphics.drawString(font, font.plainSubstrByWidth((stateCode.equals("ready") ? text("control.ready") : code(stateCode)).copy().append(" / ").append(code(notice)).getString(), imageWidth - 96),
                leftPos + 8, topPos + imageHeight - 19, 0xffdddddd, false);
            return;
        }
        int selected = selected(); String position = selected >= 0 && selected < books().size() ? ((DataValue.TextValue) books().get(selected)).value() : "—";
        if (selectedAddress().isEmpty())
            graphics.drawString(font, font.plainSubstrByWidth((selected + 1) + "/" + books().size() + "  " + position, Math.max(0, imageWidth - 156)),
                leftPos + 68, topPos + 38, 0xffeeeeee, false);
        graphics.drawString(font, Component.translatable(servicePage ? "create_biotech.biocomputing.endpoint.name" : "create_biotech.biocomputing.library.name"), leftPos + 8, topPos + 91, 0xffcccccc, false);
        if (servicePage) serviceStatus().ifPresent(service -> {
            var summary = text("runtime", ((DataValue.IntegerValue)service.field("queued")).value(),
                code(((DataValue.TextValue)service.field("runtime")).value()), code(((DataValue.TextValue)service.field("transport")).value()));
            graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), imageWidth - 16), leftPos + 8, topPos + 214, 0xffcccccc, false);
        });
        var status = code(notice).copy();
        if (volumeStatus != null && !volumeStatus.isEmpty() && !volumeStatus.equals("ok")) {
            if (!notice.isEmpty()) status.append(" / ");
            status.append(code(volumeStatus));
        }
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 100), leftPos + 8, topPos + imageHeight - 19, 0xffdddddd, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.library." + key, args);
    }
    private static Component code(String value) {
        return value.isEmpty() ? Component.empty() : Component.translatableWithFallback("create_biotech.biocomputing.library.code." + value, value);
    }
    private static Component drivePhase(String value) {
        return switch (value) {
            case "read", "stat", "list" -> text("control.phase." + value);
            default -> code(value);
        };
    }
}
