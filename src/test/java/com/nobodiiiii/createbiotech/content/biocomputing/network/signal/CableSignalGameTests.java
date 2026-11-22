package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class CableSignalGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void placingCableJoinsNeighboursButNotDevices(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cable-builder"));
        player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos.south(2)));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.north(), CBBlocks.BLOCK_READER.get().defaultBlockState(), Block.UPDATE_ALL);
        for (var type : List.of(CBBlocks.DATA_CABLE.get(), CBBlocks.FAST_DATA_CABLE.get(), CBBlocks.DUPLEX_DATA_CABLE.get())) {
            var wire = type.defaultBlockState();
            var otherType = type == CBBlocks.DATA_CABLE.get() ? CBBlocks.FAST_DATA_CABLE.get() : CBBlocks.DATA_CABLE.get();
            level.setBlock(pos.east(), otherType.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.west(), wire, Block.UPDATE_ALL);
            level.setBlock(pos.above(), wire, Block.UPDATE_ALL);
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos),
                Direction.UP, pos.below(), false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(type));
            player.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            var placed = level.getBlockState(pos);
            h.assertTrue(placed.is(type), "Place the actual cable item");
            for (var face : Direction.values()) {
                boolean joined = face == Direction.WEST || face == Direction.EAST || face == Direction.UP;
                h.assertValueEqual(placed.getValue(DataCableBlock.connection(face)), joined, "Only data cable faces open: " + face);
                if (joined) h.assertTrue(level.getBlockState(pos.relative(face)).getValue(DataCableBlock.connection(face.getOpposite())), "Neighbour reciprocates");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, com.simibubi.create.AllItems.WRENCH.asStack());
            var cut = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos).add(-.125,0,0), Direction.WEST, pos, false);
            player.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, cut));
            level.updateNeighborsAt(pos, type);
            h.assertFalse(level.getBlockState(pos).getValue(DataCableBlock.connection(Direction.WEST)), "Neighbour updates must not heal a cut");
            h.assertFalse(level.getBlockState(pos.west()).getValue(DataCableBlock.connection(Direction.EAST)), "Both cut faces stay closed");
            level.removeBlock(pos, false);
            h.assertFalse(level.getBlockState(pos.above()).getValue(DataCableBlock.connection(Direction.DOWN)), "Broken cable does not leave an exposed contact");
            for (var dir : List.of(Direction.UP, Direction.WEST, Direction.EAST)) level.removeBlock(pos.relative(dir), false);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realSioDrivesDustWithoutProtocolAndHoldsWhilePausedAcrossOwnerReload(GameTestHelper h) {
        var level = h.getLevel(); var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var computer = computer(h, pos); var cable = pos.east(); var dust = pos.east(2);
        level.setBlock(cable, wire(Direction.WEST,Direction.EAST), 3);
        level.setBlock(dust.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(dust, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        computer.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
        h.runAfterDelay(5, () -> {
            h.assertValueEqual(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER), 12, "Actual dust gets SIO output without a serial frame");
            h.assertTrue(computer.advertisement().isEmpty(), "No legacy communication endpoint granted");
            var progress = computer.flowSnapshot().orElseThrow();
            computer.clearSignals();
            h.assertValueEqual(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER), 0, "Revocation immediately removes drive");
            computer.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
            h.runAfterDelay(3, () -> {
                var entity = computer.blockEntity; var state = entity.getBlockState();
                var saved = entity.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
                h.assertValueEqual(CableSignalWorldTicks.power(level, cable), 0, "Unloaded owner cannot keep a cable powered");
                var restored = (ComputerBlockEntity)BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
                level.setBlockEntity(restored);
                h.runAfterDelay(4, () -> {
                    var host = restored.getBehaviour(ComputerBehaviour.TYPE);
                    h.assertValueEqual(host.flowSnapshot().orElseThrow(), progress, "Pause/reload does not replay SIO instructions");
                    h.assertValueEqual(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER), 12, "Restored grant samples retained paused output");
                    level.setBlock(cable, Blocks.AIR.defaultBlockState(), 3);
                    h.assertValueEqual(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER), 0, "Broken wire removes the actual redstone signal");
                    h.succeed();
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 70)
    public static void twoExplicitSourcesConflictAndColdSavedPowerIsNotAuthority(GameTestHelper h) {
        var level = h.getLevel(); var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var computer = computer(h, pos); var cable = pos.east();
        level.setBlock(cable, wire(Direction.WEST,Direction.EAST).setValue(DataCableBlock.POWER, 15), 3);
        h.assertValueEqual(level.getSignal(cable, Direction.EAST), 0, "Saved POWER cannot drive before an authorized sample");
        computer.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
        computer.configureSignal(new SignalConnections.Configuration("p1", Direction.EAST, true));
        h.runAfterDelay(5, () -> {
            h.assertValueEqual(level.getSignal(cable, Direction.EAST), 0, "No hidden winner between two enabled sources");
            var ports = (DataValue.RecordValue)computer.signalStatus().field("ports");
            h.assertValueEqual(((DataValue.RecordValue)ports.field("p0")).field("status"), new DataValue.TextValue("source_conflict"), "Conflict is visible");
            computer.configureSignal(new SignalConnections.Configuration("p1", Direction.EAST, false));
            h.runAfterDelay(3, () -> {
                h.assertValueEqual(level.getSignal(cable, Direction.EAST), 12, "Explicitly removing the competing source restores output");
                h.succeed();
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=90)
    public static void passiveMixedCableKeepsAnalogStrengthAndDoesNotLatchDust(GameTestHelper h) {
        var level=h.getLevel(); var origin=h.absolutePos(new BlockPos(2,2,2));
        var source=origin.west(3); var lamp=origin.south(5);
        for(int x=-3;x<=0;x++) level.setBlock(origin.offset(x,-1,0),Blocks.STONE.defaultBlockState(),3);
        for(int x=-2;x<0;x++) level.setBlock(origin.offset(x,0,0),Blocks.REDSTONE_WIRE.defaultBlockState(),3);
        for(int i=0;i<5;i++) level.setBlock(origin.south(i),i==0?wire(Direction.WEST,Direction.SOUTH):wire(Direction.NORTH,Direction.SOUTH),3);
        level.setBlock(origin.south(2),CBBlocks.FAST_DATA_CABLE.get().defaultBlockState()
            .setValue(DataCableBlock.connection(Direction.NORTH),true).setValue(DataCableBlock.connection(Direction.SOUTH),true),3);
        level.setBlock(origin.south(3),CBBlocks.DUPLEX_DATA_CABLE.get().defaultBlockState()
            .setValue(DataCableBlock.connection(Direction.NORTH),true).setValue(DataCableBlock.connection(Direction.SOUTH),true),3);
        level.setBlock(lamp,Blocks.REDSTONE_LAMP.defaultBlockState(),3);
        level.setBlock(source,Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
        h.runAfterDelay(6,()->{
            h.assertValueEqual(level.getSignal(origin.south(4),Direction.SOUTH),14,"Five passive cable blocks retain analog input 14");
            h.assertTrue(level.getBlockState(lamp).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT),"Real lamp lit, no Computer");
            level.removeBlock(source,false);
            h.runAfterDelay(10,()->{
                h.assertValueEqual(level.getSignal(origin,Direction.UP),0,"Dust fed by the cable is not a source");
                h.assertTrue(!level.getBlockState(lamp).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT),"Lamp off");
                h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=90)
    public static void wrenchOpensOnlySelectedContactAndBreaksLiveCircuit(GameTestHelper h){
        var level=h.getLevel();var a=h.absolutePos(new BlockPos(2,2,2));var b=a.east();
        level.setBlock(a,wire(Direction.WEST),3);level.setBlock(b,wire(Direction.EAST),3);
        level.setBlock(a.west(),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"wire-wrench"));
        player.setItemInHand(InteractionHand.MAIN_HAND,com.simibubi.create.AllItems.WRENCH.asStack());
        Runnable wrench=()->{
            var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(a).add(.125,0,0),Direction.EAST,a,false);
            player.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        };
        h.runAfterDelay(3,()->{
            h.assertValueEqual(level.getSignal(b,Direction.WEST),0,"Adjacent closed contacts do not short");
            wrench.run();
            h.runAfterDelay(3,()->{
                h.assertValueEqual(level.getSignal(b,Direction.WEST),15,"Wrench closes one physical link between two cables");
                h.assertValueEqual(level.getSignal(b,Direction.UP),0,"Unselected side cannot power its neighbour");
                wrench.run();h.runAfterDelay(3,()->{
                    h.assertValueEqual(level.getSignal(b,Direction.WEST),0,"Opening the link isolates it again");h.succeed();
                });
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=90)
    public static void selectedSioPinReadsPhysicalRedstone(GameTestHelper h){
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(2,2,2));var host=computer(h,pos);
        var workspace=new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(new FlowProgram.SignalRead("sample","p0","reading")),
            new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        host.installDocument(workspace.snapshot(),ComputerRuntime.MemoryPolicy.RESET);host.setPaused(true);
        host.configureSignal(new SignalConnections.Configuration("p0",Direction.EAST,true));
        level.setBlock(pos.east(),wire(Direction.WEST,Direction.EAST),3);
        level.setBlock(pos.east(2),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
        h.runAfterDelay(4,()->{
            for(int i=0;i<8 && !host.flowSnapshot().orElseThrow().state().locals().fields().containsKey("reading");i++)host.step();
            h.assertValueEqual(host.flowSnapshot().orElseThrow().state().locals().field("reading"),new DataValue.IntegerValue(15),"Real SIO.READ instruction samples a bound cable");
            h.succeed();
        });
    }
    static net.minecraft.world.level.block.state.BlockState wire(Direction... faces){
        var state=CBBlocks.DATA_CABLE.get().defaultBlockState();
        for(var face:faces)state=state.setValue(DataCableBlock.connection(face),true);
        return state;
    }
    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3); level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Resident");
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "sio-test")); player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Install normal resident");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("level", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("drive", "p0", "level"),new FlowProgram.SignalWrite("driveTwo","p1","level")), new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(12))),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(true);
        for (int i = 0; i < 8 && !computer.signalOutputs().orElseThrow().outputs().containsKey("p1"); i++) computer.step();
        h.assertValueEqual(computer.signalOutputs().orElseThrow().outputs().get("p0"), 12, "ISA executes the signal instruction");
        return computer;
    }
}
