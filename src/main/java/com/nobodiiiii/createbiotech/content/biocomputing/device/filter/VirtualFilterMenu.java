package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public final class VirtualFilterMenu extends AbstractContainerMenu {
    private static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(4096, 65536, 262144);
    private final UUID nonce;
    private final String code;
    private final DataValue.RecordValue view;
    private final VirtualFilterBlockEntity entity;
    private final ServerPlayer owner;
    private final UUID session, target;
    private final List<SerialControlAccess.Binding> choices;
    private boolean consumed;

    public VirtualFilterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf wire) {
        this(id, wire.readUUID(), wire.readUtf(64),
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(wire.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS),
            null, null, null, null, List.of());
    }
    private VirtualFilterMenu(int id, UUID nonce, String code, DataValue.RecordValue view, VirtualFilterBlockEntity entity,
            ServerPlayer owner, UUID session, UUID target, List<SerialControlAccess.Binding> choices) {
        super(CBMenuTypes.VIRTUAL_FILTER.get(), id);
        this.nonce = nonce; this.code = code; this.view = view; this.entity = entity; this.owner = owner;
        this.session = session; this.target = target; this.choices = choices;
    }
    DataValue.RecordValue view() { return view; }
    String code() { return code; }
    VirtualFilterRequest request(VirtualFilterRequest.Action action, int choice) { return new VirtualFilterRequest(containerId, nonce, action, choice); }
    void handle(ServerPlayer player, VirtualFilterRequest request) {
        if (consumed || player.containerMenu != this || player != owner || !stillValid(player)
            || request.containerId() != containerId || !nonce.equals(request.nonce())) return;
        consumed = true;
        var port = entity.getBehaviour(VirtualFilterPort.TYPE);
        String result;
        try {
            result = switch (request.action()) {
                case REFRESH -> "ok";
                case REVOKE -> port.configure(player, session, null, Optional.empty(), false);
                case GRANT, GRANT_LIST -> request.choice() < choices.size()
                    ? port.configure(player, session, target, Optional.of(choices.get(request.choice())),
                        request.action() == VirtualFilterRequest.Action.GRANT_LIST) : "changed";
            };
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = String.valueOf(failure.getMessage()); }
        open(player, entity, result);
    }
    @Override public boolean stillValid(Player player) { return entity == null || player == owner && entity.getBehaviour(VirtualFilterPort.TYPE).mayManage(owner); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    static boolean open(ServerPlayer player, VirtualFilterBlockEntity entity, String result) {
        var port = entity.getBehaviour(VirtualFilterPort.TYPE);
        if (!port.mayManage(player)) return false;
        List<SerialControlAccess.Binding> candidates = List.of();
        try {
            var discovered = port.discover(player);
            if (discovered.size() <= VirtualFilterRequest.MAX_CHOICES) candidates = List.copyOf(discovered);
            else if (result.equals("ok")) result = "choice_capacity";
        } catch (CableDiscovery.DiscoveryException failure) {
            if (result.equals("ok")) result = failure.code();
        } catch (IllegalArgumentException failure) {
            if (result.equals("ok")) result = String.valueOf(failure.getMessage());
        }
        var fields = new LinkedHashMap<>(port.view().fields());
        fields.put("choices", new DataValue.ListValue(candidates.stream().<DataValue>map(SerialControlAccess.Binding::summary).toList()));
        byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(VirtualFilterProtocol.record(fields), VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            candidates = List.of();
            fields.put("choices", new DataValue.ListValue(List.of()));
            fields.put("rule", DataValue.NullValue.INSTANCE);
            bytes = ProgramEditorWire.encodeValue(VirtualFilterProtocol.record(fields), VIEW_LIMITS);
            result = "view_limit";
        }
        var captured = VirtualFilterProtocol.record(fields);
        var choices = candidates;
        var wire = bytes;
        var nonce = UUID.randomUUID();
        var session = port.session();
        var target = port.observedTarget();
        var code = result.length() <= 64 ? result : "provider_failure";
        return player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return entity.getBlockState().getBlock().getName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player actor) {
                return new VirtualFilterMenu(id, nonce, code, captured, entity, player, session, target, choices);
            }
        }, b -> { b.writeUUID(nonce); b.writeUtf(code, 64); b.writeByteArray(wire); }).isPresent();
    }
}
