package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.*;

/** Opt-in second-process acceptance. Never installs, resets, reauthorizes or injects an order. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingNativeOrderColdRecoveryGameTests {
    private static final List<String> ROLES = List.of("wire","sample","prepare","order","item","handoff",
        "ledger","supply","packet","finish","guard","ack","renewal");

    @GameTest(template="empty", timeoutTicks=18000)
    public static void savedPendingApplicationContinuesToDeliveryAndOriginalAck(GameTestHelper h) throws Exception {
        var cut = NbtIo.read(Path.of("native-completion-cut.nbt"));
        h.assertTrue(cut != null && cut.getBoolean("RecoveryVariant"), "Use an existing thirteen-machine checkpoint, never seed a substitute");
        h.assertTrue(cut.getLong("Process") != ProcessHandle.current().pid(), "A genuinely new JVM must load the saved machines");
        var level = h.getLevel(); var controller = BlockPos.of(cut.getLong("Controller"));
        h.assertTrue(h.absolutePos(BlockPos.ZERO).distSqr(controller) > 128 * 128, "Place the test anchor away from the saved installation");
        // Keep the real saved area ticking; this grants no device or program permissions.
        for (int x=(controller.getX()-8)>>4; x<=(controller.getX()+16)>>4; x++)
            for (int z=(controller.getZ()-8)>>4; z<=(controller.getZ()+8)>>4; z++) {
                level.setChunkForced(x,z,true); level.getChunk(x,z);
            }
        var packagerPos = BlockPos.of(cut.getLong("Packager"));
        var outputPos = BlockPos.of(cut.getLong("OriginalOutput"));
        var receiverPos = packagerPos.east(4);
        var expected = new ItemStack(Items.PAPER,7); expected.remove(net.minecraft.core.component.DataComponents.RARITY);
        h.runAfterDelay(40, () -> {
            var hosts = new LinkedHashMap<String,ComputerBehaviour>();
            var installations = new LinkedHashMap<String,ComputerBehaviour.InstalledProgram>();
            for (var role : ROLES) {
                var block = level.getBlockEntity(BlockPos.of(cut.getLong(role)));
                h.assertTrue(block instanceof ComputerBlockEntity, "Original machine remains: " + role);
                var host = ((ComputerBlockEntity)block).getBehaviour(ComputerBehaviour.TYPE);
                hosts.put(role,host); installations.put(role,host.installedProgram().orElseThrow());
                h.assertTrue(!installations.get(role).paused(), "Do not manually resume or reset the saved installation: " + role);
            }
            var before = stock(level,outputPos);
            h.assertValueEqual(((DataValue.ListValue)before.field("inputs")).values().size(),1,"Original input still pending after loading");
            var ledger = hosts.get("ledger").flowSnapshot().orElseThrow().state().locals().field("entries");
            var finishWork = hosts.get("finish").flowSnapshot().orElseThrow().state().work();
            var supplier = hosts.get("supply").flowSnapshot().orElseThrow().state().locals();
            h.assertValueEqual(supplier.field("job"),new DataValue.TextValue(cut.getString("Job")),"Saved business identity is not regenerated");
            var packager = (PackagerBlockEntity)level.getBlockEntity(packagerPos);
            h.assertTrue(PackageItem.isPackage(packager.heldBox),"The already produced physical package survives loading");
            var chest = (Container)level.getBlockEntity(packagerPos.west());
            var originalChest = (Container)level.getBlockEntity(outputPos.south().below().west());
            h.assertValueEqual(chest.getItem(0).getCount(),9,"Only the original seven matching items were extracted");
            h.assertValueEqual(chest.getItem(1).getCount(),16,"Unmatched components are untouched");
            h.assertValueEqual(originalChest.getItem(0).getCount(),16,"Native source has not shipped a duplicate");

            h.startSequence().thenWaitUntil(() -> h.assertTrue(PackageItem.isPackage(
                new PackagerItemHandler(packager).extractItem(0,1,true)),"Saved package is physically extractable"))
            .thenExecute(() -> {
                // The native fixture's unused plot is filled with barriers, not necessarily air.
                // Accept only that test scaffold; never overwrite a device or existing receiver.
                for (var pos : List.of(receiverPos,receiverPos.west(),receiverPos.above()))
                    h.assertTrue(level.getBlockEntity(pos)==null && (level.isEmptyBlock(pos) || level.getBlockState(pos).is(Blocks.BARRIER)),
                        "Never overwrite an existing receiving fixture: " + pos);
                level.setBlock(receiverPos.west(),Blocks.CHEST.defaultBlockState(),3);
                level.setBlock(receiverPos,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
                level.setBlock(receiverPos.above(),AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
                var senderLink = (PackagerLinkBlockEntity)level.getBlockEntity(packagerPos.above());
                var receiverLink = (PackagerLinkBlockEntity)level.getBlockEntity(receiverPos.above());
                LogisticallyLinkedBehaviour.remove(receiverLink.behaviour);
                receiverLink.behaviour.freqId = senderLink.behaviour.freqId;
                LogisticallyLinkedBehaviour.keepAlive(receiverLink.behaviour);
                var receiver = (PackagerBlockEntity)level.getBlockEntity(receiverPos);
                var box = new PackagerItemHandler(packager).extractItem(0,1,false);
                h.assertTrue(PackageItem.isPackage(box),"Move the existing package, never create or copy one");
                h.assertTrue(new PackagerItemHandler(receiver).insertItem(0,box,false).isEmpty(),"Native unpack consumes the transported package");
                System.out.println("NATIVE_COLD_RECOVERY physical package transported/unpacked in new JVM; source pid="+cut.getLong("Process")+", current="+ProcessHandle.current().pid());
            }).thenWaitUntil(() -> {
                for (var entry : hosts.entrySet()) {
                    var state = entry.getValue().flowSnapshot().orElseThrow().state();
                    h.assertTrue(state.status()!=FlowRuntime.Status.FAILED,entry.getKey()+": "+state.diagnostic());
                }
                var ack = hosts.get("ack").flowSnapshot().orElseThrow().state();
                h.assertValueEqual(ack.status(),FlowRuntime.Status.COMPLETE,"Public recovery program must finish, not a test-side ACK");
            }).thenExecute(() -> {
                var ack = hosts.get("ack").flowSnapshot().orElseThrow().state();
                h.assertValueEqual(((DataValue.RecordValue)ack.locals().field("reply")).field("code"),new DataValue.TextValue("acknowledged"),"Current session acknowledges only the original input");
                var after = stock(level,outputPos);
                h.assertValueEqual(after.field("inputs"),new DataValue.ListValue(List.of()),"Original input removed only after actual delivery");
                for (var key : List.of("device","revision","next_sequence","catalog"))
                    h.assertValueEqual(after.field(key),before.field(key),"No new input, replacement catalog or device: "+key);
                h.assertValueEqual(hosts.get("ledger").flowSnapshot().orElseThrow().state().locals().field("entries"),ledger,"Stable business ledger retained");
                h.assertTrue(hosts.get("finish").flowSnapshot().orElseThrow().state().work()>finishWork,"Saved task actually continued");
                for (var role : ROLES) h.assertValueEqual(hosts.get(role).installedProgram().orElseThrow(),installations.get(role),"No reset/reinstall: "+role);
                var actual = ((Container)level.getBlockEntity(receiverPos.west())).getItem(0);
                h.assertValueEqual(actual.getCount(),7,"Destination receives exactly seven real items");
                h.assertTrue(ItemStack.isSameItemSameComponents(expected,actual),"Component removal survives save, transport and unpack");
                h.assertValueEqual(chest.getItem(0).getCount(),9,"No repeated alternate extraction");
                h.assertValueEqual(chest.getItem(1).getCount(),16,"No component substitution");
                h.assertValueEqual(originalChest.getItem(0).getCount(),16,"No native duplicate dispatch");
                System.out.println("NATIVE_COLD_RECOVERY COMPLETE: original order delivered and acknowledged; unchanged installations, ledger and input watermark");
            }).thenSucceed();
        });
    }

    private static DataValue.RecordValue stock(ServerLevel level, BlockPos pos) {
        var tag = Objects.requireNonNull(level.getBlockEntity(pos)).saveWithoutMetadata(level.registryAccess());
        return (DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("BiocomputingStockApplication").getCompound("State"),
            CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
    }
}
