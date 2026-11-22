package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class VindicatorSequencerGameTests {
    @GameTest(templateNamespace="create_biotech_instruments",template="empty",timeoutTicks=90)
    public static void vindicatorSequenceDrivesClusterPeerAndExternalCableWithoutSioInstructions(GameTestHelper h) {
        var f = fixture(h,false,false);
        var a = f.a(); var owner = (ComputerBlockEntity)a.blockEntity; owner.clearResidentSource();
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, EntityType.VINDICATOR.create(h.getLevel())), "Captured vindicator");
        var player = f.player(); player.setItemInHand(InteractionHand.MAIN_HAND,box);
        h.assertValueEqual(owner.installResident(player,InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Installed real resident");
        var wire = new SimpleIoWire(new SimpleIoWire.Pin("A","left","p0"),new SimpleIoWire.Pin("B","right","p0"),true);
        configureThroughMenu(h,f.controller(),player,new ClusterConfiguration(f.config().members(),Map.of(),Map.of(),Map.of(),Map.of("wave",wire)));
        var gateway = SignalGatewayAccess.authorize(new ClusterMemberEditorAccess(f.controller(),owner),player,"p0","wave_out");
        a.configureSignalGateway("p0",Optional.of(gateway));
        a.configureSignal(new SignalConnections.Configuration("p0",Direction.WEST,true));
        var cable = f.controller().getBlockPos().west();
        h.getLevel().setBlock(cable, CBBlocks.DATA_CABLE.get().defaultBlockState()
            .setValue(DataCableBlock.connection(Direction.EAST),true).setValue(DataCableBlock.connection(Direction.WEST),true),3);
        a.configureSequencer(new RecordValue(Map.of("op",new TextValue("start"),"pin",new TextValue("p0"),"segments",
            new ListValue(List.of(new RecordValue(Map.of("level",new IntegerValue(11),"ticks",new IntegerValue(12))))))));
        a.setPaused(false);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(f.controller().signalInputs(f.b()).get("p0"),11,"Cluster peer samples the actual sequencer drive");
            h.assertValueEqual(h.getLevel().getSignal(cable,Direction.WEST),11,"External cable samples that same drive");
            h.assertTrue(a.flowSnapshot().orElseThrow().state().signals().outputs().isEmpty(),"No firmware write was invented");
            a.setPaused(true);
            var saved = a.sequencerStatus().field("remaining");
            h.assertValueEqual(CableSignalWorldTicks.power(h.getLevel(),cable),0,"Pause revokes the hardware drive immediately");
            var restored = reloadComputer(h,owner.getBlockPos());
            h.runAfterDelay(4, () -> {
                h.assertValueEqual(restored.sequencerStatus().field("remaining"),saved,"Paused/reloaded cursor doesn't consume ticks");
                h.assertValueEqual(h.getLevel().getSignal(cable,Direction.WEST),0,"No old high pulse restored");
                restored.setPaused(false);
                h.runAfterDelay(2, () -> {
                    h.assertValueEqual(h.getLevel().getSignal(cable,Direction.WEST),11,"Only resumed active ticks drive again");
                    h.runAfterDelay(14, () -> {
                        h.assertValueEqual(h.getLevel().getSignal(cable,Direction.WEST),0,"Finished one-shot releases cable");
                        h.assertValueEqual(restored.sequencerStatus().field("state"),new TextValue("idle"),"Sequence is complete");
                        h.succeed();
                    });
                });
            });
        });
    }
}
