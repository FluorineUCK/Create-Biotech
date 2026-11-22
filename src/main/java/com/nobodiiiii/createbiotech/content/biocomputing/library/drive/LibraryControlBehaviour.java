package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** One explicit local controller, independent of the transitional autonomous Library service. */
public final class LibraryControlBehaviour extends BlockEntityBehaviour {
    public static final BehaviourType<LibraryControlBehaviour> TYPE = new BehaviourType<>();
    public static final String ROOT = "BiocomputingLibraryControl";
    private long revision; private Optional<LibraryControlBinding> binding = Optional.empty(); private Tag invalid;
    private int removalDepth;
    public LibraryControlBehaviour(SmartBlockEntity owner) { super(owner); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    public boolean available() {
        if (removalDepth != 0 || blockEntity.isRemoved() || !(blockEntity instanceof LibraryCoreHost host) || !host.libraryIdentityReady()
            || !(blockEntity.getLevel() instanceof ServerLevel level) || !level.shouldTickBlocksAt(blockEntity.getBlockPos())) return false;
        var pos = blockEntity.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return level.getWorldBorder().isWithinBounds(pos) && chunk != null && chunk.getBlockEntity(pos) == blockEntity;
    }
    private void authorize(ServerPlayer actor) {
        if (!available() || actor.serverLevel() != blockEntity.getLevel() || !actor.isAlive()) throw new LibraryBookAccess.Failure("unavailable");
        var pos = blockEntity.getBlockPos();
        if (!actor.mayBuild() || actor.isSpectator() || !actor.serverLevel().mayInteract(actor, pos)
            || actor.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) throw new LibraryBookAccess.Failure("denied");
        if (invalid != null) throw new LibraryBookAccess.Failure("invalid_configuration");
    }
    private LibraryControlWorld world(ServerPlayer actor) { return new LibraryControlWorld((ServerLevel)blockEntity.getLevel(), blockEntity.getBlockPos(), actor); }
    public List<LibraryControlBinding> discover(ServerPlayer actor) { authorize(actor); return world(actor).discover(); }
    /** Trusted menu only: additionally requires the Library's live application edit permission. */
    public String configure(ServerPlayer actor, long expected, Optional<LibraryControlBinding> next) {
        authorize(actor); Objects.requireNonNull(next);
        if (revision != expected) return "changed";
        if (revision == Long.MAX_VALUE) return "version_exhausted";
        if (!next.equals(binding)) {
            var drive = blockEntity.getBehaviour(LibraryDriveBehaviour.TYPE);
            if (drive != null && !drive.controlChangeBlocker().isEmpty()) return drive.controlChangeBlocker();
            var serial = blockEntity.getBehaviour(LibrarySerialBehaviour.TYPE);
            if (serial != null && serial.busy()) return "busy";
        }
        next.ifPresent(target -> world(actor).verify(target));
        if (next.equals(binding)) return "configured";
        var serial = blockEntity.getBehaviour(LibrarySerialBehaviour.TYPE);
        if (serial != null && serial.enabled()) serial.configure(false); // A new delegate never inherits an open control port.
        binding = next; revision++; blockEntity.setChanged(); return "configured";
    }
    /** Same lectern edit gate/revision as delegate configuration; binding by itself never opens serial. */
    public String configureSerial(ServerPlayer actor, long expected, boolean enabled) {
        authorize(actor);
        if (expected != revision) return "changed";
        if (revision == Long.MAX_VALUE) return "version_exhausted";
        var serial = blockEntity.getBehaviour(LibrarySerialBehaviour.TYPE);
        if (serial == null) return "unavailable";
        if (enabled) world(actor).verify(binding.orElseThrow(() -> new LibraryBookAccess.Failure("unbound")));
        if (serial.enabled() == enabled) return "configured";
        serial.configure(enabled); revision++; blockEntity.setChanged(); return "configured";
    }
    public DataValue.RecordValue status() {
        String code = invalid != null ? "invalid_configuration" : !available() ? "unavailable" : binding.isEmpty() ? "unbound" : "ready";
        if (code.equals("ready")) try { world(null).verify(binding.orElseThrow()); }
        catch (LibraryBookAccess.Failure failure) { code = failure.code(); }
        catch (CableDiscovery.DiscoveryException failure) { code = failure.code(); }
        catch (RuntimeException failure) { code = "provider_failure"; }
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("revision", new DataValue.IntegerValue(revision)); fields.put("code", new DataValue.TextValue(code));
        fields.put("binding", binding.<DataValue>map(LibraryControlBinding::summary).orElse(DataValue.NullValue.INSTANCE));
        var serial = blockEntity.getBehaviour(LibrarySerialBehaviour.TYPE);
        if (serial != null) fields.put("serial", serial.status());
        var drive = blockEntity.getBehaviour(LibraryDriveBehaviour.TYPE);
        if (drive != null) fields.put("drive", drive.status());
        return new DataValue.RecordValue(fields);
    }
    /** Host/transport adapter only: never accepts a caller identity supplied inside a data frame. */
    public void requireController(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour caller) {
        if (!available()) throw new LibraryBookAccess.Failure("unavailable");
        if (invalid != null) throw new LibraryBookAccess.Failure("invalid_configuration");
        var selected = binding.orElseThrow(() -> new LibraryBookAccess.Failure("unbound"));
        if (caller == null || caller.blockEntity.getLevel() != blockEntity.getLevel()
            || !caller.blockEntity.getBlockPos().equals(selected.member())) throw new LibraryBookAccess.Failure("denied");
        var level = (ServerLevel)blockEntity.getLevel();
        var chunk = level.getChunkSource().getChunkNow(selected.member().getX() >> 4, selected.member().getZ() >> 4);
        if (chunk == null || chunk.getBlockEntity(selected.member()) != caller.blockEntity
            || caller.blockEntity.getBehaviour(ComputerBehaviour.TYPE) != caller) throw new LibraryBookAccess.Failure("member_changed");
        world(null).verify(selected);
    }
    /** Preflight a real authorized controller interface, without admitting a command or changing a route. */
    public void requireSerialPort(ComputerBehaviour caller, String name) {
        requireController(caller);
        requireSelectedSerialPort(caller, port -> port.name().equals(name), "disconnected");
    }
    private void requireSelectedSerialPort(ComputerBehaviour caller, java.util.function.Predicate<SerialDevice.Attachment> matches, String absent) {
        var selected = binding.orElseThrow();
        var port = caller.serialDevice().attachments().stream().filter(matches).findFirst()
            .orElseThrow(() -> new LibraryBookAccess.Failure(absent));
        var connection = SerialGatewayAccess.resolve((ServerLevel)blockEntity.getLevel(), selected.member(), port);
        if (!connection.position().equals(selected.terminal()) || connection.side() != selected.face())
            throw new LibraryBookAccess.Failure("denied");
    }
    ComputerBehaviour serialController(SharedSerialChannel.Port source) {
        var selected = binding.orElseThrow(() -> new LibraryBookAccess.Failure("unbound"));
        var level = (ServerLevel)blockEntity.getLevel();
        var chunk = level.getChunkSource().getChunkNow(selected.member().getX() >> 4, selected.member().getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(selected.member()) instanceof SmartBlockEntity owner)) throw new LibraryBookAccess.Failure("member_unavailable");
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE); requireController(computer);
        requireSelectedSerialPort(computer, port -> port.key().equals(source), "denied");
        return computer;
    }
    public Optional<Tag> archive() {
        if (invalid != null) return Optional.of(invalid.copy());
        if (revision == 0 && binding.isEmpty()) return Optional.empty();
        var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putLong("Revision", revision);
        binding.ifPresent(target -> tag.put("Binding", target.save())); return Optional.of(tag);
    }
    /** Preserve the captured owner during synchronous drop/placement callbacks, without persisting a lock. */
    public static <T> T duringRemoval(net.minecraft.world.level.block.entity.BlockEntity owner, java.util.function.Supplier<T> action) {
        var control = owner instanceof SmartBlockEntity smart ? smart.getBehaviour(TYPE) : null;
        if (control == null) return action.get();
        control.removalDepth++;
        try { return action.get(); } finally { control.removalDepth--; }
    }
    public void restore(Tag tag) {
        revision = 0; binding = Optional.empty(); invalid = tag == null ? null : tag.copy();
        if (tag == null) return;
        try {
            if (!(tag instanceof CompoundTag root) || !root.getAllKeys().equals(root.contains("Binding")
                ? Set.of("Version", "Revision", "Binding") : Set.of("Version", "Revision"))
                || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1
                || !root.contains("Revision", Tag.TAG_LONG) || root.getLong("Revision") < 0
                || root.contains("Binding") && (!root.contains("Binding", Tag.TAG_COMPOUND) || root.getLong("Revision") == 0)) return;
            var loaded = root.contains("Binding") ? Optional.of(LibraryControlBinding.load(root.getCompound("Binding"))) : Optional.<LibraryControlBinding>empty();
            revision = root.getLong("Revision"); binding = loaded; invalid = null;
        } catch (IllegalArgumentException malformed) { /* Retain exact unknown/corrupt archive; never reset to an open binding. */ }
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { if (!clientPacket) restore(tag.get(ROOT)); }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (!clientPacket) archive().ifPresent(data -> tag.put(ROOT, data));
    }
}
