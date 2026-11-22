package com.nobodiiiii.createbiotech.content.biocomputing.library.menu;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** New management session; the old lectern is only a temporary authorized doorway. */
public final class LibraryBookMenu extends AbstractContainerMenu {
    private final UUID nonce;
    private final ServerPlayer owner;
    private final LibraryBookWorldAccess world;
    private final LibraryBookSession session;
    private final LibraryServiceBehaviour service;
    private final LibraryControlBehaviour control;
    private List<LibraryControlBinding> controlChoices = List.of();
    private long controlDirectory;
    private long latestIntent, handledIntent = -1, revision, responseVersion, lastServiceTick = Long.MIN_VALUE;
    private LibraryBookResponse response;
    private DataValue.RecordValue state;
    public LibraryBookMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, buffer.readUUID(), null, null, null);
    }
    private LibraryBookMenu(int id, UUID nonce, ServerPlayer owner, LibraryBookWorldAccess world, LibraryServiceBehaviour service) {
        this(id, nonce, owner, world, service, null);
    }
    private LibraryBookMenu(int id, UUID nonce, ServerPlayer owner, LibraryBookWorldAccess world, LibraryServiceBehaviour service, LibraryControlBehaviour control) {
        super(CBMenuTypes.BIOCOMPUTING_LIBRARY.get(), id);
        this.nonce = nonce; this.owner = owner; this.world = world; this.service = service;
        this.control = control;
        session = world == null ? null : new LibraryBookSession(new LibraryBookAccess(world,
            new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), limits(),
                CBConfigs.SERVER.biocomputing.libraryBookBytes(CBConfigs.SERVER_SPEC.isLoaded())),
            new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get())));
    }
    // Reuse configured archive ceilings, not Computer RAM capacity or recipe-page capacity.
    private static ArchiveLimits limits() { return CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive(); }
    public long responseVersion() { return responseVersion; }
    public Optional<LibraryBookResponse> response() { return Optional.ofNullable(response); }
    public Optional<DataValue.RecordValue> state() { return Optional.ofNullable(state); }
    public void request(LibraryBookRequest.Action action, int book, String name, LibraryEntry.Kind kind, DataValue value) {
        if (world != null) return;
        var bounds = response == null ? limits() : response.limits();
        var payload = value == null ? new byte[0] : ProgramEditorWire.encodeValue(value, bounds);
        CBPackets.sendToServer(new LibraryBookRequest(containerId, nonce, ++latestIntent, revision, action, book, name, kind, payload));
    }
    public static boolean acceptsResponse(int id, UUID nonce, long intent, long revision,
        int receivedId, UUID receivedNonce, long receivedIntent, long receivedRevision) {
        return id == receivedId && nonce.equals(receivedNonce) && intent == receivedIntent && receivedRevision >= revision;
    }
    public void accept(LibraryBookResponse incoming) {
        if (!acceptsResponse(containerId, nonce, latestIntent, revision, incoming.containerId(), incoming.nonce(), incoming.intent(), incoming.revision())) return;
        revision = incoming.revision(); response = incoming; responseVersion++; state = null;
        if (incoming.state().length > 0) {
            var value = ProgramEditorWire.decodeValue(incoming.state(), incoming.limits());
            if (value instanceof DataValue.RecordValue record) state = record;
        }
    }
    public void handle(ServerPlayer player, LibraryBookRequest request) {
        if (player != owner || player.containerMenu != this || world == null || !stillValid(player)
            || request.containerId() != containerId || !nonce.equals(request.nonce()) || request.intent() <= handledIntent) return;
        handledIntent = request.intent();
        if (lastServiceTick == player.level().getGameTime()) { send("busy", null); return; }
        lastServiceTick = player.level().getGameTime();
        LibraryEntry loaded = null; String code = "ok";
        try {
            var changed = world.changes();
            if (!changed.isEmpty()) session.refreshChanged(changed);
            if (request.action() != LibraryBookRequest.Action.REFRESH && request.revision() != session.revision()) code = "changed";
            else switch (request.action()) {
                case REFRESH -> session.refresh();
                case SELECT -> session.select(request.book());
                case READ -> loaded = session.read(request.name());
                case PUT -> code = session.mutate(request.revision(), false, request.name(),
                    new LibraryEntry(request.kind(), ProgramEditorWire.decodeValue(request.value(), limits())));
                case DELETE -> code = session.mutate(request.revision(), true, request.name(), null);
                case RENAME_VOLUME -> code = session.renameVolume(request.revision(), request.name());
                case CONFIGURE_SERVICE -> {
                    if (!world.mayEdit()) code = "denied";
                    else if (service == null) code = "unavailable";
                    else {
                        var value = ProgramEditorWire.decodeValue(request.value(), limits());
                        if (!(value instanceof DataValue.RecordValue config) || !config.fields().keySet().equals(Set.of("revision", "configuration"))
                            || !(config.field("revision") instanceof DataValue.IntegerValue expected) || expected.value() < 0)
                            throw new IllegalArgumentException("Invalid configuration request");
                        code = service.configure(expected.value(),
                            com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.decode(config.field("configuration")));
                    }
                }
                case DISCOVER_CONTROL -> {
                    controlDirectory = Math.incrementExact(controlDirectory);
                    controlChoices = List.of();
                    if (!world.mayEdit()) code = "denied";
                    else if (control == null) code = "unavailable";
                    else controlChoices = control.discover(player);
                }
                case CONFIGURE_SERIAL -> {
                    if (!world.mayEdit()) code = "denied";
                    else if (control == null) code = "unavailable";
                    else {
                        var value = ProgramEditorWire.decodeValue(request.value(), limits());
                        if (!(value instanceof DataValue.RecordValue config) || !config.fields().keySet().equals(Set.of("revision", "enabled"))
                            || !(config.field("revision") instanceof DataValue.IntegerValue expected) || expected.value() < 0
                            || !(config.field("enabled") instanceof DataValue.BooleanValue enabled)) throw new IllegalArgumentException("Invalid serial configuration");
                        code = control.configureSerial(player, expected.value(), enabled.value());
                    }
                }
                case CONFIGURE_CONTROL -> {
                    if (!world.mayEdit()) code = "denied";
                    else if (control == null) code = "unavailable";
                    else {
                        var value = ProgramEditorWire.decodeValue(request.value(), limits());
                        if (!(value instanceof DataValue.RecordValue config) || !config.fields().keySet().equals(Set.of("revision", "choice", "directory"))
                            || !(config.field("revision") instanceof DataValue.IntegerValue expected) || expected.value() < 0
                            || !(config.field("directory") instanceof DataValue.IntegerValue directory) || directory.value() < 0
                            || !(config.field("choice") instanceof DataValue.IntegerValue choice)
                            || choice.value() < -1 || choice.value() >= controlChoices.size()) throw new IllegalArgumentException("Invalid control selection");
                        code = directory.value() != controlDirectory ? "changed" : control.configure(player, expected.value(), choice.value() < 0 ? Optional.empty()
                            : Optional.of(controlChoices.get((int)choice.value())));
                        controlChoices = List.of(); // One menu-owned selection, never client-supplied coordinates or identities.
                    }
                }
            }
        } catch (LibraryBookAccess.Failure failure) { code = failure.code(); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.DiscoveryException failure) { code = failure.code(); }
        catch (ArchiveException failure) { code = LibraryBookSession.archiveCode(failure); }
        catch (IllegalArgumentException failure) { code = "invalid"; }
        world.changes(); // Consume our own notification, not another menu's watch.
        send(code, loaded);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (world == null || handledIntent < 0 || !stillValid(owner)) return;
        var changed = world.changes(); if (changed.isEmpty()) return;
        String code = "changed";
        try { session.refreshChanged(changed); }
        catch (LibraryBookAccess.Failure failure) { code = failure.code(); }
        catch (IllegalArgumentException failure) { code = "invalid"; }
        send(code, null);
    }
    private void send(String code, LibraryEntry loaded) {
        byte[] state = new byte[0];
        try {
            var fields = new LinkedHashMap<String, DataValue>(session.snapshot(loaded, world::address).fields());
            if (service != null) fields.put("service", service.status());
            if (control != null) {
                fields.put("control", control.status());
                fields.put("control_directory", new DataValue.IntegerValue(controlDirectory));
                fields.put("control_choices", new DataValue.ListValue(controlChoices.stream().<DataValue>map(LibraryControlBinding::summary).toList()));
            }
            state = ProgramEditorWire.encodeValue(new DataValue.RecordValue(fields), limits());
        }
        catch (IllegalArgumentException tooLarge) {
            if (code.equals("ok")) code = loaded == null ? "ok_view_too_large" : "too_large";
        }
        CBPackets.sendToPlayer(new LibraryBookResponse(containerId, nonce, handledIntent, session.revision(), code, limits(), state), owner);
    }
    @Override public boolean stillValid(Player player) { return world == null || player == owner && world.valid(); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (world != null) world.close(); }
    public static OpeningProvider provider(Function<ServerPlayer, LibraryBookWorldAccess> factory) { return provider(factory, actor -> null); }
    public static OpeningProvider provider(Function<ServerPlayer, LibraryBookWorldAccess> factory, Function<ServerPlayer, LibraryServiceBehaviour> service) {
        return provider(factory, service, actor -> null);
    }
    public static OpeningProvider provider(Function<ServerPlayer, LibraryBookWorldAccess> factory, Function<ServerPlayer, LibraryServiceBehaviour> service,
        Function<ServerPlayer, LibraryControlBehaviour> control) {
        return new OpeningProvider(factory, service, control);
    }
    public static final class OpeningProvider implements MenuProvider {
        private final UUID nonce = UUID.randomUUID(); private final Function<ServerPlayer, LibraryBookWorldAccess> factory;
        private final Function<ServerPlayer, LibraryServiceBehaviour> service;
        private final Function<ServerPlayer, LibraryControlBehaviour> control;
        private OpeningProvider(Function<ServerPlayer, LibraryBookWorldAccess> factory, Function<ServerPlayer, LibraryServiceBehaviour> service,
            Function<ServerPlayer, LibraryControlBehaviour> control) {
            this.factory = factory; this.service = service; this.control = control;
        }
        @Override public Component getDisplayName() { return Component.translatable("create_biotech.biocomputing.library.title"); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
            if (!(player instanceof ServerPlayer server)) throw new IllegalArgumentException("Server player required");
            return new LibraryBookMenu(id, nonce, server, factory.apply(server), service.apply(server), control.apply(server));
        }
        public void writeOpeningData(RegistryFriendlyByteBuf b) { b.writeUUID(nonce); }
    }
}
