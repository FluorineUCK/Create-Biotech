package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.stockTicker.*;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationValues.*;

public final class StockApplicationMenu extends AbstractContainerMenu {
    static final ArchiveLimits VIEW_LIMITS = new ArchiveLimits(32768, 131072, 262144);
    private final StockKeeperApplicationBinding frontend;
    private final StockApplicationBehaviour physical;
    private UUID frontendRevision;
    private final ServerPlayer owner;
    private UUID nonce;
    private String code;
    private DataValue.RecordValue view;
    private List<StockApplicationBehaviour.Candidate> choices;
    private List<StockKeeperApplicationBinding.Selection> adapters;
    private Capture capture;
    private UUID materialSession;
    private final NativeDraft nativeDraft;
    /** Never recapture this identity on refresh: one preview is one possible admission. */
    private record NativeDraft(Request request, UUID frontendRevision) {}

    /** Immutable confirmation capability, shared by the real menu and headless connected tests. */
    record Capture(int container, UUID nonce, UUID actor, Fence fence, long sequence, int pending, Set<String> keys) {
        static Capture of(int container, UUID nonce, UUID actor, State state, List<Entry> catalog) {
            return new Capture(container, nonce, actor, state.fence(), state.nextSequence(), state.inputs().size(),
                Set.copyOf(catalog.stream().map(Entry::key).toList()));
        }
        boolean matches(UUID player, StockApplicationMenuRequest packet) {
            return container == packet.container() && nonce.equals(packet.nonce()) && actor.equals(player);
        }
        Optional<Request> confirm(UUID player, StockApplicationMenuRequest packet) {
            if (!matches(player, packet) || packet.action() != StockApplicationMenuRequest.Action.CONFIRM
                || !keys.contains(packet.key()) || packet.quantity() <= 0) return Optional.empty();
            return Optional.of(new Request(fence, sequence, List.of(new Selection(packet.key(), packet.quantity())), packet.address()));
        }
    }
    private record Projection(String code, DataValue.RecordValue view, byte[] bytes, State state,
                              List<Entry> entries, List<StockApplicationBehaviour.Candidate> choices,
                              List<StockKeeperApplicationBinding.Selection> adapters) {}

    public StockApplicationMenu(int id, Inventory inventory, RegistryFriendlyByteBuf b) {
        super(CBMenuTypes.STOCK_APPLICATION.get(), id);
        nonce = b.readUUID(); code = b.readUtf(64);
        view = (DataValue.RecordValue)ProgramEditorWire.decodeValue(b.readByteArray(ProgramEditorWire.MAX_BYTES), VIEW_LIMITS);
        frontend = null; physical = null; owner = null; choices = List.of(); adapters = List.of(); nativeDraft = null;
    }
    private StockApplicationMenu(int id, StockKeeperApplicationBinding frontend, StockApplicationBehaviour physical,
                                 ServerPlayer owner, UUID nonce, Projection projection, NativeDraft nativeDraft) {
        super(CBMenuTypes.STOCK_APPLICATION.get(), id);
        this.frontend = frontend; this.physical = physical; this.owner = owner; this.nonce = nonce;
        this.nativeDraft = nativeDraft;
        publish(projection);
    }
    private void publish(Projection projection) {
        code = projection.code(); view = projection.view(); choices = projection.choices(); adapters = projection.adapters();
        frontendRevision = frontend == null ? null : frontend.revision();
        materialSession = physical == null ? null : physical.materialSession();
        capture = projection.state() == null ? null : Capture.of(containerId, nonce, owner.getUUID(), projection.state(), projection.entries());
    }
    public DataValue.RecordValue view() { return view; }
    public String code() { return code; }
    UUID nonce() { return nonce; }
    public StockApplicationMenuRequest request(StockApplicationMenuRequest.Action action, int choice, String key, long quantity, String address) {
        return new StockApplicationMenuRequest(containerId, nonce, action, choice, key, quantity, address);
    }
    void accept(StockApplicationMenuResponse response) {
        if (owner != null || response.container() != containerId || !response.previous().equals(nonce)) return;
        var snapshot = response.view();
        nonce = response.next(); code = response.code(); view = snapshot;
    }

    void handle(ServerPlayer actor, StockApplicationMenuRequest request) {
        if (actor != owner || actor.containerMenu != this || !stillValid(actor)
            || request.container() != containerId || !request.nonce().equals(nonce)) return;
        if (nativeDraft != null && !Set.of(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,
            StockApplicationMenuRequest.Action.REFRESH,StockApplicationMenuRequest.Action.BACK).contains(request.action())) return;
        String result = "ok";
        var service = physical != null ? physical : frontend.resolve(actor);
        try {
            if (request.action() == StockApplicationMenuRequest.Action.BACK) {
                if (frontend != null) back(actor, frontend.ticker()); else actor.closeContainer();
                return;
            }
            if (frontend != null && request.action() != StockApplicationMenuRequest.Action.REFRESH
                && !frontendRevision.equals(frontend.revision())) result = "changed";
            else switch (request.action()) {
                case REFRESH -> {}
                case BIND -> {
                    int index = request.choice();
                    if (physical != null) result = capture == null || index < 0 || index >= choices.size() ? "changed"
                        : physical.configure(actor,capture.fence(),Optional.of(choices.get(index)));
                    else result = index < 0 || index >= adapters.size() ? "changed" : frontend.select(actor,Optional.of(adapters.get(index)));
                }
                case REVOKE -> result = physical != null ? capture == null ? "changed" : physical.configure(actor,capture.fence(),Optional.empty())
                    : frontend.select(actor,Optional.empty());
                case DISCARD -> result = physical == null || capture == null || request.choice() != capture.pending() ? "changed"
                    : physical.discard(actor,capture.fence(),capture.sequence(),capture.pending());
                case GRANT_MATERIALS, REVOKE_MATERIALS -> result = physical == null || capture == null ? "changed"
                    : physical.configureMaterials(actor, capture.fence(), materialSession,
                        request.action() == StockApplicationMenuRequest.Action.GRANT_MATERIALS ? Optional.of(request.address()) : Optional.empty());
                case CONFIRM -> result = frontend == null || service == null || capture == null ? "changed"
                    : capture.confirm(actor.getUUID(),request).map(order -> service.confirm(actor,order)).orElse("invalid_input");
                case CONFIRM_NATIVE -> result = nativeDraft == null || service == null || frontend == null
                    || !nativeDraft.frontendRevision().equals(frontend.revision()) ? "changed"
                    : request.choice() != -1 || !request.key().isEmpty() || request.quantity() != 0 || !request.address().isEmpty()
                        ? "invalid_input" : service.confirm(actor,nativeDraft.request());
                default -> { return; }
            }
        } catch (CableDiscovery.DiscoveryException failure) { result = failure.code(); }
        catch (IllegalArgumentException failure) { result = "invalid_configuration"; }
        if (!stillValid(actor) || actor.containerMenu != this) return;
        var old = nonce;
        var projection = project(frontend,physical,actor,result,nativeDraft);
        nonce = UUID.randomUUID(); publish(projection);
        CBPackets.sendToPlayer(new StockApplicationMenuResponse(containerId, old, nonce, code, projection.bytes()), actor);
    }

    private static Projection project(StockKeeperApplicationBinding frontend, StockApplicationBehaviour physical, ServerPlayer actor, String result, NativeDraft draft) {
        var service = physical != null ? physical : frontend.resolve(actor);
        var state = service == null ? null : service.state();
        var manage = physical != null ? physical.mayManage(actor) : frontend.mayManage(actor);
        var choices = List.<StockApplicationBehaviour.Candidate>of();
        var adapters = List.<StockKeeperApplicationBinding.Selection>of();
        String discovery = "ok";
        if (manage) try {
            if (physical != null) choices = physical.discover(actor);
            else adapters = frontend.discover(actor);
        } catch (CableDiscovery.DiscoveryException failure) { discovery = failure.code(); }
        catch (IllegalArgumentException failure) { discovery = "endpoint_limit"; }
        var entries = draft == null && physical == null && state != null && state.open() ? state.catalog() : List.<Entry>of();
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("catalog", entries(entries));
        fields.put("native_order",draft == null ? DataValue.NullValue.INSTANCE : draft.request().nativeOrder().orElseThrow());
        fields.put("native_address",text(draft == null ? "" : draft.request().address()));
        fields.put("status", text(physical != null ? physical.status() : frontend.status(actor)));
        fields.put("last", text(service == null ? "idle" : service.last()));
        fields.put("manage", new DataValue.BooleanValue(manage));
        fields.put("physical", new DataValue.BooleanValue(physical != null));
        fields.put("may_withdraw", new DataValue.BooleanValue(physical != null ? physical.canWithdraw(actor) : manage));
        if (physical != null) {
            fields.put("may_grant_materials", new DataValue.BooleanValue(physical.mayGrantMaterials(actor)));
            fields.put("material_address", text(physical.materialAddress()));
            fields.put("material_status", text(physical.materialStatus()));
        }
        fields.put("pending", number(state == null ? 0 : state.inputs().size()));
        fields.put("capacity", number(service == null ? 0 : service.limits().inputs()));
        fields.put("address_chars", number(service == null ? 0 : service.limits().addressChars()));
        fields.put("face", text(service == null ? "—" : service.face()));
        fields.put("connection", text(service == null ? "closed" : service.device().status(StockApplicationHost.PORT).connection()));
        fields.put("target", text(service == null ? "—" : service.output().getBlockPos().toShortString()));
        fields.put("discovery", text(discovery));
        fields.put("binding", service == null ? DataValue.NullValue.INSTANCE : service.binding().<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE));
        fields.put("selected_adapter", frontend == null ? DataValue.NullValue.INSTANCE
            : frontend.selection().<DataValue>map(StockApplicationMenu::adapter).orElse(DataValue.NullValue.INSTANCE));
        fields.put("choices", new DataValue.ListValue(physical != null
            ? choices.stream().<DataValue>map(c -> c.binding().summary()).toList()
            : adapters.stream().<DataValue>map(StockApplicationMenu::adapter).toList()));
        var view = new DataValue.RecordValue(fields);
        byte[] bytes;
        try { bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS); }
        catch (IllegalArgumentException tooLarge) {
            fields.put("catalog", new DataValue.ListValue(List.of())); fields.put("choices", new DataValue.ListValue(List.of()));
            fields.put("discovery", text("view_limit")); entries = List.of(); choices = List.of(); adapters = List.of();
            view = new DataValue.RecordValue(fields); bytes = ProgramEditorWire.encodeValue(view, VIEW_LIMITS);
            if (result.equals("ok")) result = "view_limit";
        }
        return new Projection(result, view, bytes, state, entries, choices, adapters);
    }
    private static DataValue.RecordValue adapter(StockKeeperApplicationBinding.Selection selection) {
        return record("kind",text("adapter"),"terminal",text(selection.adapter().toShortString()),"port",text("s0"),
            "member",text(selection.target().position().toShortString()));
    }

    public static StockControls controls(ServerPlayer actor, UUID nonce) {
        var frontend = nativeFrontend(actor);
        boolean ready = frontend != null && "ready".equals(frontend.status(actor));
        boolean configure = false;
        if (!ready && frontend != null && frontend.mayManage(actor)) {
            // A broken existing binding must remain repairable. Unbound keepers without a
            // usable adapter show no extra controls, even for the network administrator.
            configure = frontend.selection().isPresent();
            if (!configure) try { configure = !frontend.discover(actor).isEmpty(); }
            catch (IllegalArgumentException ignored) { }
        }
        return new StockControls(actor.containerMenu.containerId, nonce, ready || configure, ready);
    }
    public static void openFromNative(ServerPlayer actor) {
        var frontend = nativeFrontend(actor);
        if (frontend != null) open(actor,frontend,null);
    }
    private static StockKeeperApplicationBinding nativeFrontend(ServerPlayer actor) {
        if (!(actor.containerMenu instanceof com.simibubi.create.foundation.gui.menu.MenuBase<?> menu)
            || menu instanceof com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestMenu
            || menu.getType() != com.simibubi.create.AllMenuTypes.STOCK_KEEPER_REQUEST.get() || !menu.stillValid(actor)
            || !(menu.contentHolder instanceof StockTickerBlockEntity ticker)) return null;
        var frontend = ticker.getBehaviour(StockKeeperApplicationBinding.TYPE);
        return frontend != null && frontend.mayView(actor) ? frontend : null;
    }
    static String openNativeOrder(ServerPlayer actor, DataValue.NbtValue order, String address) {
        var frontend = nativeFrontend(actor);
        if (frontend == null) return "access_denied";
        var service = frontend.resolve(actor);
        if (service == null) return frontend.status(actor);
        // A preview is not admission. Keep transient device failures visible in this page;
        // the screen disables confirmation and service.confirm rechecks the actual authority.
        if (address.isBlank()) return "invalid_address";
        if (address.length() > service.limits().addressChars()) return "capacity";
        var checked = StockNativeOrder.recapture(order,actor.registryAccess(),service.limits());
        var state = service.state();
        var draft = new NativeDraft(Request.nativeOrder(state.fence(),state.nextSequence(),address,checked),frontend.revision());
        open(actor,frontend,null,draft);
        return "ok";
    }
    public static boolean openPhysical(ServerPlayer actor, StockLinkOutputBlockEntity output) {
        var service = output.getBehaviour(StockApplicationBehaviour.TYPE);
        if (service == null || !service.mayView(actor)) return false;
        open(actor,null,service); return true;
    }
    private static void open(ServerPlayer actor, StockKeeperApplicationBinding frontend, StockApplicationBehaviour physical) {
        open(actor,frontend,physical,null);
    }
    private static void open(ServerPlayer actor, StockKeeperApplicationBinding frontend, StockApplicationBehaviour physical, NativeDraft draft) {
        var projection = project(frontend,physical,actor,"ok",draft); var nonce = UUID.randomUUID();
        actor.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable(physical == null
                ? "create_biotech.stock_application.title" : "block.create_biotech.biocomputing_stock_link_output"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
                return new StockApplicationMenu(id,frontend,physical,actor,nonce,projection,draft);
            }
        }, b -> { b.writeUUID(nonce); b.writeUtf(projection.code(),64); b.writeByteArray(projection.bytes()); });
    }
    private static void back(ServerPlayer actor, StockTickerBlockEntity ticker) {
        var network = ticker.behaviour.freqId;
        actor.openMenu(ticker.new RequestMenuProvider(), b -> {
            b.writeBoolean(ticker.behaviour.mayAdministrate(actor) && Create.LOGISTICS.isLockable(network));
            b.writeBoolean(Create.LOGISTICS.isLocked(network)); b.writeBlockPos(ticker.getBlockPos());
        });
        ticker.getRecentSummary().divideAndSendTo(actor, ticker.getBlockPos());
    }
    @Override public boolean stillValid(Player player) { return owner == null || player == owner && (physical == null ? frontend.mayView(owner) : physical.mayView(owner)); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
