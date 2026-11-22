package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Local one-shot configuration snapshot; the server alone resolves network authority and captured sources. */
public final class ProductionPortMenu extends AbstractContainerMenu {
    private static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(4096, 65536, 262144);
    private static final List<String> STATUS_FIELDS = List.of("revision", "device", "address", "network", "binding",
        "last", "connection", "transmit", "failure", "frames", "orders", "diagnostic");
    private final UUID nonce;
    private final long revision;
    private final String code;
    private final String draftAddress;
    private final DataValue.RecordValue view;
    private final SmartBlockEntity host;
    private final ServerPlayer owner;
    private final List<SerialControlAccess.Binding> choices;
    private boolean consumed;

    public ProductionPortMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, buffer.readUUID(), buffer.readVarLong(), buffer.readUtf(64), buffer.readUtf(ProductionPortRequest.MAX_ADDRESS_LENGTH),
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(buffer.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS),
            null, null, List.of());
    }
    private ProductionPortMenu(int id, UUID nonce, long revision, String code, String draftAddress, DataValue.RecordValue view,
            SmartBlockEntity host, ServerPlayer owner, List<SerialControlAccess.Binding> choices) {
        super(CBMenuTypes.PRODUCTION_PORT.get(), id);
        this.nonce = nonce;
        this.revision = revision;
        this.code = code;
        this.draftAddress = draftAddress;
        this.view = view;
        this.host = host;
        this.owner = owner;
        this.choices = List.copyOf(choices);
    }

    public DataValue.RecordValue view() { return view; }
    public String code() { return code; }
    public String draftAddress() { return draftAddress; }
    public ProductionPortRequest request(ProductionPortRequest.Action action, int choice, String address) {
        return new ProductionPortRequest(containerId, nonce, revision, action, choice, address);
    }

    public void handle(ServerPlayer player, ProductionPortRequest request) {
        if (consumed || player != owner || player.containerMenu != this || !stillValid(player)
                || !request.matches(containerId, nonce, revision)) return;
        consumed = true;
        var service = host.getBehaviour(ProductionPortBehaviour.TYPE);
        String result = "ok";
        try {
            if (request.action() == ProductionPortRequest.Action.REVOKE)
                result = service.configure(player, revision, Optional.empty(), "");
            else if (request.action() == ProductionPortRequest.Action.GRANT)
                result = request.choice() >= choices.size() ? "changed"
                    : service.configure(player, revision, Optional.of(choices.get(request.choice())), request.address());
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = String.valueOf(failure.getMessage()); }
        open(player, host, result, request.address());
    }

    @Override public boolean stillValid(Player player) {
        if (host == null) return true;
        var service = host.getBehaviour(ProductionPortBehaviour.TYPE);
        return player == owner && !host.isRemoved() && service != null && service.mayManage(owner);
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    public static boolean open(ServerPlayer player, SmartBlockEntity host, String code) {
        return open(player, host, code, null);
    }
    private static boolean open(ServerPlayer player, SmartBlockEntity host, String result, String draft) {
        var service = host.getBehaviour(ProductionPortBehaviour.TYPE);
        if (host.isRemoved() || service == null || !service.mayManage(player)) return false;
        List<SerialControlAccess.Binding> candidates;
        String code = result;
        try {
            candidates = service.discover(player);
            if (candidates.size() > ProductionPortRequest.MAX_CHOICES) {
                candidates = List.of(); code = "endpoint_limit";
            }
        } catch (CableDiscovery.DiscoveryException failure) { candidates = List.of(); code = failure.code(); }
        catch (IllegalArgumentException failure) { candidates = List.of(); code = String.valueOf(failure.getMessage()); }
        var status = service.status();
        var fields = new LinkedHashMap<String, DataValue>();
        // Explicit summary allow-list: future service fields cannot accidentally expose raw frames or archives.
        for (String field : STATUS_FIELDS) fields.put(field, status.field(field));
        fields.put("choices", new DataValue.ListValue(candidates.stream().<DataValue>map(SerialControlAccess.Binding::summary).toList()));
        var view = new DataValue.RecordValue(fields);
        byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            candidates = List.of(); fields.put("choices", new DataValue.ListValue(List.of()));
            view = new DataValue.RecordValue(fields); bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); code = "view_limit";
        }
        String initialAddress = draft != null ? draft : status.field("address") instanceof DataValue.TextValue text ? text.value() : "";
        if (!ProductionPortRequest.validDraft(initialAddress)) initialAddress = "";
        String address = initialAddress;
        String message = code != null && code.length() <= 64 ? code : "provider_failure";
        var captured = view;
        var selected = List.copyOf(candidates);
        var wire = bytes;
        var nonce = UUID.randomUUID();
        long revision = service.revision();
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable("create_biotech.production_port.title"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player actor) {
                return new ProductionPortMenu(id, nonce, revision, message, address, captured, host, player, selected);
            }
        }, buffer -> {
            buffer.writeUUID(nonce); buffer.writeVarLong(revision); buffer.writeUtf(message, 64);
            buffer.writeUtf(address, ProductionPortRequest.MAX_ADDRESS_LENGTH); buffer.writeByteArray(wire);
        });
        return true;
    }
}
