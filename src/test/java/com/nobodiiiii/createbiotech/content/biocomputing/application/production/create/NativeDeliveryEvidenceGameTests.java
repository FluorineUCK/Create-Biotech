package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class NativeDeliveryEvidenceGameTests {
    private static final NativeNbtCodec.Limits NBT = new NativeNbtCodec.Limits(65536, 1048576, new ArchiveLimits(4096, 65536, 65536));
    @GameTest(template = "empty")
    public static void oneEntryCodecPreservesMetadataAndRestoresExactEvidence(GameTestHelper h) {
        var f = fixture(h); var codec = new NativeDeliveryEvidence(h.getLevel().registryAccess(), NBT, 1, 8);
        var before = f.box.copy(); var fragment = codec.capture(f.box);
        h.assertTrue(ItemStack.isSameItemSameComponents(before, f.box), "Capture does not edit native package metadata");
        var ledger = codec.restore(f.order); h.assertValueEqual(ledger.accept(fragment), DeliveryLedger.Outcome.ACCEPTED, "One material entry fits despite empty package slots");
        var saved = new Order(f.order.key(), f.order.request(), f.order.plan(), 0, Phase.WAITING, f.order.receipt(), codec.encode(ledger), "partial");
        var restored = codec.restore(saved); h.assertTrue(restored.complete(), "Exact native evidence round trip");
        h.assertValueEqual(restored.accept(fragment), DeliveryLedger.Outcome.DUPLICATE, "Restored fragment cannot count twice");
        var future = new LinkedHashMap<>(((DataValue.RecordValue)saved.result()).fields()); future.put("version", integer(2));
        var unknown = new Order(saved.key(), saved.request(), saved.plan(), saved.step(), saved.phase(), saved.receipt(), record(future), saved.diagnostic());
        boolean refused = false;
        try { codec.restore(unknown); } catch (IllegalArgumentException expected) { refused = true; }
        h.assertTrue(refused, "Unknown evidence version never becomes an empty delivery ledger");
        var tiny = new NativeDeliveryEvidence(h.getLevel().registryAccess(), new NativeNbtCodec.Limits(65536, 1048576, new ArchiveLimits(4096, 65536, 1)), 1, 8);
        refused = false;
        try { tiny.encode(ledger); } catch (IllegalArgumentException expected) { refused = true; }
        h.assertTrue(refused, "Whole evidence obeys configured value capacity");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void simulationAndNativeRejectionPublishNoDelivery(GameTestHelper h) {
        var f = fixture(h); var before = f.journal.load();
        h.assertTrue(f.receiver.unwrapBox(f.box, true), "Native simulation succeeds");
        h.assertValueEqual(f.journal.load(), before, "Simulation publishes nothing");
        for (int slot = 0; slot < f.chest.getContainerSize(); slot++) f.chest.setItem(slot, new ItemStack(Items.STONE, 64));
        h.assertTrue(!new PackagerItemHandler(f.receiver).insertItem(0, f.box, false).isEmpty(), "Full native inventory refuses package insertion");
        h.assertValueEqual(f.journal.load(), before, "Native refusal publishes nothing");
        f.chest.clearContent(); h.assertTrue(new PackagerItemHandler(f.receiver).insertItem(0, f.box, false).isEmpty(), "Unchanged package can be delivered after explicit next attempt");
        h.assertValueEqual(f.journal.load().orders().get(f.order.key()).phase(), Phase.COMPLETE, "Actual acceptance records delivery");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void wrongNetworkCannotClaimDeliveryAndAmbiguousNativeIdsRequireReview(GameTestHelper h) {
        var f = fixture(h); var before = f.journal.load();
        LogisticallyLinkedBehaviour.remove(f.link.behaviour); f.link.behaviour.freqId = UUID.randomUUID(); LogisticallyLinkedBehaviour.keepAlive(f.link.behaviour);
        h.assertTrue(new PackagerItemHandler(f.receiver).insertItem(0, f.box, false).isEmpty(), "Native delivery elsewhere remains a native success");
        h.assertValueEqual(f.journal.load(), before, "Unrelated network is not proof for this order");
        // Separate receiver and genuine pending records exercise ambiguity without replaying an applied package.
        var g = fixture(h, new BlockPos(5, 1, 1));
        var state = g.journal.load(); var secondKey = new Key(UUID.randomUUID(), UUID.randomUUID());
        var second = new Order(secondKey, g.order.request(), g.order.plan(), 0, Phase.WAITING, g.order.receipt(), DataValue.NullValue.INSTANCE, "accepted");
        var orders = new LinkedHashMap<>(state.orders()); orders.put(secondKey, second);
        h.assertTrue(g.journal.commit(state.revision(), new Snapshot(state.revision() + 1, 0, orders)), "Retained ambiguous identities");
        h.assertTrue(new PackagerItemHandler(g.receiver).insertItem(0, g.box, false).isEmpty(), "Observer never changes native acceptance");
        for (var order : g.journal.load().orders().values()) {
            h.assertValueEqual(order.phase(), Phase.REVIEW_REQUIRED, "No arbitrary first owner wins");
            h.assertValueEqual(order.diagnostic(), "ambiguous_native_order", "Explicit uncertain identity");
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void contradictoryFragmentRequiresReviewWithoutChangingNativeAcceptance(GameTestHelper h) {
        var f = fixture(h);
        PackageItem.setOrder(f.box, 7, 0, false, 0, true, PackageItem.getOrderContext(f.box));
        h.assertTrue(new PackagerItemHandler(f.receiver).insertItem(0, f.box, false).isEmpty(), "Actual native effect remains successful");
        var order = f.journal.load().orders().get(f.order.key());
        h.assertValueEqual(order.phase(), Phase.REVIEW_REQUIRED, "Wrong final-link metadata is not complete");
        h.assertValueEqual(order.diagnostic(), "delivery_conflict", "Explicit evidence conflict");
        h.assertValueEqual(f.chest.getItem(0).getCount(), 4, "Observer does not roll back or repeat native material effects");
        h.succeed();
    }
    private record Fixture(PackagerBlockEntity receiver, PackagerLinkBlockEntity link, Container chest, ItemStack box, Journal journal, Order order) {}
    private static Fixture fixture(GameTestHelper h) { return fixture(h, new BlockPos(1, 1, 1)); }
    private static Fixture fixture(GameTestHelper h, BlockPos relative) {
        var level = h.getLevel(); var p = h.absolutePos(relative); var network = UUID.randomUUID();
        level.setBlock(p.west(), Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(p, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST), 3);
        level.setBlock(p.north(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.WALL).setValue(PackagerLinkBlock.FACING, Direction.NORTH), 3);
        var link = (PackagerLinkBlockEntity)level.getBlockEntity(p.north());
        LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
        var counts = new ItemStackHandler(PackageItem.SLOTS); counts.setStackInSlot(0, new ItemStack(Items.STONE, 4));
        var box = PackageItem.containing(counts); PackageItem.addAddress(box, "evidence");
        PackageItem.setOrder(box, 7, 0, true, 0, true, PackageOrderWithCrafts.simple(List.of(new BigItemStack(new ItemStack(Items.STONE), 4))));
        var material = record(Map.of("items", new DataValue.ListValue(List.of(record(Map.of("item", text("minecraft:stone"), "count", integer(4)))))));
        var command = record(Map.of("network", text(network.toString()), "address", text("evidence"), "revision", integer(1), "data", material));
        var receipt = record(Map.of("native_orders", new DataValue.ListValue(List.of(record(Map.of("id", integer(7), "links", new DataValue.ListValue(List.of(integer(0)))))))));
        var order = new Order(new Key(UUID.randomUUID(), UUID.randomUUID()), material, new Plan("create.packaging", 1, List.of(command)), 0,
            Phase.WAITING, Optional.of(receipt), DataValue.NullValue.INSTANCE, "accepted");
        var repository = CreateProductionReceipts.get(level.getServer()).repository(); var device = UUID.randomUUID();
        var lease = repository.attach(device, new Object(), true); lease.commit(0, new Snapshot(1, 0, Map.of(order.key(), order))); lease.close();
        return new Fixture((PackagerBlockEntity)level.getBlockEntity(p), link, (Container)level.getBlockEntity(p.west()), box, repository.journal(device), order);
    }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.TextValue text(String text) { return new DataValue.TextValue(text); }
    private static DataValue.IntegerValue integer(long number) { return new DataValue.IntegerValue(number); }
}
