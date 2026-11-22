package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** One-shot configuration snapshot; each acknowledged action opens a fresh nonce, never a stale choice index. */
public final class RedstoneOutputMenu extends AbstractContainerMenu {
    private static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(4096, 65536, 262144);
    private final UUID nonce;
    private final long revision;
    private final DataValue.RecordValue view;
    private final String code;
    private final RedstoneOutputBlockEntity output;
    private final ServerPlayer owner;
    private final List<SerialControlAccess.Binding> choices;
    private boolean consumed;
    public RedstoneOutputMenu(int id, Inventory inventory, RegistryFriendlyByteBuf b) {
        this(id, b.readUUID(), b.readVarLong(), b.readUtf(64),
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(b.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS), null, null, List.of());
    }
    private RedstoneOutputMenu(int id, UUID nonce, long revision, String code, DataValue.RecordValue view,
            RedstoneOutputBlockEntity output, ServerPlayer owner, List<SerialControlAccess.Binding> choices) {
        super(CBMenuTypes.REDSTONE_OUTPUT.get(), id);
        this.nonce = nonce; this.revision = revision; this.code = code; this.view = view;
        this.output = output; this.owner = owner; this.choices = List.copyOf(choices);
    }
    public DataValue.RecordValue view() { return view; }
    public String code() { return code; }
    public RedstoneOutputRequest request(RedstoneOutputRequest.Action action, int choice) {
        return new RedstoneOutputRequest(containerId, nonce, revision, action, choice);
    }
    public void handle(ServerPlayer player, RedstoneOutputRequest request) {
        if (consumed || player != owner || player.containerMenu != this || !stillValid(player) || !request.matches(containerId, nonce, revision)) return;
        consumed = true; var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE); String result = "ok";
        try {
            if (request.action() == RedstoneOutputRequest.Action.REVOKE) result = service.configure(player, revision, Optional.empty());
            else if (request.action() == RedstoneOutputRequest.Action.GRANT) {
                if (request.choice() >= choices.size()) result = "changed";
                else result = service.configure(player, revision, Optional.of(choices.get(request.choice())));
            }
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = String.valueOf(failure.getMessage()); }
        open(player, output, result);
    }
    @Override public boolean stillValid(Player player) {
        return output == null || player == owner && output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE).mayManage(owner);
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    public static boolean open(ServerPlayer player, RedstoneOutputBlockEntity output, String result) {
        var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        if (!service.mayManage(player)) return false;
        List<SerialControlAccess.Binding> candidates; String code = result;
        try { candidates = service.discover(player); }
        catch (CableDiscovery.DiscoveryException failure) { candidates = List.of(); code = failure.code(); }
        catch (IllegalArgumentException failure) { candidates = List.of(); code = String.valueOf(failure.getMessage()); }
        var fields = new LinkedHashMap<String, DataValue>(service.status().fields());
        fields.put("choices", new DataValue.ListValue(candidates.stream().<DataValue>map(SerialControlAccess.Binding::summary).toList()));
        DataValue.RecordValue view = new DataValue.RecordValue(fields); byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            candidates = List.of(); fields.put("choices", new DataValue.ListValue(List.of()));
            view = new DataValue.RecordValue(fields); bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); code = "view_limit";
        }
        var captured = view; var selected = candidates; var wire = bytes; var nonce = UUID.randomUUID();
        long revision = service.revision(); String message = code.length() <= 64 ? code : "provider_failure";
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable("block.create_biotech.biocomputing_redstone_output"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player actor) {
                return new RedstoneOutputMenu(id, nonce, revision, message, captured, output, player, selected);
            }
        }, b -> { b.writeUUID(nonce); b.writeVarLong(revision); b.writeUtf(message, 64); b.writeByteArray(wire); });
        return true;
    }
}
