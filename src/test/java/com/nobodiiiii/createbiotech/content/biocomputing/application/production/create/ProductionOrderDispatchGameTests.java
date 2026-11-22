package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Real native packaging and file-journal restart; not a recipe-completion or player-UI test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class ProductionOrderDispatchGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void realPackagingIsNotRepeatedAfterClosingAndReopeningTheOrderJournal(GameTestHelper h) {
        var level = h.getLevel(); var network = UUID.randomUUID(); var source = UUID.randomUUID();
        var packagerPos = h.absolutePos(new BlockPos(3, 2, 3)); var chestPos = packagerPos.west();
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST), 3);
        var linkPos = packagerPos.north();
        level.setBlock(linkPos, AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
            .setValue(PackagerLinkBlock.FACING, Direction.NORTH), 3);
        var chest = (Container)level.getBlockEntity(chestPos);
        var packager = (PackagerBlockEntity)level.getBlockEntity(packagerPos);
        var link = (PackagerLinkBlockEntity)level.getBlockEntity(linkPos);
        h.assertTrue(link.getPackager() == packager, "Native stock link physically targets this packager");
        LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network;
        LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
        chest.setItem(0, new ItemStack(Items.STONE, 64));
        h.runAfterDelay(30, () -> {
            var manifest = Map.of(new StackKey(new ItemStack(Items.STONE)), 4L);
            h.assertValueEqual(CreatePackageDispatch.dispatch(network, "workshop", manifest, 8, () -> false).code(), "denied", "Closed authority performs no native request");
            h.assertValueEqual(chest.getItem(0).getCount(), 64, "Denied operation keeps all actual stock");
            var bounds = new ProductionOrderFileJournal.Limits(1048576, 4194304, new ArchiveLimits(4096, 100000, 100000));
            var key = new Key(source, UUID.randomUUID());
            var request = record("four-stone");
            Backend backend = new Backend() {
                public boolean authorized(UUID candidate) { return candidate.equals(source) && level.getServer().isSameThread(); }
                public boolean supports(String name, int version) { return name.equals("native-test") && version == 1; }
                public Preparation prepare(UUID candidate, DataValue.RecordValue value) {
                    return Preparation.ready(new Plan("native-test", 1, List.of(value)));
                }
                public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
                    var result = CreatePackageDispatch.dispatch(network, "workshop", manifest, 8, () -> authorized(source));
                    return result.accepted() ? Dispatch.accepted(new DataValue.RecordValue(Map.of("orders", new DataValue.ListValue(
                        result.orders().stream().<DataValue>map(order -> new DataValue.IntegerValue(order.id())).toList()))))
                        : Dispatch.rejected(result.code());
                }
                public Observation observe(Operation operation, DataValue.RecordValue receipt) { return Observation.pending(); }
            };
            try {
                var directory = Files.createTempDirectory(level.getServer().getWorldPath(LevelResource.ROOT), "production-order-test-");
                try (var journal = ProductionOrderFileJournal.initialize(directory, bounds)) {
                    var service = new ProductionOrderService(journal, backend, new Limits(4, 4, 4096));
                    h.assertValueEqual(service.submit(key, request).code(), "accepted", "New order is durable before native dispatch");
                    h.assertValueEqual(chest.getItem(0).getCount(), 64, "Admission alone does not consume material");
                    h.assertValueEqual(service.advance().order().orElseThrow().phase(), Phase.WAITING, "Actual native invocation records receipt but not business completion");
                }
                h.assertValueEqual(chest.getItem(0).getCount(), 60, "Exactly four actual items packaged");
                h.assertTrue(!packager.heldBox.isEmpty(), "Native packager produced a real package");
                h.assertValueEqual(PackageItem.getAddress(packager.heldBox), "workshop", "Native package uses validated destination");
                var contents = PackageItem.getContents(packager.heldBox);
                int count = 0;
                for (int slot = 0; slot < contents.getSlots(); slot++) if (contents.getStackInSlot(slot).is(Items.STONE)) count += contents.getStackInSlot(slot).getCount();
                h.assertValueEqual(count, 4, "Package conserves the removed stock");
                try (var restored = ProductionOrderFileJournal.open(directory, bounds)) {
                    var service = new ProductionOrderService(restored, backend, new Limits(4, 4, 4096));
                    h.assertValueEqual(service.submit(key, request).code(), "existing", "Same public core request cannot consume stock again");
                    h.assertValueEqual(service.advance().code(), "waiting", "Missing delivery evidence remains waiting");
                    h.assertValueEqual(service.status(key).order().orElseThrow().phase(), Phase.WAITING, "Native packaging does not masquerade as completed production");
                    h.assertValueEqual(chest.getItem(0).getCount(), 60, "Real-file restore does not repeat native effects");
                }
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            h.succeed();
        });
    }
    private static DataValue.RecordValue record(String text) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.TextValue(text)));
    }
}
