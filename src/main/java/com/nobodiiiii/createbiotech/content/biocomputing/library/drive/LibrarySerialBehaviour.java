package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** One explicit rear hardware interface. It executes only addressed physical drive commands, never an application. */
public final class LibrarySerialBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<LibrarySerialBehaviour> TYPE = new BehaviourType<>();
    public static final String ROOT = "BiocomputingLibrarySerial";
    private final SerialDevice device;
    private String lastCode = "idle";
    public LibrarySerialBehaviour(SmartBlockEntity owner) {
        super(owner);
        device = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.librarySerial(CBConfigs.SERVER_SPEC.isLoaded()), this::available,
            () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), blockEntity::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    private LibraryControlBehaviour control() { return blockEntity.getBehaviour(LibraryControlBehaviour.TYPE); }
    @Override protected boolean available() { return control() != null && control().available(); }
    public SerialDevice device() { return device; }
    boolean enabled() { return device.view().ports().get("s0").enabled(); }
    boolean busy() {
        var status = device.status("s0");
        return device.diagnostic().isPresent() || status.receive().frames() != 0 || Set.of("queued", "transmitting").contains(status.transmit());
    }
    void configure(boolean enabled) { device.configure("s0", blockEntity.getBlockState().getValue(LibraryCoreBlock.FACING).getOpposite(), enabled); }
    public DataValue.RecordValue target() {
        var pos = blockEntity.getBlockPos(); return new LibraryTrackAddress(pos.getX(), pos.getY(), pos.getZ()).value();
    }
    public DataValue.RecordValue status() {
        var status = device.status("s0");
        return new DataValue.RecordValue(Map.of("enabled", new DataValue.BooleanValue(enabled()), "target", target(),
            "connection", new DataValue.TextValue(status.connection()), "transmit", new DataValue.TextValue(status.transmit()),
            "failure", new DataValue.TextValue(status.failure()), "last", new DataValue.TextValue(lastCode),
            "frames", new DataValue.IntegerValue(status.receive().frames())));
    }
    @Override protected void processReceived() {
        if (!enabled() || device.diagnostic().isPresent()) return;
        var receive = device.prepareReceive("s0");
        if (!receive.result().field("status").equals(new DataValue.TextValue("received"))) return;
        var frame = (DataValue.RecordValue)receive.result().field("value");
        if (!new DataValue.TextValue("library_command").equals(frame.fields().get("kind")) || !target().equals(frame.fields().get("target"))) {
            discard(receive, "not_addressed"); return;
        }
        // A full transmit slot is backpressure: leave the command in its bounded receive slot.
        if (Set.of("queued", "transmitting").contains(device.status("s0").transmit())) return;
        try {
            var caller = control().serialController(receive.source().orElseThrow(() -> new LibraryBookAccess.Failure("unknown_source")));
            var command = LibraryDriveCommand.decode(frame);
            blockEntity.getBehaviour(LibraryDriveBehaviour.TYPE).acceptSerial(caller, command, receive);
            lastCode = "handled";
        } catch (LibraryBookAccess.Failure failure) {
            // Rejected device commands have no effect. A state change mid-publication retains the command.
            if (failure.code().equals("changed")) lastCode = failure.code(); else discard(receive, failure.code());
        } catch (CableDiscovery.DiscoveryException failure) { discard(receive, failure.code()); }
        catch (SerialRecordCodec.Failure failure) { lastCode = failure.code().name().toLowerCase(Locale.ROOT); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException failure) { lastCode = failure.code().name().toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException failure) { lastCode = String.valueOf(failure.getMessage()); }
    }
    private void discard(SerialDevice.Prepared receive, String code) {
        if (receive.publish()) { lastCode = code; blockEntity.setChanged(); }
    }
    public Optional<Tag> archive() { return Optional.ofNullable(device.write()); }
    public void restore(Tag data) { device.read(data); resetActivity(); lastCode = "idle"; }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { if (!clientPacket) restore(tag.get(ROOT)); }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (!clientPacket) archive().ifPresent(data -> tag.put(ROOT, data));
    }
}
