package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ResidentRadioMenuGameTests {
    @GameTest(template="empty")
    public static void traderRadioNeedsNoProductionListenerMenu(GameTestHelper helper) {
        var legacy = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_biotech", "wandering_listener");
        helper.assertTrue(!net.minecraft.core.registries.BuiltInRegistries.MENU.containsKey(legacy),
            "The production listener menu must not be registered alongside the resident radio");
        helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.MENU.containsKey(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_biotech", "biocomputing_program")),
            "Keep the real Computer program and radio settings menu");
        helper.succeed();
    }

    static ProgramDocument example(ComputerBehaviour computer,String id) {
        var resources=(java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream>) key ->
            ResidentRadioMenuGameTests.class.getResourceAsStream("/assets/"+key.getNamespace()+"/"+key.getPath());
        var entry=ProgramExampleCatalog.load(resources).stream().filter(it->it.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry,resources,computer.hardware().limits().archive());
    }
    private static DataValue.RecordValue tune(int band,boolean armed) {
        return new DataValue.RecordValue(Map.of("preset",new DataValue.IntegerValue(band),"armed",new DataValue.BooleanValue(armed)));
    }
    private static final ComputerControlSession.Action READ=ComputerControlSession.Action.READ_RADIO, SET=ComputerControlSession.Action.CONFIGURE_RADIO;
    private static final DataValue.RecordValue EMPTY=new DataValue.RecordValue(Map.of());

    @GameTest(template="empty",timeoutTicks=650)
    public static void adjacentEmitterCanSpoilAFrameButRetuningDoesNotReplayIt(GameTestHelper h) {
        var tx = ResidentRadioWorldGameTests.place(h,new BlockPos(1,4,2),true).getBehaviour(ComputerBehaviour.TYPE);
        var rx = ResidentRadioWorldGameTests.place(h,new BlockPos(5,4,2),true).getBehaviour(ComputerBehaviour.TYPE);
        var neighbour = ResidentRadioWorldGameTests.place(h,new BlockPos(6,4,2),true).getBehaviour(ComputerBehaviour.TYPE);
        h.runAfterDelay(5, () -> {
            for (var host : List.of(tx,neighbour))
                h.assertValueEqual(host.installDocument(example(host,"radio_probe_send"),ComputerRuntime.MemoryPolicy.RESET),
                    ComputerRuntime.InstallResult.APPLIED,"Install the same finite one-shot on both transmitters");
            tx.configureRadio(tx.programRevision(), 8, true);
            rx.configureRadio(rx.programRevision(), 8, true);
            neighbour.configureRadio(neighbour.programRevision(), 9, true);
            tx.setPaused(false);
            neighbour.setPaused(false);
            h.startSequence().thenWaitUntil(() -> {
                h.assertValueEqual(rx.radioStatus().field("reception"),new DataValue.TextValue("interference"),"Different center can still spoil reception");
                long wanted=((DataValue.IntegerValue)rx.radioStatus().field("wanted")).value();
                long noise=((DataValue.IntegerValue)rx.radioStatus().field("interference")).value();
                h.assertTrue(noise >= wanted && wanted > 0,"Nearby adjacent carrier must be measurable at this receiver");
                for (var host : List.of(tx,neighbour))
                    h.assertValueEqual(host.radioStatus().field("transmit"),new DataValue.TextValue("transmitted"),"Local TX completion despite failed RX");
                h.assertValueEqual(rx.radioStatus().field("frames"),new DataValue.IntegerValue(0),"No corrupt or foreign data");
            }).thenExecute(() -> neighbour.configureRadio(neighbour.programRevision(), 11, true))
            .thenExecuteAfter(20, () -> {
                h.assertValueEqual(rx.radioStatus().field("frames"),new DataValue.IntegerValue(0),"Retune alone cannot recover a lost frame");
                for (var host : List.of(tx,neighbour)) { host.reset(); host.setPaused(false); }
            }).thenWaitUntil(() -> {
                h.assertValueEqual(rx.radioStatus().field("frames"),new DataValue.IntegerValue(1),"Only a fresh, explicitly sent frame arrives");
                h.assertValueEqual(rx.radioStatus().field("reception"),new DataValue.TextValue("accepted"),"Accepted on the original tuner");
                h.assertValueEqual(rx.radioStatus().field("interference"),new DataValue.IntegerValue(0),"Outside the filter is not co-channel traffic");
                h.assertValueEqual(neighbour.radioStatus().field("transmit"),new DataValue.TextValue("transmitted"),"Retuned transmitter was really active");
            }).thenSucceed();
        });
    }

    @GameTest(template="empty",timeoutTicks=650)
    public static void menuAndShippedProbeDocumentsUseOnlyExplicitTraderHardware(GameTestHelper h) {
        var a=ResidentRadioWorldGameTests.place(h,new BlockPos(2,4,5),true).getBehaviour(ComputerBehaviour.TYPE);
        var b=ResidentRadioWorldGameTests.place(h,new BlockPos(6,4,5),true).getBehaviour(ComputerBehaviour.TYPE);
        h.runAfterDelay(5,()->{
            var sender=example(a,"radio_probe_send");var receiver=example(b,"radio_probe_receive");
            h.assertValueEqual(a.installDocument(sender,ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Install catalog sender");
            h.assertValueEqual(b.installDocument(receiver,ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Install catalog receiver");
            for (var host:List.of(a,b)) {
                var id=UUID.randomUUID();var menu=new ComputerControlSession(id);long rev=host.programRevision();
                var seen=menu.apply(id,1,0,READ,()->EMPTY,host).orElseThrow();
                h.assertValueEqual(seen.code(),"ok","Read local radio even if the previous control view is stale");
                h.assertValueEqual(seen.dedicatedStatus().orElseThrow().field("armed"),new DataValue.BooleanValue(false),"Preview does not tune");
                var denied=menu.apply(id,2,rev,SET,()->tune(1,true),host,()->{throw new IllegalArgumentException("not_permitted");}).orElseThrow();
                h.assertTrue(!denied.code().equals("ok") && denied.dedicatedStatus().orElseThrow().fields().isEmpty(),"No observation or mutation without menu access");
                h.assertValueEqual(menu.apply(id,3,rev,SET,()->tune(1,true),host).orElseThrow().code(),"ok","Explicit apply");
                h.assertValueEqual(menu.apply(id,4,rev,SET,()->tune(0,false),host).orElseThrow().code(),"stale","Do not operate a newer physical session");
                h.assertTrue(menu.apply(id,4,host.programRevision(),SET,()->tune(0,false),host).isEmpty(),"No replay");
                h.assertTrue(!SerialControlData.decode(host.serialStatus()).ports().containsKey("rf0") &&
                    host.serialDevice().attachments().isEmpty(),"The radio is not a cable face");
            }
            a.setPaused(false);
            h.startSequence().thenWaitUntil(()->h.assertValueEqual(b.radioStatus().field("frames"),new DataValue.IntegerValue(1),"One complete native frame ready"))
            .thenExecute(()->b.setPaused(false)).thenWaitUntil(()->{
                var state=b.flowSnapshot().orElseThrow().state();
                h.assertValueEqual(state.status(),FlowRuntime.Status.COMPLETE,"Public receiver ran");
                h.assertValueEqual(state.locals().field("received"),SerialResult.receive("received",sender.flow().orElseThrow().initial().field("payload")),"Original typed NBT, no hidden conversion");
                h.assertValueEqual(b.radioStatus().field("frames"),new DataValue.IntegerValue(0),"Claimed once");
            }).thenSucceed();
        });
    }
}
