package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;

/** Frozen player view. Interaction requests resolve against the observed layout, never a client-supplied binding. */
public final class TerminalUiMenu extends AbstractContainerMenu {
    private static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(4096, 65536, 262144);
    private record Snapshot(UUID session, long revision, List<SerialControlAccess.Binding> choices, OptionalInt ccReader) {}
    private final UUID nonce;
    private final String code;
    private final DataValue.RecordValue view;
    private final TerminalUiBlockEntity terminal;
    private final ServerPlayer owner;
    private final Snapshot observed;
    private boolean consumed;

    public TerminalUiMenu(int id, Inventory inventory, RegistryFriendlyByteBuf wire) {
        this(id, wire.readUUID(), wire.readUtf(64),
            (DataValue.RecordValue)ProgramEditorWire.decodeValue(wire.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS),
            null, null, null);
    }

    private TerminalUiMenu(int id, UUID nonce, String code, DataValue.RecordValue view,
            TerminalUiBlockEntity terminal, ServerPlayer owner, Snapshot observed) {
        super(CBMenuTypes.TERMINAL_UI.get(), id);
        this.nonce = nonce;
        this.code = code;
        this.view = view;
        this.terminal = terminal;
        this.owner = owner;
        this.observed = observed;
    }

    DataValue.RecordValue view() { return view; }
    String code() { return code; }
    private boolean managing() { return view.fields().get("managing") instanceof DataValue.BooleanValue value && value.value(); }

    TerminalUiRequest request(TerminalUiRequest.Action action, int choice, String instance, String text) {
        return new TerminalUiRequest(containerId, nonce, action, choice, instance, text);
    }

    void handle(ServerPlayer player, TerminalUiRequest request) {
        if (consumed || player != owner || player.containerMenu != this || !stillValid(player)
            || request.containerId() != containerId || !nonce.equals(request.nonce())) return;
        consumed = true;
        var service = terminal.getBehaviour(TerminalUiSerialBehaviour.TYPE);
        boolean manage = managing();
        String result = "ok";
        try {
            switch (request.action()) {
                case REFRESH -> {}
                case VIEW -> manage = false;
                case MANAGE -> {
                    manage = service.mayManage(player);
                    if (!manage) result = "access_denied";
                }
                case GRANT -> {
                    if (!manage) result = "access_denied";
                    else if (request.choice() >= observed.choices().size()) result = "changed";
                    else result = service.configure(player, observed.session(), Optional.of(observed.choices().get(request.choice())));
                }
                case REVOKE -> result = manage ? service.configure(player, observed.session(), Optional.empty()) : "access_denied";
                case CC_ALLOW_READ, CC_REVOKE_READ -> result = manage
                    ? terminal.configureCcReader(player, observed.ccReader(),
                        request.action() == TerminalUiRequest.Action.CC_ALLOW_READ ? OptionalInt.of(request.choice()) : OptionalInt.empty())
                    : "access_denied";
                case CC_ALLOW_PUBLISH, CC_REVOKE_PUBLISH -> result = manage
                    ? terminal.configureCcPublisher(player, observed.session(),
                        request.action() == TerminalUiRequest.Action.CC_ALLOW_PUBLISH ? OptionalInt.of(request.choice()) : OptionalInt.empty())
                    : "access_denied";
                case CLICK, SUBMIT -> result = manage ? "invalid_interaction"
                    : service.interact(player, observed.session(), observed.revision(), request.instance(),
                        request.action() == TerminalUiRequest.Action.SUBMIT ? Optional.of(request.text()) : Optional.empty());
            }
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = String.valueOf(failure.getMessage()); }
        open(player, terminal, manage, result);
    }

    @Override public boolean stillValid(Player player) {
        return terminal == null || player == owner && terminal.getBehaviour(TerminalUiSerialBehaviour.TYPE).mayUse(owner);
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    static boolean open(ServerPlayer player, TerminalUiBlockEntity terminal, boolean manage, String result) {
        var service = terminal.getBehaviour(TerminalUiSerialBehaviour.TYPE);
        if (!service.mayUse(player)) return false;
        var state = terminal.state();
        boolean mayManage = service.mayManage(player);
        manage &= mayManage;
        List<SerialControlAccess.Binding> choices = List.of();
        if (manage) {
            try {
                var discovered = service.discover(player);
                if (discovered.size() <= TerminalUiRequest.MAX_CHOICES) choices = List.copyOf(discovered);
                else if (result.equals("ok")) result = "choice_capacity";
            } catch (CableDiscovery.DiscoveryException failure) {
                if (result.equals("ok")) result = failure.code();
            } catch (IllegalArgumentException failure) {
                if (result.equals("ok")) result = String.valueOf(failure.getMessage());
            }
        }
        var fields = new LinkedHashMap<>(service.diagnostics().fields());
        fields.put("managing", new DataValue.BooleanValue(manage));
        fields.put("may_manage", new DataValue.BooleanValue(mayManage));
        fields.put("interactive", new DataValue.BooleanValue(service.canInteract()));
        fields.put("revision", number(state.revision()));
        fields.put("events", number(state.events().size()));
        fields.put("input_chars", number(TerminalUiSerialBehaviour.limits().inputChars()));
        fields.put("cc_available", new DataValue.BooleanValue(TerminalUiBlockEntity.ccInstalled()));
        fields.put("cc_reader", manage && terminal.ccReader().isPresent() ? number(terminal.ccReader().getAsInt()) : DataValue.NullValue.INSTANCE);
        fields.put("cc_publisher", manage && state.ccPublisher().isPresent() ? number(state.ccPublisher().getAsInt()) : DataValue.NullValue.INSTANCE);
        fields.put("page", manage ? DataValue.NullValue.INSTANCE
            : state.page().<DataValue>map(Page::encode).orElse(DataValue.NullValue.INSTANCE));
        fields.put("choices", new DataValue.ListValue(choices.stream().<DataValue>map(SerialControlAccess.Binding::summary).toList()));
        byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(record(fields), VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            choices = List.of();
            fields.put("page", DataValue.NullValue.INSTANCE);
            fields.put("choices", new DataValue.ListValue(List.of()));
            bytes = ProgramEditorWire.encodeValue(record(fields), VIEW_LIMITS);
            result = "view_limit";
        }
        var view = record(fields);
        var observed = new Snapshot(state.session(), state.revision(), choices, terminal.ccReader());
        var data = bytes;
        var nonce = UUID.randomUUID();
        var code = result.length() <= 64 ? result : "provider_failure";
        return player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return terminal.getBlockState().getBlock().getName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player actor) {
                return new TerminalUiMenu(id, nonce, code, view, terminal, player, observed);
            }
        }, wire -> {
            wire.writeUUID(nonce);
            wire.writeUtf(code, 64);
            wire.writeByteArray(data);
        }).isPresent();
    }
}
