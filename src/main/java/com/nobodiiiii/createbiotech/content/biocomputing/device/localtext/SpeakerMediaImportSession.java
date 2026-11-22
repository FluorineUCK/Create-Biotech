package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFileRam;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Only a live, authorized Computer menu may fill the owner's volatile file buffer. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class SpeakerMediaImportSession {
    private final int containerId;
    private final UUID nonce;
    private long intent, lastChunkTick;
    private ComputerFileRam.Lease upload;
    private String name;
    private int size;
    private SpeakerMediaStoreRequest pendingStore;
    private SpeakerMediaStoreSession store;

    public void close() {
        cancelUpload();
        closeStore();
    }

    private void closeStore() {
        pendingStore = null;
        if (store != null) { store.close(); store = null; }
    }

    public void store(ServerPlayer player, SpeakerMediaStoreRequest request, ComputerBehaviour computer) {
        if (request.intent() <= intent || storing()) return;
        intent = request.intent();
        cancelUpload();
        if (computer == null) { respond(player, null, "access_denied"); return; }
        pendingStore = request;
    }

    /** After Cluster block entities refresh physical availability, before serial hardware advances. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void afterBlockTicks(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Active menus are the queue owners. Closing or replacing a menu cannot leave a queued action behind.
        for (var player : level.players()) {
            if (player.containerMenu instanceof ComputerProgramMenu menu) menu.advanceMediaStore(player);
        }
    }

    public boolean storing() { return pendingStore != null || store != null; }

    public void advanceStore(ServerPlayer player, ComputerBehaviour computer) {
        if (!storing()) return;
        String result;
        if (computer == null) result = "access_denied";
        else {
            try {
                if (store == null) {
                    store = SpeakerMediaStoreSession.start(player, computer, pendingStore);
                    pendingStore = null;
                }
                result = store.tick(player, computer);
                if (result == null) return;
                if ("written".equals(result)) store.complete(player, computer);
            } catch (IllegalArgumentException failure) { result = code(failure); }
            catch (IllegalStateException failure) { result = "provider_failure"; }
        }
        closeStore();
        respond(player, computer, "written".equals(result) ? "media_stored" : result);
    }

    private static String code(IllegalArgumentException failure) {
        String code = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return code.matches("[a-z_]{1,32}") ? code : "provider_failure";
    }

    public SpeakerMediaImportSession(int containerId, UUID nonce) {
        this.containerId = containerId; this.nonce = nonce;
    }

    public boolean uploading() { return upload != null; }
    public void cancelUpload() {
        if (upload != null) { upload.abort(); upload = null; }
    }

    public void page(ServerPlayer player, SpeakerMediaPageRequest request, ComputerBehaviour computer) {
        if (request.intent() <= intent || request.page() < -2 || request.page() > 0) return;
        intent = request.intent();
        close();
        if (request.page() == -1) return; // Closed screen: completed file stays in the machine's RAM.
        if (request.page() == -2 && computer != null) computer.discardStagedFile(player.getUUID());
        respond(player, computer, request.page() == -2 ? "media_discarded" : "ok");
    }

    public void chunk(ServerPlayer player, SpeakerMediaChunk request, ComputerBehaviour computer) {
        if (storing() || request.intent() < intent || request.intent() == intent && upload == null) return;
        boolean newUpload = request.intent() > intent;
        if (newUpload) { intent = request.intent(); cancelUpload(); }
        if (computer == null) { cancelUpload(); respond(player, null, "access_denied"); return; }
        if (newUpload) {
            var limits = CBConfigs.SERVER.biocomputing.speakerMediaLimits(CBConfigs.SERVER_SPEC.isLoaded());
            if (request.name().length() > SpeakerMediaChunk.MAX_NAME || SpeakerMediaFormat.fromName(request.name()) == null
                || request.total() <= 0) { respond(player, computer, "media_invalid"); return; }
            if (request.total() > limits.track() || request.total() > SpeakerMediaLimits.HARD_TRACK_BYTES) {
                respond(player, computer, "media_file_limit"); return;
            }
            if (request.offset() != 0) { respond(player, computer, "media_upload_order"); return; }
            upload = computer.reserveFileRam(player.getUUID(), request.name(), request.total()).orElse(null);
            if (upload == null) { respond(player, computer, "media_ram_unavailable"); return; }
            name = request.name(); size = request.total();
        }
        lastChunkTick = player.serverLevel().getGameTime();
        if (!name.equals(request.name()) || size != request.total() || request.bytes().length > SpeakerMediaChunk.CHUNK_BYTES
            || !upload.append(request.offset(), request.bytes())) {
            cancelUpload();
            respond(player, computer, "media_upload_order");
            return;
        }
        if (upload.filled()) {
            String result = SpeakerMediaUpload.finish(upload, name);
            upload = null;
            respond(player, computer, result);
        }
    }

    public void expireUpload(ServerPlayer player, ComputerBehaviour computer) {
        if (upload != null && (computer == null || player.serverLevel().getGameTime() - lastChunkTick > 200)) {
            cancelUpload();
            respond(player, computer, "media_timeout");
        }
    }

    private void respond(ServerPlayer player, ComputerBehaviour computer, String code) {
        var tracks = computer == null ? List.<SpeakerMediaPage.Track>of() : computer.stagedFile(player.getUUID()).stream()
            .map(file -> new SpeakerMediaPage.Track(file.id(), file.name(), SpeakerMediaFormat.fromName(file.name()).name(), file.size())).toList();
        CBPackets.sendToPlayer(new SpeakerMediaPage(containerId, nonce, intent, computer == null ? "access_denied" : code,
            0, 0, tracks), player);
    }
}
