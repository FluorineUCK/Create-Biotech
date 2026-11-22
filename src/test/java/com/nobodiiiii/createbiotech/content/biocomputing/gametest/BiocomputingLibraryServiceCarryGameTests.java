package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryServiceCarryGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void pendingLibrarianWorkSurvivesActualWrenchCarryAndPlacement(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, f.pos.south(4)));
        computer.install(BiocomputingWorldGameTests.counter(), com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(true); var independent = computer.snapshot().orElseThrow();
        for (int i = 1; i <= 3; i++) f.level.setBlock(f.pos.south(i), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var control = f.core.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE);
        helper.assertValueEqual(control.configure(f.player, 0, Optional.of(control.discover(f.player).getFirst())), "configured", "Explicit control through the rear cable");
        helper.assertValueEqual(control.configureSerial(f.player, 1, true), "configured", "Separate explicit serial opening");
        var serial = f.core.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour.TYPE);
        var fixtureFrame = new DataValue.RecordValue(Map.of("fixture", new DataValue.TextValue("retained complete frame")));
        serial.device().attachments().getFirst().receiver().offer(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(
            fixtureFrame, com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.serialCodec(true)));
        var serialCheckpoint = serial.archive().orElseThrow();
        var drive = f.core.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
        var track = LibraryTrackAddress.from(f.pos, f.core.getBlockState().getValue(com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock.FACING), f.pos.east());
        long operation = drive.seek(computer, track);
        var checkpoint = drive.archive().orElseThrow(); var binding = control.archive().orElseThrow();
        f.wrench(true);
        var drop = f.coreDrops().getFirst(); var held = drop.getItem().copy(); drop.discard();
        var envelope = LibraryServiceItemData.decode(held.get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(envelope.drive().orElseThrow(), checkpoint, "Remaining work is on the actual dropped core");
        helper.assertValueEqual(envelope.serial().orElseThrow(), serialCheckpoint, "Complete interface frames travel in the same actual core");
        helper.assertTrue(envelope.archive() == null, "No legacy service or book-data duplication");
        var restored = place(f, held); var loaded = restored.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
        helper.assertValueEqual(loaded.archive().orElseThrow(), checkpoint, "Normal item placement restores the exact unfinished operation");
        helper.assertValueEqual(restored.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour.TYPE).archive().orElseThrow(),
            serialCheckpoint, "Actual item placement does not discard the interface queue");
        helper.assertValueEqual(restored.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE).archive().orElseThrow(), binding,
            "Control identity is carried with, not recreated independently of, the work");
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(loaded.result(computer, operation).code(), "positioned", "Original work completes after actual replacement");
            helper.assertValueEqual(computer.snapshot().orElseThrow(), independent, "Carry does not advance delegate state");
            f.assertBookUnchanged(); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void unknownMechanicalArchiveRemainsPrivateAndRecoverableInsteadOfResetting(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper); f.wrench(true);
        var drop = f.coreDrops().getFirst(); var held = drop.getItem().copy(); drop.discard();
        var raw = net.minecraft.nbt.StringTag.valueOf("unknown-mechanical-work");
        held.set(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get(), new LibraryServiceItemData(f.core.libraryId(), null,
            Optional.empty(), Optional.of(raw)).encode());
        var placed = place(f, held);
        var drive = placed.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
        helper.assertValueEqual(drive.status().field("code"), new DataValue.TextValue("invalid_checkpoint"), "Invalid work is not reset to idle");
        helper.assertTrue(drive.snapshot().isEmpty(), "No invented empty checkpoint"); drive.tick();
        helper.assertValueEqual(drive.archive().orElseThrow(), raw, "Observation/ticking preserve the exact unknown data");
        var client = new net.minecraft.nbt.CompoundTag(); drive.write(client, f.level.registryAccess(), true);
        helper.assertTrue(client.isEmpty(), "Generic update packet never contains private work");
        f.wrench(true);
        var recovered = LibraryServiceItemData.decode(f.coreDrops().getFirst().getItem().get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(recovered.drive().orElseThrow(), raw, "Actual recovery retains the archive");
        f.assertBookUnchanged(); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invalidOrMismatchedServiceEnvelopeCannotPlaceOrConsumeCore(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        f.core.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, configuration(false));
        f.wrench(true);
        var drop = f.coreDrops().getFirst(); var original = drop.getItem().copy(); drop.discard();
        var component = CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get();
        var raw = original.get(component);
        for (var invalid : List.of(CustomData.EMPTY, raw.update(t -> t.putUUID("LibraryId", UUID.randomUUID())),
                raw.update(t -> t.putInt("Version", 0)), raw.update(t -> t.putString("unknown", "field")))) {
            var held = original.copy(); held.set(component, invalid); var before = held.copy();
            f.player.setItemInHand(InteractionHand.MAIN_HAND, held);
            helper.assertTrue(!f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND,
                f.hit(f.pos.below())).consumesAction(), "Invalid envelope rejects the actual placement");
            helper.assertTrue(ItemStack.matches(held, before), "Rejected item is not consumed or rewritten");
            helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above())
                && f.level.getBlockEntity(f.pos) == null, "No partial pair or host survives rejection");
        }
        var placed = place(f, original);
        helper.assertValueEqual(placed.memberId(), f.core.memberId(), "Valid original still claims the identity");
        helper.assertValueEqual(placed.getBehaviour(LibraryServiceBehaviour.TYPE).status().field("configuration"),
            configuration(false).value(), "Valid archive is not blocked by earlier rejected attempts");
        f.assertBookUnchanged(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void malformedInnerServiceRemainsUnavailableAndRecoverable(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        f.wrench(true);
        var drop = f.coreDrops().getFirst(); var held = drop.getItem().copy(); drop.discard();
        var raw = net.minecraft.nbt.StringTag.valueOf("unrecognized-service-archive");
        held.set(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get(), new LibraryServiceItemData(f.core.memberId(), raw).encode());
        var placed = place(f, held); var service = placed.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.status().field("code"), new DataValue.TextValue("invalid_save"), "Invalid service is not an empty successful reset");
        helper.assertValueEqual(service.receive(request()), AdmissionStatus.UNAVAILABLE, "Invalid service cannot accept work");
        f.wrench(true);
        var recovered = LibraryServiceItemData.decode(f.coreDrops().getFirst().getItem().get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(recovered.archive(), raw, "Ordinary recovery preserves the unreadable archive for diagnosis");
        f.assertBookUnchanged(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void materializedReplySurvivesCarryWithoutNewCorrelationOrDispatchIdentity(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        service.configure(0, configuration(true)); service.receive(request());
        var archive = new net.minecraft.nbt.Tag[1];
        helper.startSequence().thenIdle(4).thenExecute(() -> {
            helper.assertValueEqual(service.status().field("runtime"), new DataValue.TextValue("ready"), "A reply is already materialized before removal");
            archive[0] = f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT).copy();
            f.wrench(true);
            var drop = f.coreDrops().getFirst(); var held = drop.getItem().copy(); drop.discard();
            var placed = place(f, held);
            helper.assertValueEqual(placed.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT), archive[0],
                "Placement restores reply plus original sequence and correlation verbatim");
        }).thenIdle(4).thenExecute(() -> {
            var placed = (LibraryBlockEntity)f.level.getBlockEntity(f.pos);
            helper.assertValueEqual(placed.getBehaviour(LibraryServiceBehaviour.TYPE).status().field("runtime"), new DataValue.TextValue("ready"),
                "Ordinary ticks resume retrying the already-materialized reply");
            helper.assertValueEqual(placed.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT), archive[0],
                "Missing return connection does not replace or discard the reply");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void inventoryProjectionRedactsServiceAndWireContainsOnlyMarker(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        service.configure(0, configuration(true)); service.receive(request());
        var stack = f.core.asConfiguredStack(); var before = stack.copy();
        var inventory = new net.neoforged.neoforge.items.ItemStackHandler(1); inventory.setStackInSlot(0, stack);
        var view = (DataValue.ListValue)com.nobodiiiii.createbiotech.content.biocomputing.peripheral.CapabilitySnapshotValues.inventory(
            inventory, f.level.registryAccess(), new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReadLimits(4096, 32768, 65536));
        var item = (DataValue.RecordValue)view.values().getFirst();
        helper.assertValueEqual(((DataValue.RecordValue)item.field("components")).field(LibraryServiceItemData.COMPONENT_ID),
            new DataValue.ErrorValue("access_denied", "access_denied"), "Real item capability never exposes queues or grants");
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), f.level.registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            var client = ItemStack.STREAM_CODEC.decode(buffer);
            helper.assertTrue(client.get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()).isEmpty(), "Ordinary inventory wire carries only a presence marker");
            helper.assertTrue(!buffer.isReadable(), "Projection leaves no hidden trailing service bytes");
        } finally { buffer.release(); }
        helper.assertTrue(ItemStack.matches(stack, before), "Neither public projection mutates server item contents");
        helper.succeed();
    }

    static LibraryBlockEntity place(BiocomputingLibraryBlockGameTests.Fixture f, ItemStack held) {
        f.player.setItemInHand(InteractionHand.MAIN_HAND, held);
        f.helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND,
            f.hit(f.pos.below())).consumesAction(), "Actual player dispatch places carrying item");
        f.helper.assertTrue(held.isEmpty(), "Exactly one core item consumed");
        return (LibraryBlockEntity)f.level.getBlockEntity(f.pos);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void failedRemovalRejectsAdmissionsDuringHandoffAndResumesAfterward(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.configure(0, configuration(true)), "configured", "Open explicit grant");
        var request = request();
        var observed = new ArrayList<AdmissionStatus>();
        Consumer<EntityJoinLevelEvent> interfere = event -> {
            if (event.getLevel() == f.level && event.getEntity() instanceof ItemEntity item
                && LibraryCoreItemData.read(item.getItem()).flatMap(LibraryCoreItemData::preservedLibraryId)
                    .filter(f.core.memberId()::equals).isPresent()) {
                observed.add(service.receive(request));
                event.setCanceled(true);
            }
        };
        var before = f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT).copy();
        NeoForge.EVENT_BUS.addListener(interfere);
        try { f.wrench(true); } finally { NeoForge.EVENT_BUS.unregister(interfere); }
        helper.assertValueEqual(observed, List.of(AdmissionStatus.UNAVAILABLE), "No acceptance after the item snapshot has been captured");
        helper.assertTrue(f.level.getBlockEntity(f.pos) == f.core && f.coreDrops().isEmpty(), "Rejected output retains original host only");
        helper.assertValueEqual(f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT), before,
            "Handoff suspension never rewrites durable state");
        helper.assertValueEqual(service.receive(request), AdmissionStatus.ACCEPTED, "Failed removal releases the temporary admission barrier");
        f.wrench(true);
        var carried = LibraryServiceItemData.decode(f.coreDrops().getFirst().getItem().get(
            com.nobodiiiii.createbiotech.registry.CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(carried.archive(), f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT),
            "Successful retry carries the request accepted after the failed attempt");
        f.assertBookUnchanged(); helper.succeed();
    }

    static LibraryServiceConfiguration configuration(boolean enabled) {
        return new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", enabled)),
            Map.of("logs", LibraryServiceConfiguration.Access.READ_ONLY));
    }
    static MessageEnvelope request() {
        var sender = UUID.randomUUID(); var event = UUID.randomUUID();
        return new MessageEnvelope(sender, 0, event, 0, new DataValue.RecordValue(Map.of(
            "op", new DataValue.TextValue("read"), "volume", new DataValue.TextValue("logs"),
            "entry", new DataValue.TextValue("reading"))),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(sender, event, 0), "missing-return")));
    }
}
