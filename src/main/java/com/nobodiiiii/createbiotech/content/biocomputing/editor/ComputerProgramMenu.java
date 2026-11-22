package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaChunk;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaImportSession;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPage;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPageRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaStoreRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.PeripheralCaptureService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusWiringService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusWiringSession;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Authority-owning program editor menu; opening data contains only an unguessable session nonce. */
public final class ComputerProgramMenu extends AbstractContainerMenu implements WiringMenu, com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu {
	@Nullable private final ClusterMemberEditorAccess clusterAccess;
	private final UUID nonce;
	@Nullable private final SmartBlockEntity serverOwner;
	@Nullable private final ServerPlayer serverPlayer;
	@Nullable private final ComputerBehaviour computer;
	@Nullable private final ProgramEditSession editSession;
	@Nullable private final ComputerControlSession controlSession;
	@Nullable private XBusWiringSession wiringSession;
    @Nullable private final SpeakerMediaImportSession mediaImport;
    private long mediaIntent;
    @Nullable private SpeakerMediaPage mediaPage;

    public Optional<SpeakerMediaPage> mediaPage() { return Optional.ofNullable(mediaPage); }
    public void requestMediaPage(int page) {
        mediaPage = null;
        CBPackets.sendToServer(new SpeakerMediaPageRequest(containerId, nonce, ++mediaIntent, page));
    }
    public long beginMediaUpload() { return ++mediaIntent; }
    public void sendMediaChunk(long intent, String name, int total, int offset, byte[] bytes) {
        CBPackets.sendToServer(new SpeakerMediaChunk(containerId, nonce, intent, name, total, offset, bytes));
    }
    public void accept(SpeakerMediaPage response) {
        if (serverOwner == null && response.containerId() == containerId && nonce.equals(response.nonce())
            && response.intent() == mediaIntent) mediaPage = response;
    }
    private boolean mediaSessionMatches(ServerPlayer player, int id, UUID session) {
        return mediaImport != null && player == serverPlayer && player.containerMenu == this
            && id == containerId && nonce.equals(session);
    }
    private boolean mayImportMedia(ServerPlayer player) {
        if (computer == null || !stillValid(player) || !player.isAlive() || !player.mayBuild() || player.isSpectator()) return false;
        var pos = serverOwner.getBlockPos();
        var level = player.serverLevel();
        return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player, pos);
    }
    public void handle(ServerPlayer player, SpeakerMediaPageRequest request) {
        if (mediaSessionMatches(player, request.containerId(), request.nonce()))
            mediaImport.page(player, request, mayImportMedia(player) ? computer : null);
    }
    public void handle(ServerPlayer player, SpeakerMediaChunk request) {
        if (mediaSessionMatches(player, request.containerId(), request.nonce()))
            mediaImport.chunk(player, request, mayImportMedia(player) ? computer : null);
    }
    public void storeMedia(UUID file, String port, net.minecraft.core.BlockPos library, net.minecraft.core.BlockPos track,
            int slot, String volume, String entry) {
        mediaPage = null;
        CBPackets.sendToServer(new SpeakerMediaStoreRequest(containerId, nonce, ++mediaIntent, file, port, library, track, slot, volume, entry));
    }
    public void handle(ServerPlayer player, SpeakerMediaStoreRequest request) {
        if (mediaSessionMatches(player, request.containerId(), request.nonce()))
            mediaImport.store(player, request, mayImportMedia(player) ? computer : null);
    }
    /** Manual media transfers need this tick's physical Cluster state, not the earlier player tick. */
    public void advanceMediaStore(ServerPlayer player) {
        if (mediaImport != null && mediaImport.storing() && player == serverPlayer && player.containerMenu == this)
            mediaImport.advanceStore(player, mayImportMedia(player) ? computer : null);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (serverPlayer != null && mediaImport != null && mediaImport.uploading())
            mediaImport.expireUpload(serverPlayer, mayImportMedia(serverPlayer) ? computer : null);
    }
	private boolean loadHandled;
	private long latestReadIntent;
    private boolean memberPanelAccess;
    boolean hasPanelAccess() { return memberPanelAccess; }
	@Nullable private ProgramEditorResponse loadedSource;
	private long latestIntent;
	private long responseVersion;
    private long latestDraftIntent, handledDraftIntent, draftResponseVersion;
    private long draftBaseRevision = -1;
    private ProgramDraftResponse draftResponse;
    private boolean draftPending, draftConflict;
    public Optional<ProgramDraftResponse> draftResponse() { return Optional.ofNullable(draftResponse); }
    public long draftResponseVersion() { return draftResponseVersion; }
    public boolean draftPending() { return draftPending; }
    public boolean draftLoaded() { return latestDraftIntent == 0 || draftResponse != null && draftResponse.code().equals("ready"); }
    public boolean canSaveDraft() { return !draftPending && !draftConflict && draftBaseRevision >= 0 && response.isPresent(); }
    public void requestSavedDraft() { requestSavedDraft(CBPackets::sendToServer); }
    void requestSavedDraft(java.util.function.Consumer<ProgramDraftRequest> sender) {
        if (serverOwner != null || latestDraftIntent != 0) return;
        draftPending = true;
        sender.accept(new ProgramDraftRequest(containerId, nonce, ++latestDraftIntent, 0, false, new byte[0]));
    }
    public void saveDraft(ProgramDocument document) { saveDraft(document, CBPackets::sendToServer); }
    void saveDraft(ProgramDocument document, java.util.function.Consumer<ProgramDraftRequest> sender) {
        if (serverOwner != null || !canSaveDraft()) throw new IllegalStateException("Draft is not ready to save");
        byte[] bytes = ProgramDocumentWire.encode(document, response.orElseThrow().archiveLimits());
        draftPending = true;
        sender.accept(new ProgramDraftRequest(containerId, nonce, ++latestDraftIntent, draftBaseRevision, true, bytes));
    }
    public void accept(ProgramDraftResponse incoming) {
        if (incoming.containerId() != containerId || !incoming.nonce().equals(nonce) || incoming.intent() != latestDraftIntent
            || draftResponse != null && draftResponse.intent() == incoming.intent()) return;
        draftPending = false; draftResponse = incoming; draftResponseVersion++;
        if (incoming.code().equals("ready") || incoming.code().equals("saved")) draftBaseRevision = incoming.revision();
        if (incoming.code().equals("stale")) draftConflict = true; // Do not bless a revision this editor never loaded.
    }
    public void handle(ServerPlayer player, ProgramDraftRequest request) {
        if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
            || !request.nonce().equals(nonce) || !stillValid(player) || computer == null || request.intent() <= handledDraftIntent) return;
        handledDraftIntent = request.intent();
        String code; String detail = ""; long revision = 0; byte[] document = new byte[0];
        try {
            var current = computer.savedDraft(); revision = current.revision();
            if (!request.save()) { code = "ready"; document = current.document(); }
            else {
                code = computer.saveDraft(request.revision(), request.document()) ? "saved" : "stale";
                revision = computer.savedDraft().revision();
            }
        } catch (IllegalArgumentException | com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException error) {
            code = "invalid"; detail = trim(String.valueOf(error.getMessage()));
        }
        CBPackets.sendToPlayer(new ProgramDraftResponse(containerId, nonce, request.intent(), revision, code, detail, document), player);
    }
    @Nullable private Boolean nativeIsa1b;
    public Boolean nativeIsa1b() { return nativeIsa1b; }
	private Optional<ProgramEditorResponse> response = Optional.empty();
	@Nullable private ProgramEditorResponse installedSource;
	private long installedSourceVersion;
	@Nullable private Long sourceAttemptRevision;
	private long sourceReadIntent;
	private boolean sourceReadPending;
	private long latestControlIntent, controlResponseVersion;
	@Nullable private ComputerControlSession.Action latestControlAction;
	private DataValue.RecordValue debuggerInputs = new DataValue.RecordValue(java.util.Map.of());
	private Optional<ProgramStatusResponse> controlResponse = Optional.empty();
	private Optional<DataValue.RecordValue> status = Optional.empty();

	public ComputerProgramMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
		this(containerId, buffer.readUUID(), null, null);
        memberPanelAccess = buffer.readBoolean();
	}

	private ComputerProgramMenu(int containerId, UUID nonce, @Nullable SmartBlockEntity owner,
		@Nullable ServerPlayer player) {
		this(containerId, nonce, owner, player, null);
	}
	private ComputerProgramMenu(int containerId, UUID nonce, @Nullable SmartBlockEntity owner,
		@Nullable ServerPlayer player, @Nullable ClusterMemberEditorAccess access) {
		super(CBMenuTypes.COMPUTER_PROGRAM.get(), containerId);
		clusterAccess = access;
		this.nonce = Objects.requireNonNull(nonce); serverOwner = owner; serverPlayer = player;
		computer = owner == null ? null : owner.getBehaviour(ComputerBehaviour.TYPE);
		editSession = owner == null ? null : new ProgramEditSession(nonce);
        mediaImport = owner == null ? null : new SpeakerMediaImportSession(containerId, nonce);
		controlSession = owner == null ? null : new ComputerControlSession(nonce);
	}

	public void requestInitial() {
		if (serverOwner != null || latestIntent != 0) return;
		latestIntent = 1;
		CBPackets.sendToServer(new ProgramEditorRequest(containerId, nonce, latestIntent, 0, false, false, new byte[0]));
	}

	public void submit(ProgramArchive archive, ComputerRuntime.MemoryPolicy policy) { submit(ProgramDocument.of(archive), policy); }

	public void submit(ProgramDocument document, ComputerRuntime.MemoryPolicy policy) {
		Objects.requireNonNull(document); Objects.requireNonNull(policy);
		if (serverOwner != null || response.isEmpty()) return;
		ProgramEditorResponse authority = response.orElseThrow();
		if (installedSource != null && installedSource.revision() > authority.revision()) authority = installedSource;
		long intent = ++latestIntent;
		try {
			byte[] bytes = ProgramDocumentWire.encode(document, authority.archiveLimits());
			CBPackets.sendToServer(new ProgramEditorRequest(containerId, nonce, intent, authority.revision(), true,
				policy == ComputerRuntime.MemoryPolicy.RESET, bytes));
		} catch (IllegalArgumentException tooLarge) {
			accept(new ProgramEditorResponse(containerId, nonce, intent, authority.revision(), "too_large",
				trim(String.valueOf(tooLarge.getMessage())), authority.hardware(), authority.archiveLimits(), new byte[0], authority.nativeIsa1b(), authority.pins()));
		}
	}

	public long responseVersion() { return responseVersion; }
	public Optional<ProgramEditorResponse> response() { return response; }
	public Optional<ProgramEditorResponse> installedSource(long revision) {
		return Optional.ofNullable(installedSource).filter(value -> value.revision() == revision);
	}
	public long installedSourceVersion() { return installedSourceVersion; }
	public void requestInstalledSource(long revision) { requestInstalledSource(revision, CBPackets::sendToServer); }
	void requestInstalledSource(long revision, java.util.function.Consumer<ProgramEditorRequest> sender) {
		if (serverOwner != null || response.isEmpty() || sourceReadPending
			|| latestIntent != response.orElseThrow().intent() && latestIntent != sourceReadIntent
			|| Objects.equals(sourceAttemptRevision, revision) || installedSource(revision).isPresent()) return;
		sourceAttemptRevision = revision; sourceReadPending = true; sourceReadIntent = ++latestIntent;
		sender.accept(new ProgramEditorRequest(containerId, nonce, sourceReadIntent, revision, false, false, new byte[0]));
	}
	public void accept(ProgramEditorResponse incoming) {
		Objects.requireNonNull(incoming);
		if (!acceptsResponse(containerId, nonce, latestIntent, incoming)) return;
		nativeIsa1b = incoming.nativeIsa1b();
		if (incoming.code().equals("ready")) { installedSource = incoming; installedSourceVersion++; }
		if (sourceReadPending && incoming.intent() != sourceReadIntent) sourceAttemptRevision = null;
		sourceReadPending = false;
		// A source-only read is not an editor result: do not replace the draft or installation notice.
		if (sourceReadIntent != 0 && incoming.intent() == sourceReadIntent) return;
		response = Optional.of(incoming); responseVersion++;
	}
	static boolean acceptsResponse(int containerId, UUID nonce, long latestIntent, ProgramEditorResponse incoming) {
		return incoming.containerId() == containerId && incoming.nonce().equals(nonce)
			&& incoming.intent() == latestIntent;
	}

	public void handle(ServerPlayer player, ProgramEditorRequest request) {
		if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
			|| !request.nonce().equals(nonce) || !stillValid(player) || computer == null || editSession == null) return;
		if (!request.install()) {
			if (request.intent() <= latestReadIntent) return;
			latestReadIntent = request.intent();
			long revision = computer.programRevision();
			if (loadHandled && request.revision() != revision) {
				sendEditor(player, request, "stale", "Source revision changed", new byte[0]); return;
			}
			loadHandled = true;
			// Keep only one bounded archive. A cancelled UI read can retry without re-encoding or installing it.
			if (loadedSource != null && loadedSource.revision() == revision) {
				CBPackets.sendToPlayer(loadedSource.reissueSource(request.intent(), computer.hardware()), player);
			} else sendLoad(player, request);
			return;
		}
		HardwareSelection selected = computer.hardware();
		var result = editSession.submit(request.nonce(), request.intent(), request.revision(), computer::programRevision,
			() -> computer.installDocument(ProgramDocumentWire.decode(request.archive(), selected.limits().archive()),
				request.resetMemory() ? ComputerRuntime.MemoryPolicy.RESET : ComputerRuntime.MemoryPolicy.PRESERVE));
		result.ifPresent(answer -> {
			invalidateWiring();
			HardwareSelection current = computer.hardware();
			CBPackets.sendToPlayer(new ProgramEditorResponse(containerId, nonce, request.intent(), answer.revision(),
				answer.code(), trim(answer.detail()), current.limits().hardware(), current.limits().archive(), new byte[0], ProgramEditorResponse.capability(current), current.limits().pins()), player);
		});
	}

	private void sendLoad(ServerPlayer player, ProgramEditorRequest request) {
		HardwareSelection selected = computer.hardware();
		if (!selected.ready()) {
			sendEditor(player, request, "unavailable", selected.status().name().toLowerCase(java.util.Locale.ROOT), new byte[0]);
			return;
		}
		try {
			var document = computer.document();
			String code = loadCode(true, document.isPresent(), computer.diagnostic().isPresent());
			if (document.isEmpty()) {
				sendEditor(player, request, code, code.equals("ready") ? "" : "No installed program snapshot", new byte[0]);
				return;
			}
			byte[] bytes = ProgramDocumentWire.encode(document.orElseThrow(), selected.limits().archive());
			String detail = computer.diagnostic().map(d -> d.code() + ": " + d.message()).orElse("");
			sendEditor(player, request, "ready", detail, bytes);
		} catch (IllegalArgumentException tooLarge) {
			sendEditor(player, request, "too_large", String.valueOf(tooLarge.getMessage()), new byte[0]);
		}
	}
	static String loadCode(boolean ready, boolean hasDocument, boolean diagnostic) {
		return !ready || diagnostic && !hasDocument ? "unavailable" : "ready";
	}

	private void sendEditor(ServerPlayer player, ProgramEditorRequest request, String code, String detail, byte[] archive) {
		var selected = computer.hardware();
		var reply = new ProgramEditorResponse(containerId, nonce, request.intent(), computer.programRevision(),
			code, trim(detail), selected.limits().hardware(), selected.limits().archive(), archive, ProgramEditorResponse.capability(selected), selected.limits().pins());
		if (code.equals("ready")) loadedSource = reply;
		CBPackets.sendToPlayer(reply, player);
	}

	public void control(ComputerControlSession.Action action, DataValue.RecordValue inputs) {
		Objects.requireNonNull(action); Objects.requireNonNull(inputs); if (serverOwner != null) return;
		long revision = controlResponse.map(ProgramStatusResponse::revision)
			.orElseGet(() -> response.map(ProgramEditorResponse::revision).orElse(0L));
        control(action,inputs,revision);
    }
    /** Apply against the hardware snapshot actually displayed, not an intervening menu reply. */
    void tuneRadio(long viewedRevision, RadioControlData.Tune tune) {
        if (serverOwner == null) control(ComputerControlSession.Action.CONFIGURE_RADIO, tune.encode(), viewedRevision);
    }
    void configureWitchCarrier(long revision, WitchCarrierStage.Setting setting) {
        if (serverOwner == null) control(ComputerControlSession.Action.CONFIGURE_WITCH_CARRIER, setting.encode(), revision);
    }
    void configureWitchFilter(long revision, WitchReceiverFilter.Setting setting) {
        if (serverOwner == null) control(ComputerControlSession.Action.CONFIGURE_WITCH_FILTER, setting.encode(), revision);
    }
    void connectReceiveLoop(long revision, RadioControlData.CarrierInput selection) {
        if (serverOwner == null) control(ComputerControlSession.Action.CONFIGURE_RECEIVE_LOOP, selection.encode(), revision);
    }
    void connectCarrier(long viewedRevision, RadioControlData.CarrierInput selection) {
        if (serverOwner == null) control(ComputerControlSession.Action.CONFIGURE_CARRIER_INPUT, selection.encode(), viewedRevision);
    }
    private void control(ComputerControlSession.Action action, DataValue.RecordValue inputs, long revision) {
		long intent = ++latestControlIntent;
		latestControlAction = action;
		try {
			byte[] bytes = action.carriesInputs() ? ProgramEditorWire.encodeValue(inputs, response.map(ProgramEditorResponse::archiveLimits).orElseThrow()) : new byte[0];
			CBPackets.sendToServer(new ProgramControlRequest(containerId, nonce, intent, revision, action, bytes));
		} catch (IllegalArgumentException | java.util.NoSuchElementException invalid) {
			accept(new ProgramStatusResponse(containerId, nonce, intent, revision, "invalid", trim(String.valueOf(invalid.getMessage())), new byte[0]));
		}
	}
    private long keyStateIntent;
    void terminalKeyState(long view, long bits) {
        if (serverOwner != null || response.isEmpty()) return;
        long revision = controlResponse.map(ProgramStatusResponse::revision).orElseGet(() -> response.orElseThrow().revision());
        var sample = new DataValue.RecordValue(java.util.Map.of("view", new DataValue.IntegerValue(view), "bits", new DataValue.IntegerValue(bits)));
        try {
            CBPackets.sendToServer(new ProgramControlRequest(containerId, nonce, ++keyStateIntent, revision,
                ComputerControlSession.Action.TERMINAL_KEY_STATE, ProgramEditorWire.encodeValue(sample, response.orElseThrow().archiveLimits())));
        } catch (IllegalArgumentException invalid) { /* Without capacity, no refresh is sent and the sample expires. */ }
    }
    void terminalKey(long view,String key) { control(ComputerControlSession.Action.TERMINAL_KEY,new TerminalKeyPress(view,key).encode()); }
	public void terminalClick(long view, String instance) { control(ComputerControlSession.Action.TERMINAL_CLICK, new TerminalClick(view, instance).encode()); }
	public void terminalSubmit(long view, String instance, String text) { control(ComputerControlSession.Action.TERMINAL_SUBMIT, new TerminalSubmit(view, instance, text).encode()); }
    public void terminalClaim(long view, int index) { control(ComputerControlSession.Action.TERMINAL_CLAIM, new TerminalTaskClaim(view, index).encode()); }
    public Optional<DataValue.RecordValue> terminalClaimStatus() {
        if (latestControlAction != ComputerControlSession.Action.TERMINAL_CLAIM || controlResponse.isEmpty() || controlResponse.orElseThrow().intent() != latestControlIntent) return Optional.empty();
        return status.filter(value -> value.fields().containsKey("terminal_claim"));
    }
	static boolean acceptsControlResponse(int containerId, UUID nonce, long latestIntent, ProgramStatusResponse incoming) {
		return incoming.containerId() == containerId && incoming.nonce().equals(nonce) && incoming.intent() == latestIntent;
	}
	public long controlResponseVersion() { return controlResponseVersion; }
	@Override public long wiringResponseVersion() { return controlResponseVersion(); }
	@Override public Optional<WiringMenu.Reply> wiringResponse() {
		return controlResponse().map(reply -> new WiringMenu.Reply(reply.intent(), reply.code(), reply.detail(), wiringStatus()));
	}
	@Override public void wiringControl(WiringMenu.Action action, DataValue.RecordValue inputs) {
		control(switch (action) {
			case READ -> ComputerControlSession.Action.READ_WIRING;
			case ADD -> ComputerControlSession.Action.ADD_WIRING;
			case REMOVE -> ComputerControlSession.Action.REMOVE_WIRING;
			case SET_ENABLED -> ComputerControlSession.Action.SET_WIRING_ENABLED;
		}, inputs);
	}
	@Override public void leaveWiring() { control(ComputerControlSession.Action.READ_ENDPOINT, new DataValue.RecordValue(java.util.Map.of())); }
    boolean taskDebugReply() {
        return latestControlAction == ComputerControlSession.Action.READ_TASK_DEBUG
            && controlResponse.filter(reply -> reply.intent() == latestControlIntent).isPresent();
    }
	public Optional<ProgramStatusResponse> controlResponse() { return controlResponse; }
	public Optional<DataValue.RecordValue> status() { return status; }
	@Override public void requestLog() { control(ComputerControlSession.Action.READ_LOG, new DataValue.RecordValue(java.util.Map.of())); }
	@Override public long logResponseVersion() { return controlResponseVersion; }
	@Override public Optional<com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu.Reply> logResponse() {
		if (latestControlAction != ComputerControlSession.Action.READ_LOG) return Optional.empty();
		return controlResponse.filter(reply -> reply.intent() == latestControlIntent).map(reply ->
			new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu.Reply(reply.code(), status));
	}
	public Optional<DataValue.RecordValue> terminalStatus() {
		if (latestControlAction != ComputerControlSession.Action.READ_TERMINAL || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		return status.filter(value -> value.fields().containsKey("terminal"));
	}
	public Optional<DataValue.RecordValue> terminalClickStatus() {
		if (latestControlAction != ComputerControlSession.Action.TERMINAL_CLICK || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent) return Optional.empty();
		return status.filter(value -> value.fields().containsKey("terminal_click"));
	}
	public Optional<DataValue.RecordValue> terminalSubmitStatus() {
		if (latestControlAction != ComputerControlSession.Action.TERMINAL_SUBMIT || controlResponse.isEmpty() || controlResponse.orElseThrow().intent() != latestControlIntent) return Optional.empty();
		return status.filter(value -> value.fields().containsKey("terminal_submit"));
	}
	public Optional<DataValue.RecordValue> endpointStatus() { return endpointReply(false); }
    public Optional<DataValue.RecordValue> instrumentStatus() {
        if(latestControlAction==null || !latestControlAction.instrument() || controlResponse.isEmpty()
            || controlResponse.orElseThrow().intent()!=latestControlIntent || !controlResponse.orElseThrow().code().equals("ok"))return Optional.empty();
        return status.filter(value->value.fields().containsKey("instrument"));
    }
    public Optional<DataValue.RecordValue> radioStatus() {
        if (latestControlAction == null || !latestControlAction.radio() || controlResponse.isEmpty()
            || controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
        return status;
    }
	public Optional<DataValue.RecordValue> serialStatus() {
		if (latestControlAction == null || !latestControlAction.serial() || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		return status;
	}
    public Optional<DataValue.RecordValue> signalStatus() {
        if (latestControlAction == null || !latestControlAction.signal() || controlResponse.isEmpty()
            || controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
        return status;
    }
	public Optional<DataValue.RecordValue> peerDirectory() { return endpointReply(true); }
	public Optional<DataValue.RecordValue> wiringStatus() {
		if (latestControlAction == null || !latestControlAction.wiring() || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		return status.filter(value -> value.fields().containsKey("wiring"));
	}
	public Optional<DataValue.RecordValue> communicationStatus() {
		if (latestControlAction != ComputerControlSession.Action.READ_COMMUNICATION || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		return status.filter(value -> value.fields().containsKey("communication"));
	}
	private Optional<DataValue.RecordValue> endpointReply(boolean discovery) {
		if (latestControlAction == null || !latestControlAction.endpoint() || latestControlAction.wiring() || latestControlAction == ComputerControlSession.Action.READ_COMMUNICATION
			|| (latestControlAction == ComputerControlSession.Action.DISCOVER_PEERS) != discovery
			|| controlResponse.isEmpty() || controlResponse.orElseThrow().intent() != latestControlIntent
			|| !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		return status.filter(value -> value.fields().containsKey(discovery ? "peers" : "endpoint"));
	}
	public Optional<DataValue.RecordValue> periodicStatus() {
		if (latestControlAction == null || !latestControlAction.periodic() || controlResponse.isEmpty()
			|| controlResponse.orElseThrow().intent() != latestControlIntent || !controlResponse.orElseThrow().code().equals("ok"))
			return Optional.empty();
		return status.filter(value -> value.fields().containsKey("periodic"));
	}
	/** Menu-local draft only: neither captures nor manual edits mutate the installed Computer. */
	public DataValue.RecordValue debuggerInputs() { return debuggerInputs; }
	public void rememberDebuggerInputs(DataValue.RecordValue inputs) { debuggerInputs = Objects.requireNonNull(inputs); }
    boolean readerMenuCapturePending() {
        return latestControlAction == ComputerControlSession.Action.READ_PERIPHERALS
            && controlResponse.filter(reply -> reply.intent() == latestControlIntent).isEmpty();
    }
	Optional<ProgramStatusResponse> readerMenuCaptureFailure() {
		if (latestControlAction != ComputerControlSession.Action.READ_PERIPHERALS) return Optional.empty();
		return controlResponse.filter(reply -> reply.intent() == latestControlIntent && !reply.code().equals("ok"));
	}
	public Optional<DataValue.RecordValue> capturedInputs() {
		if (latestControlAction == null || latestControlAction.capturedInputName().isEmpty()
			|| controlResponse.isEmpty() || controlResponse.orElseThrow().intent() != latestControlIntent
			|| !controlResponse.orElseThrow().code().equals("ok")) return Optional.empty();
		String root = latestControlAction.capturedInputName().orElseThrow();
		return status.map(value -> value.fields().get("captured_inputs"))
			.filter(DataValue.RecordValue.class::isInstance).map(DataValue.RecordValue.class::cast)
			.map(captured -> captured.fields().get(root)).filter(DataValue.RecordValue.class::isInstance)
			.map(value -> new DataValue.RecordValue(java.util.Map.of(root, value)));
	}
	public void accept(ProgramStatusResponse incoming) {
		Objects.requireNonNull(incoming);
		if (!acceptsControlResponse(containerId, nonce, latestControlIntent, incoming)) return;
		controlResponse = Optional.of(incoming); controlResponseVersion++; status = Optional.empty();
		byte[] encoded = incoming.status();
		if (encoded.length == 0) return;
		try {
			var limits = response.map(ProgramEditorResponse::archiveLimits).orElseThrow();
			boolean capture = latestControlAction != null && latestControlAction.capturedInputName().isPresent();
			var value = capture ? BoundedEditorEnvelope.CAPTURE.decode(encoded, limits) : ProgramEditorWire.decodeValue(encoded, limits);
			if (!(value instanceof DataValue.RecordValue record)) throw new IllegalArgumentException("status is not a record");
			status = Optional.of(record);
		} catch (IllegalArgumentException | java.util.NoSuchElementException invalid) { status = Optional.empty(); }
	}
	public void handle(ServerPlayer player, ProgramControlRequest request) {
		if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
			|| !request.nonce().equals(nonce) || !stillValid(player) || computer == null || controlSession == null) return;
		var limits = computer.hardware().limits().archive();
        if (request.action() == ComputerControlSession.Action.TERMINAL_KEY_STATE) {
            try {
                if (ProgramEditorWire.decodeValue(request.inputs(), limits) instanceof DataValue.RecordValue sample)
                    controlSession.updateKeyState(request.intent(), request.revision(), sample, computer);
            } catch (IllegalArgumentException invalid) { /* Invalid samples cannot renew the lease. */ }
            return;
        }
		var result = controlSession.apply(request.nonce(), request.intent(), request.revision(), request.action(), () -> {
			if (request.action() == ComputerControlSession.Action.READ_WIRING)
				return wiringSession().read(request.intent(), limits);
			if (request.action() == ComputerControlSession.Action.DISCOVER_PEERS)
				return clusterAccess == null ? PeerDiscovery.discover(serverOwner, player) : PeerDiscovery.discover(clusterAccess, player);
			if (request.action() == ComputerControlSession.Action.READ_NETWORK)
				return new DataValue.RecordValue(java.util.Map.of("network", clusterAccess == null
					? PeerDiscovery.topology(serverOwner, player) : PeerDiscovery.topology(clusterAccess, player)));
			if (request.action() == ComputerControlSession.Action.READ_PERIPHERALS)
				return new DataValue.RecordValue(java.util.Map.of("peripherals", clusterAccess == null
					? PeripheralCaptureService.capture(serverOwner, player) : PeripheralCaptureService.capture(clusterAccess, player)));
			var value = ProgramEditorWire.decodeValue(request.inputs(), limits);
			if (!(value instanceof DataValue.RecordValue record)) throw new IllegalArgumentException("inputs must be a named record");
			if (request.action() == ComputerControlSession.Action.READ_PANEL_SOCKET)
                return com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess.page(clusterAccess,player,record);
            if (request.action() == ComputerControlSession.Action.ADD_WIRING) {
				var add = WiringControlData.Add.decode(record);
				return wiringSession().add(request.intent(), add.view(), add.name(), add.localPin(), add.peer(), limits);
			}
			if (request.action() == ComputerControlSession.Action.REMOVE_WIRING) {
				var remove = WiringControlData.Remove.decode(record);
				return wiringSession().remove(request.intent(), remove.view(), remove.name(), limits);
			}
			if (request.action() == ComputerControlSession.Action.SET_WIRING_ENABLED) {
				var enabled = WiringControlData.SetEnabled.decode(record);
				return wiringSession().setEnabled(request.intent(), enabled.view(), enabled.name(), enabled.enabled(), limits);
			}
			return record;
		}, computer, () -> {
			if (clusterAccess == null) PeerDiscovery.authorize(serverOwner, player);
			else PeerDiscovery.authorize(clusterAccess, player);
		}, config -> com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess.authorize(clusterAccess, player, config),
            config -> com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalGatewayAccess.authorize(clusterAccess, player, config.pin(), config.alias()));
		result.ifPresent(answer -> {
			var response = ProgramStatusResponse.fromAnswer(containerId, nonce, request.intent(), answer, limits);
			if (!request.action().wiring() || !response.code().equals("ok")) invalidateWiring();
			if (request.action() == ComputerControlSession.Action.READ_TERMINAL && !response.code().equals("ok"))
				controlSession.invalidateTerminalPublication();
			CBPackets.sendToPlayer(response, player);
		});
	}
	private XBusWiringSession wiringSession() {
		if (wiringSession == null) wiringSession = clusterAccess == null
			? XBusWiringService.open(serverOwner, serverPlayer, () -> serverPlayer.containerMenu == this && stillValid(serverPlayer))
			: XBusWiringService.open(clusterAccess, serverPlayer, () -> serverPlayer.containerMenu == this && stillValid(serverPlayer));
		return wiringSession;
	}
	private void invalidateWiring() { if (wiringSession != null) wiringSession.invalidate(); }
    @Override public void removed(Player player) {
        if (computer != null) computer.updateTerminalKeys(nonce, 0);
        if (mediaImport != null) mediaImport.close();
        super.removed(player); invalidateWiring();
    }
	@Override public boolean stillValid(Player player) {
		if (serverOwner == null) return true;
		if (player != serverPlayer || !(serverOwner.getLevel() instanceof ServerLevel level)
			|| player.level() != level || serverOwner.isRemoved()) return false;
		if (clusterAccess != null) return clusterAccess.stillValid(serverPlayer);
		var pos = serverOwner.getBlockPos();
		return level.hasChunkAt(pos) && level.getBlockEntity(pos) == serverOwner
			&& player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
	}
	@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

	public static OpeningProvider provider(SmartBlockEntity owner) { return new OpeningProvider(owner); }
	public static OpeningProvider provider(ClusterMemberEditorAccess access) {
		return new OpeningProvider(Objects.requireNonNull(access));
	}
	public static final class OpeningProvider implements MenuProvider {
		private final SmartBlockEntity owner;
		@Nullable private final ClusterMemberEditorAccess access;
		private final UUID nonce = UUID.randomUUID();
		private OpeningProvider(SmartBlockEntity owner) { this.owner = Objects.requireNonNull(owner); access = null; }
		private OpeningProvider(ClusterMemberEditorAccess access) {
			this.access = access; owner = access.computer();
		}
		@Override public Component getDisplayName() { return Component.translatable("create_biotech.biocomputing_program.title"); }
		@Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
			if (!(player instanceof ServerPlayer server)) throw new IllegalArgumentException("server player required");
			return new ComputerProgramMenu(containerId, nonce, owner, server, access);
		}
		public void writeOpeningData(RegistryFriendlyByteBuf buffer) { buffer.writeUUID(nonce); buffer.writeBoolean(access != null); }
	}
	private static String trim(String value) {
		if (value == null) return "";
		return value.length() <= ProgramEditorResponse.MAX_DETAIL ? value : value.substring(0, ProgramEditorResponse.MAX_DETAIL);
	}
}








