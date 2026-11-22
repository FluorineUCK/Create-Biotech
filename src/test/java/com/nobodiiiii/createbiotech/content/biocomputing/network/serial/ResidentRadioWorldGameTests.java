package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class ResidentRadioWorldGameTests {
    @GameTest(template = "empty", timeoutTicks = 750)
    public static void actualTraderHardwareKeepsWiredAndAddsAnInitiallyClosedRadio(GameTestHelper h) {
        var station = place(h, new BlockPos(2, 4, 3), true);
        var control = place(h, new BlockPos(5, 4, 3), false);
        h.runAfterDelay(5, () -> {
            var radio = station.getBehaviour(ComputerBehaviour.TYPE);
            var wired = control.getBehaviour(ComputerBehaviour.TYPE);
            h.assertValueEqual(radio.hardware().profile(), "trader", "Actual trader profile");
            h.assertValueEqual(wired.hardware().profile(), "starter", "Actual untrained control profile");
            h.assertTrue(radio.hardware().ready() && wired.hardware().ready(), "Both actual Computers are loaded and equipped");
            h.assertTrue(radio.hardware().limits().serial().ports().containsKey(ResidentRadioHardware.PORT), "An installed trader must actually expose the physical radio");
            h.assertTrue(!wired.hardware().limits().serial().ports().containsKey(ResidentRadioHardware.PORT), "Balanced non-trader must not acquire a radio");
            h.assertValueEqual(radio.hardware().limits().serial().ports().get("s0"), wired.hardware().limits().serial().ports().get("s0"), "Do not replace the wired port");
            h.assertValueEqual(radio.serialDevice().status(ResidentRadioHardware.PORT).connection(), "closed", "No automatic radio on old saves");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void installedProgramsExchangeNativeNbtWithoutAnyDataCable(GameTestHelper h) {
        var a = place(h, new BlockPos(2, 4, 4), true).getBehaviour(ComputerBehaviour.TYPE);
        var b = place(h, new BlockPos(6, 4, 4), true).getBehaviour(ComputerBehaviour.TYPE);
        var tag = new CompoundTag();
        tag.putInt("temperature", 23);
        var payload = NativeNbtCodec.snapshot(tag, new NativeNbtCodec.Limits(4096, 65536, new ArchiveLimits(262144, 2097152, 16777216)));
        h.runAfterDelay(5, () -> {
            var configRevision = a.programRevision();
            a.configureRadio(configRevision, 2, true);
            h.assertValueEqual(a.radioStatus().field("preset_count"), new DataValue.IntegerValue(16), "Bounded selectable hardware presets");
            try {
                a.configureRadio(configRevision, 3, false);
                h.fail("An earlier editor revision cannot retune the current station");
            } catch (IllegalArgumentException denied) {
                h.assertValueEqual(denied.getMessage(), "stale", "Reject stale tune before hardware mutation");
            }
            h.assertValueEqual(a.radioStatus().field("preset"), new DataValue.IntegerValue(2), "Keep the newer tuning");
            b.configureRadio(b.programRevision(), 2, true);
            h.assertTrue(a.serialDevice().attachments().isEmpty() && b.serialDevice().attachments().isEmpty(),
                "An activated radio is absent from the wired endpoint enumeration");
            try {
                a.configureSerial(new SerialControlData.Configuration(ResidentRadioHardware.PORT, Direction.UP, true));
                h.fail("The cable configuration UI cannot bind the radio as a wired face");
            } catch (IllegalArgumentException denied) {
                h.assertValueEqual(denied.getMessage(), "radio_interface", "Radio has an explicit tuning entry, not a wired port");
            }
            a.serialDevice().configure("s0", Direction.EAST, true);
            h.assertValueEqual(a.serialDevice().attachments().stream().map(SerialDevice.Attachment::name).toList(),
                List.of("s0"), "Actual current wired port remains available on the same hardware");
            install(h, a, document(Map.of("payload", DataType.typeOf(payload)), Map.of("payload", payload),
                new FlowProgram.SerialSend("send", ResidentRadioHardware.PORT, "payload", "sent")));
            install(h, b, document(Map.of(), Map.of(), new FlowProgram.SerialReceive("receive", ResidentRadioHardware.PORT, "received", true)));
            a.setPaused(false);
            h.startSequence().thenWaitUntil(() -> {
                h.assertValueEqual(b.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 1, "Actual world ticks deliver the ISA payload wirelessly");
                h.assertValueEqual(b.radioStatus().field("reception"), text("accepted"), "Receiver gets local diagnosis");
                h.assertValueEqual(a.flowSnapshot().orElseThrow().state().locals().field("sent"), SerialResult.send("queued"), "The sender outcome was only an enqueue");
                h.assertValueEqual(a.serialDevice().status(ResidentRadioHardware.PORT).transmit(), "transmitted", "Physical end of sender");
            }).thenExecute(() -> b.setPaused(false)).thenWaitUntil(() -> {
                h.assertValueEqual(b.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Read through the public instruction");
                h.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals().field("received"), SerialResult.receive("received", payload), "Lossless native NBT");
                h.assertValueEqual(b.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 0, "Claim each complete frame once");
            }).thenSucceed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 950)
    public static void threePhysicalComputersDistinguishAdjacentInterferenceFromNonOverlappingRadio(GameTestHelper h) {
        var a = place(h, new BlockPos(2, 4, 3), true).getBehaviour(ComputerBehaviour.TYPE);
        var b = place(h, new BlockPos(7, 4, 3), true).getBehaviour(ComputerBehaviour.TYPE);
        var c = place(h, new BlockPos(7, 4, 6), true).getBehaviour(ComputerBehaviour.TYPE);
        h.runAfterDelay(5, () -> {
            a.configureRadio(a.programRevision(), 7, true);
            b.configureRadio(b.programRevision(), 7, true);
            c.configureRadio(c.programRevision(), 8, true);
            queue(h, a, "wanted");
            queue(h, c, "neighbor");
            h.startSequence().thenWaitUntil(() -> {
                h.assertValueEqual(b.radioStatus().field("reception"), text("accepted"), "A partial overlap need not destroy a strong wanted signal");
                h.assertTrue(((DataValue.IntegerValue)b.radioStatus().field("interference")).value() > 0, "Adjacent energy reaches the stock receiver");
                h.assertValueEqual(b.serialDevice().poll(ResidentRadioHardware.PORT).orElseThrow().field("sample"), text("wanted"), "First frame delivered once");
            }).thenExecuteAfter(5, () -> {
                c.configureRadio(c.programRevision(), 10, true);
                queue(h, a, "retry from program");
                queue(h, c, "non-overlapping");
            }).thenWaitUntil(() -> {
                h.assertValueEqual(b.radioStatus().field("interference"), new DataValue.IntegerValue(0), "Non-overlapping energy is rejected");
                h.assertValueEqual(b.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 1, "Only the explicit later emission is delivered");
                h.assertValueEqual(b.serialDevice().poll(ResidentRadioHardware.PORT).orElseThrow().field("sample"),
                    text("retry from program"), "No old frame appears after recovery");
            }).thenSucceed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void replacingTheActualTraderInvalidatesAnInFlightFrameEvenWhenRearmedInTheSameTick(GameTestHelper h) {
        var sender = place(h, new BlockPos(2, 4, 2), true);
        var a = sender.getBehaviour(ComputerBehaviour.TYPE);
        var b = place(h, new BlockPos(6, 4, 2), true).getBehaviour(ComputerBehaviour.TYPE);
        h.runAfterDelay(5, () -> {
            a.configureRadio(a.programRevision(), 13, true);
            b.configureRadio(b.programRevision(), 13, true);
            a.serialDevice().configure("s0", Direction.SOUTH, true);
            queue(h, a, "retired");
            h.startSequence().thenWaitUntil(() ->
                h.assertValueEqual(a.serialDevice().status(ResidentRadioHardware.PORT).transmit(), "transmitting", "Wait for actual carrier start"))
                .thenExecute(() -> {
                    sender.clearResidentSource();
                    seat(h, sender, true);
                    h.assertValueEqual(a.radioStatus().field("armed"), new DataValue.BooleanValue(false), "A replacement does not inherit power");
                    a.configureRadio(a.programRevision(), 13, true);
                    h.assertTrue(a.serialDevice().attachments().stream().anyMatch(p -> p.name().equals("s0")), "Replacement has not touched the configured wired face");
                }).thenExecuteAfter(100, () -> {
                    h.assertValueEqual(b.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 0, "Old physical emitter cannot complete");
                    queue(h, a, "explicit new");
                }).thenWaitUntil(() -> {
                    h.assertValueEqual(b.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 1, "A new prompt emission passes once");
                    h.assertValueEqual(b.serialDevice().poll(ResidentRadioHardware.PORT).orElseThrow().field("sample"),
                        text("explicit new"), "Only the current resident's new frame was delivered");
                }).thenSucceed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void archivedCompleteRadioDoesNotDisableWiredIoOnAnOrdinaryReplacement(GameTestHelper h) {
        var station = place(h, new BlockPos(5, 4, 2), true);
        var a = station.getBehaviour(ComputerBehaviour.TYPE);
        var b = place(h, new BlockPos(5, 4, 6), true).getBehaviour(ComputerBehaviour.TYPE);
        var peer = place(h, new BlockPos(2, 4, 2), false).getBehaviour(ComputerBehaviour.TYPE);
        var level = h.getLevel();
        var through = CBBlocks.DATA_CABLE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.WEST),true)
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.EAST),true);
        level.setBlockAndUpdate(station.getBlockPos().west(), through);
        level.setBlockAndUpdate(station.getBlockPos().west(2), through);
        h.runAfterDelay(5, () -> {
            a.serialDevice().configure("s0", Direction.WEST, true);
            peer.serialDevice().configure("s0", Direction.EAST, true);
            a.configureRadio(a.programRevision(), 0, true);
            b.configureRadio(b.programRevision(), 0, true);
            queue(h, b, "completed before swap");
            h.startSequence().thenWaitUntil(() ->
                h.assertValueEqual(a.serialDevice().status(ResidentRadioHardware.PORT).receive().frames(), 1, "Actual completed frame"))
                .thenExecute(() -> {
                    station.clearResidentSource(); seat(h, station, false);
                    h.assertTrue(!a.hasResidentRadio(), "Ordinary replacement has no RF hardware");
                    h.assertValueEqual(a.serialDevice().status(ResidentRadioHardware.PORT).connection(), "missing_port", "Archive is dormant");
                    h.assertTrue(a.serialDevice().diagnostic().isEmpty(), "A dormant RF archive does not poison the wired serial bank");
                    h.assertValueEqual(a.serialDevice().send("s0", new DataValue.RecordValue(Map.of("sample", text("physical wire")))),
                        "queued", "Keep the old wired face live on the replacement");
                }).thenWaitUntil(() ->
                    h.assertValueEqual(peer.serialDevice().status("s0").receive().frames(), 1, "Receive over the real cable"))
                .thenExecute(() -> {
                    h.assertValueEqual(peer.serialDevice().poll("s0").orElseThrow().field("sample"), text("physical wire"), "Only intended wired frame arrives");
                    station.clearResidentSource(); seat(h, station, true);
                    h.assertTrue(a.hasResidentRadio(), "Real radio is back");
                    h.assertValueEqual(a.radioStatus().field("armed"), new DataValue.BooleanValue(false), "No automatic power-up on replacement");
                    h.assertValueEqual(a.serialDevice().poll(ResidentRadioHardware.PORT).orElseThrow().field("sample"),
                        text("completed before swap"), "A detached occupant does not remove the Computer's fully received owned record");
                    h.assertTrue(a.serialDevice().poll(ResidentRadioHardware.PORT).isEmpty(), "The prior receive can be claimed once");
                }).thenSucceed();
        });
    }

    @GameTest(template = "empty", batch = "receive_loop", timeoutTicks = 1200)
    public static void closedPhysicalReceiveLoopFiltersBeforeDecodeAndDoesNotGateTransmission(GameTestHelper h) {
        var station = place(h,new BlockPos(3,4,3),true);
        var pos = station.getBlockPos();
        var rx = station.getBehaviour(ComputerBehaviour.TYPE);
        var source = placeAbsolute(h,pos.west(2),true).getBehaviour(ComputerBehaviour.TYPE);
        var noise = placeAbsolute(h,pos.south(2),true).getBehaviour(ComputerBehaviour.TYPE);
        var witch = placeAbsolute(h,pos.east(2).south(2),false);
        witch.clearResidentSource(); seat(h,witch,EntityType.WITCH.create(h.getLevel()));
        var filter = witch.getBehaviour(ComputerBehaviour.TYPE);
        h.runAfterDelay(5, () -> {
            filter.configureWitchFilter(filter.programRevision(),new WitchReceiverFilter.Setting(Optional.of(Direction.SOUTH), WitchReceiverFilter.Width.NARROW));
            rx.configureRadio(rx.programRevision(),4,true);
            source.configureRadio(source.programRevision(),4,true);
            noise.configureRadio(noise.programRevision(),5,true);
            queue(h,source,"stock"); queue(h,noise,"noise");
            h.startSequence().thenWaitUntil(() -> {
                h.assertTrue(((DataValue.IntegerValue)rx.radioStatus().field("interference")).value()>0,"Stock path admits some adjacent power");
                h.assertValueEqual(rx.serialDevice().status("rf0").receive().frames(),1,"Stock payload is available");
                h.assertValueEqual(rx.serialDevice().poll("rf0").orElseThrow().field("sample"), text("stock"), "Stock path works alone");
            }).thenExecute(() -> rx.configureReceiveLoop(rx.programRevision(),Optional.of(Direction.EAST)))
            .thenWaitUntil(() -> h.assertValueEqual(rx.radioStatus().field("witch_link"),text("send_open"),"No implicit filter"))
            .thenExecute(() -> {
                guide(h,pos.east(),Direction.WEST,Direction.EAST);
                guide(h,pos.east(2),Direction.WEST,Direction.SOUTH);
                guide(h,pos.east(2).south(),Direction.NORTH,Direction.SOUTH);
            }).thenWaitUntil(() -> h.assertValueEqual(rx.radioStatus().field("witch_link"),text("return_open"),"A send leg alone is not a receiver"))
            .thenExecute(() -> {
                guide(h,pos.east(2).south(3),Direction.NORTH,Direction.WEST);
                for (int x=0;x<=1;x++) guide(h,pos.east(x).south(3),Direction.EAST,Direction.WEST);
                guide(h,pos.west().south(3),Direction.EAST,Direction.NORTH);
                for(int z=1;z<=2;z++) guide(h,pos.west().south(z),Direction.SOUTH,Direction.NORTH);
                guide(h,pos.west(),Direction.SOUTH,Direction.EAST);
            }).thenWaitUntil(() -> h.assertValueEqual(rx.radioStatus().field("witch_link"),text("connected"),"Return reaches the original trader"))
            .thenExecute(() -> { queue(h,source,"narrow");queue(h,noise,"noise2"); })
            .thenWaitUntil(() -> {
                h.assertValueEqual(rx.serialDevice().status("rf0").receive().frames(),1,"Narrow frame arrived");
                h.assertValueEqual(rx.radioStatus().field("interference"),new DataValue.IntegerValue(0),"Narrow rejects adjacent power");
                h.assertValueEqual(rx.serialDevice().poll("rf0").orElseThrow().field("sample"),text("narrow"),"Only wanted frame arrived");
            }).thenExecute(() -> {
                noise.configureRadio(noise.programRevision(),4,true);
                queue(h,source,"wanted");queue(h,noise,"noise3");
            }).thenWaitUntil(() -> {
                h.assertValueEqual(rx.radioStatus().field("reception"),text("interference"),"Co-channel energy is not distinguished");
                h.assertValueEqual(rx.serialDevice().status("rf0").receive().frames(),0,"No damaged payload delivered");
            }).thenExecute(() -> { h.getLevel().removeBlock(pos.west(),false);queue(h,rx,"still transmitting"); })
            .thenWaitUntil(() -> {
                h.assertValueEqual(rx.radioStatus().field("witch_link"),text("return_open"),"Broken return never bypasses");
                h.assertValueEqual(source.serialDevice().status("rf0").receive().frames(),1,"Open receive loop does not stop transmission");
                h.assertValueEqual(source.serialDevice().poll("rf0").orElseThrow().field("sample"),text("still transmitting"),"Outgoing data intact");
            }).thenExecute(() -> queue(h,source,"not received"))
            .thenWaitUntil(() -> {
                h.assertValueEqual(source.serialDevice().status("rf0").transmit(),"transmitted","Sender finished");
                h.assertValueEqual(rx.serialDevice().status("rf0").receive().frames(),0,"Broken return cannot deliver frames");
            }).thenSucceed();
        });
    }

    private static void guide(GameTestHelper h, BlockPos pos, Direction in, Direction out) {
        h.getLevel().setBlockAndUpdate(pos, CBBlocks.CARRIER_CABLE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.CarrierCableBlock.INPUT, in)
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.CarrierPathBlock.FACING, out));
    }

    private static void queue(GameTestHelper h, ComputerBehaviour computer, String content) {
        h.assertValueEqual(computer.serialDevice().send(ResidentRadioHardware.PORT,
            new DataValue.RecordValue(Map.of("sample", text(content)))), "queued", "One finite send use");
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }

    private static ProgramDocument document(Map<String, DataType> inputs, Map<String, DataValue> data, FlowProgram.Step step) {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(
            new FlowProgram(inputs, List.of(step)).withAssembly(), new DataValue.RecordValue(data))));
        return workspace.snapshot();
    }

    private static void install(GameTestHelper h, ComputerBehaviour computer, ProgramDocument program) {
        h.assertValueEqual(computer.installDocument(program, ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED,
            "Only the ordinary public program installation path");
    }

    static ComputerBlockEntity place(GameTestHelper h, BlockPos local, boolean trader) {
        return placeAbsolute(h, h.absolutePos(local), trader);
    }

    static ComputerBlockEntity placeAbsolute(GameTestHelper h, BlockPos pos, boolean trader) {
        var level = h.getLevel();
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
        var station = (ComputerBlockEntity)level.getBlockEntity(pos);
        seat(h, station, trader);
        return station;
    }

    static void seat(GameTestHelper h, ComputerBlockEntity station, boolean trader) {
        var level = h.getLevel();
        net.minecraft.world.entity.LivingEntity resident;
        if (trader) resident = EntityType.WANDERING_TRADER.create(level);
        else {
            var villager = EntityType.VILLAGER.create(level);
            villager.setVillagerData(villager.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.NONE));
            villager.setBaby(false);
            resident = villager;
        }
        seat(h, station, resident);
    }

    static void seat(GameTestHelper h, ComputerBlockEntity station, net.minecraft.world.entity.LivingEntity resident) {
        var level = h.getLevel();
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Use the genuine resident snapshot path");
        var operator = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "resident-radio-fixture"));
        operator.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(station.getBlockPos()));
        operator.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(station.installResident(operator, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Use the physical Computer installation");
    }
}
