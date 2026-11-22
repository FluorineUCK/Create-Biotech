package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** Device-owned librarian work. Trusted local transport adapter only, not an instant public RPC API.
 * Commands require the actual delegate; accepted work subsequently uses only this device's clock. */
public final class LibraryDriveBehaviour extends BlockEntityBehaviour {
    public static final BehaviourType<LibraryDriveBehaviour> TYPE = new BehaviourType<>();
    public static final String ROOT = "BiocomputingLibraryDrive";
    private Tag retained;
    private LibraryReadHead.State state = LibraryReadHead.State.empty();
    private LibraryDriveLimits decodedLimits;
    private String diagnostic;
    private String runtimeStatus = "";
    private long lastTick = Long.MIN_VALUE;
    private LibraryServiceWorldSource worldSource;
    private LibraryDriveLimits sourceLimits;
    private int sourceBookBytes;

    public LibraryDriveBehaviour(SmartBlockEntity owner) { super(owner); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    private LibraryDriveLimits limits() { return CBConfigs.SERVER.biocomputing.libraryDrive(CBConfigs.SERVER_SPEC.isLoaded()); }
    private LibraryControlBehaviour control() { return blockEntity.getBehaviour(LibraryControlBehaviour.TYPE); }
    private boolean available() { return control() != null && control().available(); }

    private boolean synchronize() {
        var limits = limits();
        if (limits.equals(decodedLimits)) return diagnostic == null;
        decodedLimits = limits;
        try {
            if (retained == null) state = LibraryReadHead.State.empty();
            else if (retained instanceof CompoundTag tag) state = LibraryReadHeadCodec.decode(tag, limits);
            else throw new LibraryBookAccess.Failure("invalid_checkpoint");
            diagnostic = null; return true;
        } catch (LibraryBookAccess.Failure failure) { diagnostic = failure.code(); }
        catch (ArchiveException failure) { diagnostic = failure.code().name().toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException failure) { diagnostic = "invalid_checkpoint"; }
        // Exact original archive survives errors and later configuration recovery.
        return false;
    }
    public Optional<LibraryReadHead.State> snapshot() { return synchronize() ? Optional.of(state) : Optional.empty(); }
    public String controlChangeBlocker() {
        if (!synchronize()) return diagnostic;
        return LibraryReadHead.busy(state) ? "busy" : state.result().isPresent() ? "result_pending" : "";
    }
    private void authorize(ComputerBehaviour caller) {
        if (!available()) throw new LibraryBookAccess.Failure("unavailable");
        control().requireController(caller);
        if (!synchronize()) throw new LibraryBookAccess.Failure(diagnostic);
    }
    private void publish(LibraryReadHead.State next) {
        var limits = limits();
        var encoded = LibraryReadHeadCodec.encode(next, limits); // Complete preflight, before any publication.
        state = next; retained = encoded; decodedLimits = limits; diagnostic = null;
        blockEntity.setChanged();
    }
    /** Serial dequeue/reply and the mechanical continuation publish before the single dirty callback. */
    void acceptSerial(ComputerBehaviour caller, LibraryDriveCommand command,
        com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Prepared receive) {
        authorize(caller);
        var limits = limits(); var before = state;
        LibraryDriveCommand.Transition next;
        try { next = command.apply(before, source(), limits, new LibraryDriveCommand.Admission(
            ((LibraryCoreHost)blockEntity).libraryDeviceId(), LibraryServiceTicks.stamp(((ServerLevel)blockEntity.getLevel()).getServer())),receive.maximumReplyBytes()); }
        catch (LibraryBookAccess.Failure failure) {
            next = new LibraryDriveCommand.Transition(before, command.reply(before.sequence(), failure.code(), DataValue.NullValue.INSTANCE));
        }
        catch (ArchiveException failure) {
            next = new LibraryDriveCommand.Transition(before, command.reply(before.sequence(), failure.code().name().toLowerCase(Locale.ROOT), DataValue.NullValue.INSTANCE));
        }
        var encoded = LibraryReadHeadCodec.encode(next.state(), limits);
        com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Prepared exchange;
        try { exchange = receive.replying(next.reply()); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Failure tooLarge) {
            // No oversized result is discarded, and no operation is admitted without a finite reply.
            next = new LibraryDriveCommand.Transition(before, command.reply(before.sequence(), tooLarge.code().name().toLowerCase(Locale.ROOT), DataValue.NullValue.INSTANCE));
            encoded = LibraryReadHeadCodec.encode(before, limits); exchange = receive.replying(next.reply());
        }
        if (state != before || !limits.equals(limits()) || !exchange.publish()) throw new LibraryBookAccess.Failure("changed");
        state = next.state(); retained = encoded; decodedLimits = limits; diagnostic = null;
        blockEntity.setChanged();
        releaseAcknowledged(before, next.state());
    }
    public long seek(ComputerBehaviour caller, LibraryTrackAddress track) {
        authorize(caller); publish(LibraryReadHead.seek(state, track, source(), limits())); return state.sequence();
    }
    public long select(ComputerBehaviour caller, int slot) {
        authorize(caller); publish(LibraryReadHead.select(state, slot, source(), limits())); return state.sequence();
    }
    public long readEntry(ComputerBehaviour caller, String entry) {
        authorize(caller); publish(LibraryReadHead.read(state, entry, source(), limits())); return state.sequence();
    }
    /** Output adapter borrows completed DATA from the controller's live serial read; no new read, move or ACK. */
    public DataValue readyDataForOutput(ComputerBehaviour caller, String pin, long sequence, LibraryMutation.Version version) {
        if (!available()) throw new LibraryBookAccess.Failure("unavailable");
        control().requireSerialPort(caller, pin);
        var serial = blockEntity.getBehaviour(LibrarySerialBehaviour.TYPE);
        if (serial == null || !serial.enabled()) throw new LibraryBookAccess.Failure("library_closed");
        if (!synchronize()) throw new LibraryBookAccess.Failure(diagnostic);
        return LibraryReadHead.readyData(state, source(), sequence, version);
    }
    /** Non-destructive observation. A lost transport reply does not implicitly consume the result. */
    public LibraryReadHead.Result result(ComputerBehaviour caller, long sequence) {
        authorize(caller); return LibraryReadHead.take(state, sequence, limits()).result();
    }
    /** Explicit consumption, not "emission completed". Transport must supply its durable receipt boundary. */
    public void acknowledge(ComputerBehaviour caller, long sequence) {
        authorize(caller); var before = state; publish(LibraryReadHead.take(state, sequence, limits()).state());
        releaseAcknowledged(before, state);
    }
    private void releaseAcknowledged(LibraryReadHead.State before, LibraryReadHead.State after) {
        if (before.result().filter(r -> r.code().equals("written")).isEmpty() || after.result().isPresent()) return;
        try { source().releaseWrite(before.track().orElseThrow(), before.slot(), before.writing().orElseThrow().operation()); }
        catch (RuntimeException unavailable) { /* ACK is durable first; unavailable media retain their charged receipt. */ }
    }
    /** Both device and transitional FIFO verdicts become visible before one dirty callback. */
    public boolean sealWrites(LibraryServiceStore store, LibraryWriteService.Source legacySource,
        LibraryServiceLimits serviceLimits, LibraryWriteBatch now) {
        if (!synchronize()) return false;
        var before = state; var work = before.writing().orElse(null);
        var upload = before.transfer().filter(t -> t.commit().isPresent()).orElse(null);
        var admission = work != null ? work.admission() : upload != null ? upload.commit().orElseThrow().admission() : null;
        if (admission == null || !admission.verdict().isEmpty() || !admission.batch().closedAt(now)) return true;
        try {
            var limits = limits(); Optional<LibraryWriteBatch.Target> target = Optional.empty();
            try {
                if (upload != null) {
                    source().verifyTransfer(before.track().orElseThrow(),before.slot(),upload);
                    target=Optional.of(new LibraryWriteBatch.Target(admission.batch(),upload.volume(),upload.entry()));
                } else {
                    var prepared = source().prepareWrite(before.track().orElseThrow(), before.slot(), work.operation());
                    if (!prepared.replay()) target = Optional.of(new LibraryWriteBatch.Target(work.admission().batch(),
                        work.operation().mutation().volume(), work.operation().mutation().entry()));
                }
            } catch (RuntimeException invalid) { /* Final completion reports its actual error without committing. */ }
            var batch = store.prepareBatch(legacySource, serviceLimits, now, target);
            boolean conflict=target.filter(batch::competesWith).isPresent();
            var next = upload != null ? LibraryReadHead.sealTransfer(before,conflict) : LibraryReadHead.withWrite(before, work.seal(conflict));
            var encoded = LibraryReadHeadCodec.encode(next, limits);
            if (state != before || !limits.equals(limits()) || !batch.current()) throw new LibraryBookAccess.Failure("changed");
            batch.publishWithoutCallback();
            state = next; retained = encoded; decodedLimits = limits; runtimeStatus = "";
            blockEntity.setChanged(); return true;
        } catch (RuntimeException failure) { runtimeStatus = LibraryWriteService.failure(failure); return false; }
    }
    @Override public void tick() {
        if (!available() || !synchronize()) return;
        long now = ((ServerLevel)blockEntity.getLevel()).getGameTime();
        if (lastTick == now) return;
        lastTick = now;
        // Received segments consume device time; an empty/processed reservation waits for explicit commands.
        if (!LibraryReadHead.busy(state) || state.reading().filter(LibraryReadTransfer::ready).isPresent()
            || state.transfer().filter(t -> t.remaining() == 0 && t.commit().isEmpty()).isPresent()) { runtimeStatus = ""; return; }
        try {
            var limits = limits(); var before = state;
            var prepared = LibraryReadHead.prepareTick(before, source(), limits);
            var encoded = LibraryReadHeadCodec.encode(prepared.next(), limits);
            var recovery = prepared.recovery().map(s -> LibraryReadHeadCodec.encode(s, limits));
            if (state != before || !limits.equals(limits())) throw new LibraryBookAccess.Failure("changed");
            if (recovery.isPresent()) {
                state = prepared.recovery().orElseThrow(); retained = recovery.orElseThrow(); decodedLimits = limits;
                blockEntity.setChanged(); // A callback failure keeps the uncertain operation, never a false failure reply.
                try { prepared.commit().run(); }
                catch (RuntimeException uncertain) { runtimeStatus = "commit_retry"; return; }
            }
            state = prepared.next(); retained = encoded; decodedLimits = limits; runtimeStatus = "";
            blockEntity.setChanged();
        } catch (RuntimeException failure) { runtimeStatus = LibraryWriteService.failure(failure); }
        // No subtraction from world time: unload/dormancy never creates catch-up work.
    }
    private LibraryReadHead.Source source() {
        var limits = limits(); int bytes = CBConfigs.SERVER.biocomputing.libraryBookBytes(CBConfigs.SERVER_SPEC.isLoaded());
        if (worldSource == null || !limits.equals(sourceLimits) || bytes != sourceBookBytes) {
            closeSource();
            worldSource = new LibraryServiceWorldSource(blockEntity.getLevel(), blockEntity.getBlockPos(), this::available,
                this::scan, limits.archive(), bytes);
            sourceLimits = limits; sourceBookBytes = bytes;
        }
        return worldSource.driveSource(track -> track.resolve(blockEntity.getBlockPos(),
            blockEntity.getBlockState().getValue(LibraryCoreBlock.FACING), scan()), volume -> {
                var service = blockEntity.getBehaviour(LibraryServiceBehaviour.TYPE);
                if (service == null) throw new LibraryBookAccess.Failure("unavailable");
                return service.volumeAccess(volume);
            }, blockEntity instanceof LibraryBlockEntity library ? library.recipePageReader(limits) : null);
    }
    private LibraryStructureScanner.ScanResult scan() {
        if (!available()) throw new LibraryBookAccess.Failure("unavailable");
        return LibraryStructureScanner.scan(new LibraryStructureWorldView(blockEntity.getLevel()), blockEntity.getBlockPos(),
            CBConfigs.SERVER.library.libraryMaxMembers.get(), CBConfigs.SERVER.library.libraryMaxSpan.get());
    }
    /** Metadata only, for the lectern and future public hardware-status operation. */
    public DataValue.RecordValue status() {
        var loaded = snapshot(); var work = loaded.flatMap(LibraryReadHead.State::pending);
        var writing = loaded.flatMap(LibraryReadHead.State::writing);
        String code = diagnostic != null ? diagnostic : !available() ? "unavailable" : !runtimeStatus.isEmpty() ? runtimeStatus : work.isPresent()
            ? work.orElseThrow().action().name().toLowerCase(Locale.ROOT) : loaded.orElseThrow().result().isPresent() ? "result_pending"
            : writing.isPresent() ? writing.orElseThrow().admission().verdict().isEmpty() ? "batch_pending" : "writing"
            : loaded.orElseThrow().reading().isPresent()?loaded.orElseThrow().reading().orElseThrow().status()
            : loaded.orElseThrow().transfer().isPresent() ? loaded.orElseThrow().transfer().orElseThrow().commit().isPresent()
                ? loaded.orElseThrow().transfer().orElseThrow().commit().orElseThrow().restoring() ? "restoring" : "decoding"
                : loaded.orElseThrow().transfer().orElseThrow().remaining() > 0 ? "busy" : "receiving" : "idle";
        return new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(code),
            "result", loaded.flatMap(LibraryReadHead.State::result).<DataValue>map(r -> new DataValue.TextValue(r.code())).orElse(DataValue.NullValue.INSTANCE),
            "sequence", new DataValue.IntegerValue(loaded.map(LibraryReadHead.State::sequence).orElse(0L)),
            "remaining", new DataValue.IntegerValue(work.map(LibraryReadHead.Pending::remainingTicks).orElseGet(() -> writing.map(LibraryWriteWork::remaining)
                .orElseGet(() -> loaded.flatMap(LibraryReadHead.State::transfer).map(LibraryWriteTransfer::remaining)
                    .orElseGet(() -> loaded.flatMap(LibraryReadHead.State::reading).map(LibraryReadTransfer::remaining).orElse(0L))))),
            "track", loaded.flatMap(LibraryReadHead.State::track).<DataValue>map(LibraryTrackAddress::value).orElse(DataValue.NullValue.INSTANCE),
            "slot", new DataValue.IntegerValue(loaded.map(s -> (long)s.slot()).orElse(-1L))));
    }
    public Optional<Tag> archive() { return Optional.ofNullable(retained).map(Tag::copy); }
    public void restore(Tag tag) {
        closeSource(); retained = tag == null ? null : tag.copy(); decodedLimits = null; diagnostic = null; runtimeStatus = "";
        state = LibraryReadHead.State.empty(); lastTick = Long.MIN_VALUE;
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { if (!clientPacket) restore(tag.get(ROOT)); }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (!clientPacket) archive().ifPresent(data -> tag.put(ROOT, data));
    }
    private void closeSource() { if (worldSource != null) worldSource.close(); worldSource = null; sourceLimits = null; }
    @Override public void unload() { closeSource(); }
}
