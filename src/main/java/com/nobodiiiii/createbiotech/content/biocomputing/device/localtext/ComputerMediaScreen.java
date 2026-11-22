package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local file staging and explicit transfer, without modifying or installing the open program draft. */
public final class ComputerMediaScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final Runnable close;
    private SpeakerMediaPage displayed;
    private boolean requested, waiting, reading;
    private int slice, offset;
    private long intent;
    private Path pending;
    private byte[] bytes;
    private String fileName, notice;
    private boolean storeForm;
    private final String[] address = {"s0", "", "", "0", "", ""};

    public ComputerMediaScreen(ComputerProgramMenu menu, Inventory inventory, Runnable close) {
        super(menu, inventory, text("media_tab"));
        this.close = close;
    }

    @Override protected void init() {
        setWindowSize(Math.min(420, width - 12), Math.min(270, height - 12));
        super.init();
        clearWidgets();
        setFocused(null);
        if (storeForm) { initStore(); return; }
        int half = (imageWidth - 20) / 2;
        boolean ready = !waiting && !reading && requested;
        if (pending != null) {
            button(8, imageHeight - 51, half, text("media_confirm"), this::readPending).active = ready;
            button(12 + half, imageHeight - 51, half, text("media_cancel"), () -> {
                pending = null; notice = null; init();
            }).active = ready;
        }
        int nextY = imageHeight - 75, rows = rows();
        var tracks = displayed == null ? List.<SpeakerMediaPage.Track>of() : displayed.tracks();
        slice = Math.clamp(slice, 0, Math.max(0, (tracks.size() - 1) / rows));
        for (int i = 0; i < rows && slice * rows + i < tracks.size(); i++) {
            var track = tracks.get(slice * rows + i);
            var label = Component.literal(track.format() + " · " + track.name());
            var entry = button(8, 68 + i * 21, imageWidth - 16,
                Component.literal(font.plainSubstrByWidth(label.getString(), imageWidth - 29)), () -> {
                    notice = "media_still_staged"; init();
                });
            entry.active = ready && pending == null;
            entry.setTooltip(Tooltip.create(text("media_track", label, track.size())));
        }
        if (displayed != null && (displayed.lastPage() > 0 || tracks.size() > rows)) {
            boolean localNext = (slice + 1) * rows < tracks.size();
            button(8, nextY, half, text("previous"), () -> {
                if (slice > 0) { slice--; init(); }
                else request(displayed.page() - 1);
            }).active = ready && pending == null && (slice > 0 || displayed.page() > 0);
            button(12 + half, nextY, half, text("next"), () -> {
                if (localNext) { slice++; init(); }
                else request(displayed.page() + 1);
            }).active = ready && pending == null && (localNext || displayed.page() < displayed.lastPage());
        }
        if (pending == null && !tracks.isEmpty()) {
            button(8, imageHeight - 51, half, text("media_discard"), () -> request(-2)).active = ready;
            button(12 + half, imageHeight - 51, half, text("media_store"), () -> {
                address[5] = tracks.getFirst().name();
                storeForm = true; notice = null; init();
            }).active = ready;
        }
        button(8, imageHeight - 26, half, text("media_refresh"), () -> request(0)).active = ready && pending == null;
        button(12 + half, imageHeight - 26, half, text("back"), this::onClose);
    }

    private static final String[] LABELS = {"media_port", "media_library_pos", "media_track_pos", "media_slot", "media_volume", "media_entry"};

    private void initStore() {
        int half = (imageWidth - 20) / 2;
        int spacing = Math.min(26, Math.max(20, (imageHeight - 83) / address.length));
        for (int i = 0; i < address.length; i++) {
            final int field = i;
            var entry = addRenderableWidget(new EditBox(font, leftPos + half + 12, topPos + 53 + i * spacing,
                half, 18, text(LABELS[i])));
            entry.setMaxLength(i >= 4 ? 128 : 38);
            entry.setValue(address[i]); entry.setResponder(value -> address[field] = value);
            entry.setEditable(!waiting);
            entry.setTooltip(Tooltip.create(text(LABELS[i])));
        }
        button(8, imageHeight - 26, half, text("media_store_confirm"), this::confirmStore).active = !waiting;
        button(12 + half, imageHeight - 26, half, text("back"), this::onClose);
    }

    private static BlockPos coordinates(String input) {
        String[] parts = input.strip().split("\\s+");
        if (parts.length != 3) throw new IllegalArgumentException();
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private void confirmStore() {
        if (displayed == null || displayed.tracks().isEmpty() || waiting) return;
        try {
            var library = coordinates(address[1]);
            var track = coordinates(address[2]);
            int slot = Integer.parseInt(address[3].strip());
            if (slot < 0 || slot > 5 || address[0].isBlank() || address[0].length() > 32 || address[4].isBlank() || address[5].isBlank())
                throw new IllegalArgumentException();
            menu.storeMedia(displayed.tracks().getFirst().id(), address[0].strip(), library, track, slot, address[4], address[5]);
            storeForm = false; waiting = true; notice = null; init();
        } catch (IllegalArgumentException invalid) { notice = "invalid_address"; }
    }

    private int rows() { return Math.max(1, (imageHeight - 144) / 21); }
    private void request(int page) {
        requested = true; waiting = true; notice = null; slice = 0;
        menu.requestMediaPage(page); init();
    }

    @Override public void onFilesDrop(List<Path> files) {
        if (storeForm || waiting || reading || displayed == null || displayed.code().equals("access_denied")) return;
        pending = null; notice = null;
        if (files.size() != 1) notice = "media_one_file";
        else {
            var path = files.getFirst();
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (name.length() > SpeakerMediaChunk.MAX_NAME || SpeakerMediaFormat.fromName(name) == null) notice = "media_invalid";
            else pending = path;
        }
        init();
    }

    private record LocalFile(byte[] bytes, String error) {}
    private void readPending() {
        if (reading || waiting || pending == null) return;
        var path = pending;
        pending = null; fileName = path.getFileName().toString(); reading = true; init();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return new LocalFile(null, "media_local_read");
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] file = input.readNBytes(SpeakerMediaLimits.HARD_TRACK_BYTES + 1);
                    return file.length == 0 ? new LocalFile(null, "media_invalid")
                        : file.length > SpeakerMediaLimits.HARD_TRACK_BYTES ? new LocalFile(null, "media_file_limit")
                        : new LocalFile(file, null);
                }
            } catch (IOException | RuntimeException error) { return new LocalFile(null, "media_local_read"); }
        }, Util.nonCriticalIoPool()).thenAccept(file -> minecraft.execute(() -> {
            if (minecraft.screen != this) return;
            reading = false; notice = file.error(); bytes = file.bytes(); offset = 0; waiting = bytes != null;
            if (waiting) intent = menu.beginMediaUpload();
            init();
        }));
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { request(0); return; }
        var response = menu.mediaPage().orElse(null);
        if (response != null && response != displayed) {
            displayed = response; waiting = false; bytes = null; slice = 0;
            init();
        }
        if (bytes == null) return;
        int end = Math.min(offset + SpeakerMediaChunk.CHUNK_BYTES, bytes.length);
        menu.sendMediaChunk(intent, fileName, bytes.length, offset, Arrays.copyOfRange(bytes, offset, end));
        offset = end;
        if (end == bytes.length) bytes = null;
    }

    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 18, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        drawLine(g, title, 10, 0xffeeeeee);
        if (storeForm) {
            drawLine(g, notice == null ? text("media_destination_hint") : text("code." + notice), 34, 0xffffcf80);
            int spacing = Math.min(26, Math.max(20, (imageHeight - 83) / address.length));
            for (int i = 0; i < LABELS.length; i++)
                g.drawString(font, font.plainSubstrByWidth(text(LABELS[i]).getString(), (imageWidth - 26) / 2),
                    leftPos + 8, topPos + 57 + i * spacing, 0xffdddddd, false);
            return;
        }
        drawLine(g, text("media_hint"), 31, 0xffdddddd);
        if (reading) drawLine(g, text("media_reading"), 49, 0xffffcf80);
        else if (bytes != null) drawLine(g, text("media_progress", 100L * offset / bytes.length), 49, 0xffffcf80);
        else if (waiting) drawLine(g, text("waiting"), 49, 0xffffcf80);
        else if (pending != null) drawLine(g, text("media_pending", pending.getFileName().toString()), 49, 0xffffcf80);
        else if (notice != null) drawLine(g, text("code." + notice), 49, 0xffffcf80);
        else if (displayed != null && !displayed.code().equals("ok")) drawLine(g, text("code." + displayed.code()), 49, 0xffffcf80);
        if (displayed != null && displayed.code().equals("ok") && displayed.tracks().isEmpty())
            drawLine(g, text("media_empty"), 72, 0xffdddddd);
    }
    private void drawLine(GuiGraphics g, Component label, int y, int color) {
        g.drawString(font, font.plainSubstrByWidth(label.getString(), imageWidth - 16), leftPos + 8, topPos + y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}
    @Override public void onClose() {
        if (storeForm) { storeForm = false; notice = null; init(); return; }
        if (requested) menu.requestMediaPage(-1);
        close.run();
    }
    @Override public void removed() {}
    private static Component text(String key, Object... args) {
        String translated = "create_biotech.biocomputing.media." + key;
        return Component.translatableWithFallback(translated, key.startsWith("code.") ? key.substring(5) : key, args);
    }
}
