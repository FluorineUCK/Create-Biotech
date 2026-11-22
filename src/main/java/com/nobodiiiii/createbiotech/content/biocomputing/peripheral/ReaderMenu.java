package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlockEntity;
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

/** One-shot configuration snapshot. Selection remains local until explicit grant; no sample or program starts here. */
public final class ReaderMenu extends AbstractContainerMenu {
    private static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(4096, 65536, 262144);
    private final UUID nonce;
    private final long revision;
    private final DataValue.RecordValue view;
    private final String code;
    private final BlockReaderBlockEntity reader;
    private final ServerPlayer owner;
    private final List<SerialControlAccess.Binding> choices;
    private final List<String> catalog;
    private boolean consumed;
    public ReaderMenu(int id, Inventory inventory, RegistryFriendlyByteBuf b) {
        this(id, b.readUUID(), b.readVarLong(), b.readUtf(64),
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(b.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS), null, null, List.of(), List.of());
    }
    private ReaderMenu(int id, UUID nonce, long revision, String code, DataValue.RecordValue view,
            BlockReaderBlockEntity reader, ServerPlayer owner, List<SerialControlAccess.Binding> choices, List<String> catalog) {
        super(CBMenuTypes.BLOCK_READER.get(), id); this.nonce = nonce; this.revision = revision; this.code = code; this.view = view;
        this.reader = reader; this.owner = owner; this.choices = List.copyOf(choices); this.catalog = List.copyOf(catalog);
    }
    public DataValue.RecordValue view() { return view; }
    public String code() { return code; }
    public ReaderRequest request(ReaderRequest.Action action, int choice, List<Integer> ids) {
        return new ReaderRequest(containerId, nonce, revision, action, choice, ids);
    }
    public void handle(ServerPlayer player, ReaderRequest request) {
        if (consumed || player != owner || player.containerMenu != this || !stillValid(player) || !request.matches(containerId, nonce, revision)) return;
        consumed = true; var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE); String result = "ok";
        try {
            if (request.action() == ReaderRequest.Action.REVOKE) result = service.configure(player, revision, Optional.empty(), Set.of());
            else if (request.action() == ReaderRequest.Action.GRANT) {
                if (request.choice() >= choices.size() || request.components().stream().anyMatch(i -> i >= catalog.size())) result = "changed";
                else result = service.configure(player, revision, Optional.of(choices.get(request.choice())),
                    request.components().stream().map(catalog::get).collect(java.util.stream.Collectors.toSet()));
            }
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = String.valueOf(failure.getMessage()); }
        open(player, reader, result);
    }
    @Override public boolean stillValid(Player player) { return reader == null || player == owner && reader.getBehaviour(ReaderSerialBehaviour.TYPE).mayManage(owner); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    public static boolean open(ServerPlayer player, BlockReaderBlockEntity reader, String result) {
        var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE); if (!service.mayManage(player)) return false;
        List<SerialControlAccess.Binding> candidates; String code = result;
        try { candidates = service.discover(player); }
        catch (CableDiscovery.DiscoveryException failure) { candidates = List.of(); code = failure.code(); }
        catch (IllegalArgumentException failure) { candidates = List.of(); code = String.valueOf(failure.getMessage()); }
        List<String> ids = ReadComponents.registry().ids().stream().sorted().toList();
        var descriptors = ReadComponents.registry().describe();
        var fields = new LinkedHashMap<String, DataValue>(service.status().fields());
        if (candidates.size() > ReaderRequest.MAX_CHOICES || ids.size() > ReaderRequest.MAX_CHOICES) { candidates = List.of(); ids = List.of(); code = "view_limit"; }
        fields.put("choices", new DataValue.ListValue(candidates.stream().<DataValue>map(SerialControlAccess.Binding::summary).toList()));
        fields.put("current", new DataValue.IntegerValue(service.binding().isPresent() ? candidates.indexOf(service.binding().orElseThrow()) : -1));
        fields.put("catalog", new DataValue.ListValue(ids.stream().<DataValue>map(id -> {
            var descriptor = (DataValue.RecordValue)descriptors.field(id);
            return new DataValue.RecordValue(Map.of("id", new DataValue.TextValue(id), "label", descriptor.field("label"), "additional", descriptor.field("additional")));
        }).toList()));
        DataValue.RecordValue view = new DataValue.RecordValue(fields); byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            candidates = List.of(); ids = List.of(); fields.put("choices", new DataValue.ListValue(List.of())); fields.put("catalog", new DataValue.ListValue(List.of()));
            fields.put("current", new DataValue.IntegerValue(-1)); view = new DataValue.RecordValue(fields); bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); code = "view_limit";
        }
        var captured = view; var selected = candidates; var names = ids; var wire = bytes; var nonce = UUID.randomUUID();
        long revision = service.revision(); String message = code.length() <= 64 ? code : "provider_failure";
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable("block.create_biotech.biocomputing_block_reader"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player actor) {
                return new ReaderMenu(id, nonce, revision, message, captured, reader, player, selected, names);
            }
        }, b -> { b.writeUUID(nonce); b.writeVarLong(revision); b.writeUtf(message, 64); b.writeByteArray(wire); });
        return true;
    }
}
