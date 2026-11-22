package com.nobodiiiii.createbiotech.content.biocomputing.gametest;



import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingResidentInstallGameTests {
	private BiocomputingResidentInstallGameTests() {}

    @GameTest(templateNamespace="create_biotech_instruments",template="empty",timeoutTicks=80)
    public static void illusionerRecordsPhysicalRedstoneWithoutRunningAnApplication(GameTestHelper h){
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(2,2,2));
        var computer=placeComputer(level,pos);var player=headlessPlayer(level,pos);
        player.setItemInHand(InteractionHand.MAIN_HAND,captured(create(EntityType.ILLUSIONER,level)));
        h.assertValueEqual(computer.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Install analyzer");
        var owner=computer.getBehaviour(ComputerBehaviour.TYPE);
        var wire=CBBlocks.DATA_CABLE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.WEST),true)
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.EAST),true);
        level.setBlock(pos.east(),wire,3);
        owner.configureSignal(new com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Configuration("p0",Direction.EAST,true));
        var nonce=UUID.randomUUID();var session=new ComputerControlSession(nonce);
        var arm=new DataValue.RecordValue(Map.of("op",new DataValue.TextValue("start"),"pins",new DataValue.ListValue(List.of(new DataValue.TextValue("p0")))));
        var answer=session.apply(nonce,1,owner.programRevision(),ComputerControlSession.Action.CONFIGURE_INSTRUMENT,()->arm,owner).orElseThrow();
        h.assertValueEqual(answer.code(),"ok","Arm through the same instrument control action as the menu");
        h.runAfterDelay(5,()->{
            level.setBlock(pos.east(2),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
            h.runAfterDelay(5,()->{
                var frozen=new DataValue.RecordValue(Map.of("op",new DataValue.TextValue("freeze")));
                var reply=session.apply(nonce,2,owner.programRevision(),ComputerControlSession.Action.CONFIGURE_INSTRUMENT,()->frozen,owner).orElseThrow();
                var details=(DataValue.RecordValue)reply.dedicatedStatus().orElseThrow().field("details");
                var edges=(DataValue.ListValue)details.field("edges");
                h.assertValueEqual(edges.values().size(),1,"No synthetic startup edges");
                var e=(DataValue.RecordValue)edges.values().getFirst();
                h.assertValueEqual(e.field("from"),new DataValue.IntegerValue(0),"Real previous sample");
                h.assertValueEqual(e.field("to"),new DataValue.IntegerValue(15),"Powered cable sample");
                h.assertTrue(owner.flowSnapshot().isEmpty(),"The instrument never installs hidden firmware");h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void installedParserAndWitchUseTheirOwnPhysicalSerialPorts(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(2, 2, 2));
        var player = headlessPlayer(level, origin);
        var priest = villager(level, 5);
        priest.setVillagerData(priest.getVillagerData().setProfession(VillagerProfession.CLERIC));
        var open = new com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.Configuration("s0", Direction.EAST, true);
        for (var resident : List.<LivingEntity>of(priest, create(EntityType.WITCH, level))) {
            var computer = placeComputer(level, resident == priest ? origin : origin.east(3));
            player.setItemInHand(InteractionHand.MAIN_HAND, captured(resident));
            helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Install actual resident");
            var owner = computer.getBehaviour(ComputerBehaviour.TYPE);
            if (resident == priest) {
                helper.assertTrue(owner.serialDevice().view().ports().isEmpty(), "Default parser has no physical port to open");
                try {
                    owner.configureSerial(open);
                    helper.fail("Parser accepted a missing port");
                } catch (IllegalArgumentException missing) {
                    helper.assertValueEqual(missing.getMessage(), "missing_port", "Reject missing port rather than a closed interface");
                }
            } else {
                owner.configureSerial(open);
                helper.assertTrue(owner.serialDevice().view().ports().get("s0").enabled(), "Witch opens its own wired port");
            }
        }
        helper.succeed();
    }

	@GameTest(template = "empty", timeoutTicks = 80)
	public static void upperHalfPlayerInstallationRetainsIndependentHardwareAndResidentAcrossReload(GameTestHelper helper) {
		var level = helper.getLevel();
		var librarian = villager(level, 1);
        librarian.setVillagerData(librarian.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        var fletcher = villager(level, 2);
        fletcher.setVillagerData(fletcher.getVillagerData().setProfession(VillagerProfession.FLETCHER));
        var sources = java.util.List.<LivingEntity>of(librarian, fletcher,
            create(EntityType.EVOKER, level), create(EntityType.ZOMBIE_VILLAGER, level));
		var selections = new java.util.ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection>();
		for (int index = 0; index < sources.size(); index++) {
			var pos = helper.absolutePos(new BlockPos(2 + (index % 2) * 3, 2, 2 + (index / 2) * 3));
			var computer = placeComputer(level, pos); var player = headlessPlayer(level, pos);
			var source = sources.get(index);
			var held = captured(source); var original = held.copy(); player.setItemInHand(InteractionHand.MAIN_HAND, held);
			var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos.above()),
				net.minecraft.core.Direction.NORTH, pos.above(), false);
			helper.assertTrue(player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND, hit).consumesAction(),
				"Actual upper-half player item use installs through the unique new transaction");
			helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(held), "Original held box is consumed once");
			helper.assertTrue(ItemStack.matches(original, computer.residentSnapshot()), "Exact physical snapshot retained");
			var selection = computer.getBehaviour(ComputerBehaviour.TYPE).hardware(); selections.add(selection);
			helper.assertTrue(selection.status() == com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection.Status.READY,
				"Installed resident has usable independent hardware");
			var saved = computer.saveWithFullMetadata(level.registryAccess()); var state = computer.getBlockState();
			level.removeBlockEntity(pos);
			var restored = (ComputerBlockEntity) Objects.requireNonNull(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
				pos, state, saved, level.registryAccess())); level.setBlockEntity(restored);
			helper.assertTrue(ItemStack.matches(original, restored.residentSnapshot()), "Same resident after host reconstruction");
			helper.assertValueEqual(restored.getBehaviour(ComputerBehaviour.TYPE).hardware(), selection, "Same per-resident limits after reload");
			helper.assertTrue(restored.getBehaviour(ComputerBehaviour.TYPE).snapshot().isEmpty(), "Installation does not invent a program or RAM");
		}
		helper.assertTrue(selections.get(0).limits().hardware().programUnits() > selections.get(1).limits().hardware().programUnits()
			&& selections.get(0).limits().hardware().workPerSlice() < selections.get(1).limits().hardware().workPerSlice(),
			"Program and speed residents retain horizontal tradeoffs, not shared cluster resources");
        helper.assertValueEqual(selections.get(2).limits().taskSlots(), 12, "Evoker contexts share one host");
        var zombie = selections.get(3).limits();
        helper.assertTrue(zombie.hardware().registerSlots() == 8 && zombie.taskSlots() == 1 &&
            zombie.pins().xbus().equals(java.util.List.of("x0")), "Degraded zombie hardware after physical installation");
		helper.succeed();
	}

	@GameTest(template = "empty", timeoutTicks = 80)
	public static void realInspectorAndTransferWorkWithoutAFactoryHostOrProductionProfile(GameTestHelper helper) {
		var level = helper.getLevel();
		var sources = java.util.List.of(villager(level, 3), create(EntityType.WANDERING_TRADER, level),
			create(EntityType.ZOMBIE_VILLAGER, level), create(EntityType.PILLAGER, level), create(EntityType.VINDICATOR, level),
            create(EntityType.EVOKER, level), create(EntityType.ILLUSIONER, level), create(EntityType.WITCH, level));
		for (var source : sources) {
			var resident = new com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident();
			var target = new com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.Target() {
				@Override public ComputerInstallResult installationStatus() { return resident.hasSource() ? ComputerInstallResult.OCCUPIED : ComputerInstallResult.SUCCESS; }
				@Override public com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.Prepared prepareResidentInstallation(
					ItemStack snapshot, com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.Candidate candidate) {
					helper.assertValueEqual(candidate.uuid(), source.getUUID(), "Decoded physical identity only");
					return new com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.Prepared() {
						@Override public void commit() { resident.restoreSnapshot(snapshot); }
						@Override public void rollback() { resident.clear(); }
						@Override public void published() { }
					};
				}
			};
			var held = captured(source); var original = held.copy();
			helper.assertValueEqual(com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.install(
				held, target, new com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInspector(level)),
				ComputerInstallResult.SUCCESS, "Independent physical owner installs a supported adult");
			helper.assertTrue(ItemStack.matches(original, resident.snapshot()), "No production state is required");
			helper.assertTrue(level.getEntity(source.getUUID()) == null, "Inspection never spawns the resident");
		}
		helper.succeed();
	}

	@GameTest(template = "empty", timeoutTicks = 80)
	public static void stalePhysicalHostCannotConsumeAPlayerBox(GameTestHelper helper) {
		var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2,2,2));
		var old = placeComputer(level, pos); var saved = old.saveWithFullMetadata(level.registryAccess());
		level.removeBlockEntity(pos);
		var replacement = (ComputerBlockEntity) Objects.requireNonNull(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
			pos, old.getBlockState(), saved, level.registryAccess())); level.setBlockEntity(replacement);
		var player = headlessPlayer(level, pos); var held = captured(villager(level, 2)); var original = held.copy();
		player.setItemInHand(InteractionHand.MAIN_HAND, held);
		helper.assertValueEqual(old.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.INVALID_IDENTITY,
			"Retired instance cannot acquire physical ownership");
		helper.assertTrue(ItemStack.matches(original, held) && !old.hasResidentSource() && !replacement.hasResidentSource(),
			"The player and both host instances remain unchanged");
		helper.assertValueEqual(replacement.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS,
			"The actual replacement remains usable");
		helper.succeed();
	}

	@GameTest(template = "empty", timeoutTicks = 60)
	public static void productionValidatorRejectsEveryInvalidCaptureWithoutMutation(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity computer = placeComputer(level, pos);
		ServerPlayer player = headlessPlayer(level, pos);

		assertRejectedUnchanged(helper, level, computer, player, new ItemStack(Items.PAPER),
			ComputerInstallResult.NOT_BOX, "wrong item");
		assertRejectedUnchanged(helper, level, computer, player,
			new ItemStack(CBItems.CARDBOARD_BOX.get()), ComputerInstallResult.EMPTY_BOX,
			"empty captured-entity box");

		Villager baby = villager(level, 1);
		baby.setBaby(true);
		assertRejectedUnchanged(helper, level, computer, player, captured(baby),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "baby villager profile");

		Zombie unsupported = create(EntityType.ZOMBIE, level);
		assertRejectedUnchanged(helper, level, computer, player, captured(unsupported),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "unsupported entity type");

		Villager invalidProfile = villager(level, 6);
		assertRejectedUnchanged(helper, level, computer, player, captured(invalidProfile),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "out-of-table villager profile");

		// Vanilla entity loading normalizes these malformed fields, but hardware reads the raw snapshot.
		for (Consumer<CompoundTag> corrupt : java.util.List.<Consumer<CompoundTag>>of(
			entity -> entity.getCompound("VillagerData").putInt("level", 0),
			entity -> entity.getCompound("VillagerData").putString("level", "1"),
			entity -> entity.putString("Age", "-1"),
			entity -> entity.remove("VillagerData"))) {
			var malformed = captured(villager(level, 1)); editCapturedEntityTag(malformed, corrupt);
			assertRejectedUnchanged(helper, level, computer, player, malformed,
				ComputerInstallResult.INVALID_IDENTITY, "raw hardware must agree with the decoded resident");
		}

		ItemStack malformedPassengers = captured(villager(level, 1));
		editCapturedEntityTag(malformedPassengers,
			captured -> captured.putString("Passengers", "not-a-list"));
		assertRejectedUnchanged(helper, level, computer, player, malformedPassengers,
			ComputerInstallResult.INVALID_IDENTITY, "mistyped passengers tag");

		ItemStack passengerBearing = captured(villager(level, 1));
		ListTag passengers = new ListTag();
		CompoundTag passenger = new CompoundTag();
		passenger.putString("id", "minecraft:villager");
		passenger.putUUID("UUID", UUID.randomUUID());
		passengers.add(passenger);
		editCapturedEntityTag(passengerBearing,
			captured -> captured.put("Passengers", passengers));
		assertRejectedUnchanged(helper, level, computer, player, passengerBearing,
			ComputerInstallResult.PASSENGERS_UNSUPPORTED, "passenger-bearing capture");

		ItemStack malformedUuid = captured(villager(level, 1));
		editCapturedEntityTag(malformedUuid,
			captured -> captured.putString("UUID", "not-an-int-array"));
		assertRejectedUnchanged(helper, level, computer, player, malformedUuid,
			ComputerInstallResult.INVALID_IDENTITY, "malformed UUID");

		ItemStack unknownType = captured(villager(level, 1));
		editCapturedEntityTag(unknownType,
			captured -> captured.putString("id", "create_biotech:missing_entity_type"));
		assertRejectedUnchanged(helper, level, computer, player, unknownType,
			ComputerInstallResult.INVALID_IDENTITY, "unregistered raw entity type");

		Villager sameTypeCollision = villager(level, 1);
		ItemStack sameTypeBox = captured(sameTypeCollision);
		addCollision(level, sameTypeCollision, "same-type collision fixture");
		try {
			assertRejectedUnchanged(helper, level, computer, player, sameTypeBox,
				ComputerInstallResult.INVALID_IDENTITY, "loaded same-UUID/same-type collision");
		} finally {
			sameTypeCollision.discard();
		}

		Villager capturedVillager = villager(level, 1);
		ItemStack differentTypeBox = captured(capturedVillager);
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER),
			"GameTest server did not load the Nether");
		FakePlayer differentTypeCollision = new FakePlayer(nether,
			new GameProfile(capturedVillager.getUUID(), "computer-install-collision"));
		differentTypeCollision.moveTo(0.5, 80, 0.5);
		nether.addNewPlayer(differentTypeCollision);
		if (nether.getEntity(capturedVillager.getUUID()) != differentTypeCollision)
			throw new IllegalStateException("Could not add cross-level different-type collision fixture");
		try {
			assertRejectedUnchanged(helper, level, computer, player, differentTypeBox,
				ComputerInstallResult.INVALID_IDENTITY,
				"cross-level same-UUID/different-type collision");
		} finally {
			nether.removePlayerImmediately(differentTypeCollision, Entity.RemovalReason.DISCARDED);
		}

		helper.runAfterDelay(3, () -> {
			assertStablePair(helper, level, pos);
			helper.succeed();
		});
	}

	@GameTest(template = "empty", timeoutTicks = 40)
	public static void physicalInstallRetainsResidentBeforeClearingAndSecondInstallIsAtomic(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity computer = placeComputer(level, pos);
		ServerPlayer player = headlessPlayer(level, pos);
		Villager source = villager(level, 3);
		source.setVillagerData(source.getVillagerData()
			.setProfession(VillagerProfession.LIBRARIAN));
		ItemStack first = captured(source);
		Tag filledSnapshot = first.save(level.registryAccess());
		player.setItemInHand(InteractionHand.MAIN_HAND, first);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND),
			ComputerInstallResult.SUCCESS, "valid adult librarian must install");

		helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(player.getMainHandItem()),
			"successful commit must clear the same held box");
		CompoundTag committed = computer.saveWithoutMetadata(level.registryAccess())
			.getCompound("ComputerData");
		helper.assertValueEqual(committed.get("Resident"), filledSnapshot,
			"Computer must retain the exact filled snapshot committed before hand clear");
		helper.assertValueEqual(committed.getAllKeys(), java.util.Set.of("Version", "ComputerId", "Resident"),
			"Physical installation does not create executable production data");

		ItemStack second = captured(villager(level, 1));
		Tag secondBefore = second.save(level.registryAccess());
		CompoundTag computerBefore = computer.saveWithoutMetadata(level.registryAccess());
		player.setItemInHand(InteractionHand.MAIN_HAND, second);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND),
			ComputerInstallResult.OCCUPIED, "second installation must be rejected");
		helper.assertValueEqual(player.getMainHandItem().save(level.registryAccess()), secondBefore,
			"second rejection must leave the held stack byte-identical");
		helper.assertValueEqual(computer.saveWithoutMetadata(level.registryAccess()), computerBefore,
			"second rejection must leave the complete server BE tag byte-identical");
		helper.runAfterDelay(3, () -> {
			assertStablePair(helper, level, pos);
			helper.succeed();
		});
	}

	private static void assertRejectedUnchanged(GameTestHelper helper, ServerLevel level,
		ComputerBlockEntity computer, ServerPlayer player, ItemStack held,
		ComputerInstallResult expected, String fixture) {
		Tag heldBefore = held.save(level.registryAccess());
		CompoundTag computerBefore = computer.saveWithoutMetadata(level.registryAccess());
		player.setItemInHand(InteractionHand.MAIN_HAND, held);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND), expected,
			fixture + " result");
		helper.assertValueEqual(player.getMainHandItem().save(level.registryAccess()), heldBefore,
			fixture + " must retain the held stack byte-identically");
		helper.assertValueEqual(computer.saveWithoutMetadata(level.registryAccess()), computerBefore,
			fixture + " must retain the complete server BE tag byte-identically");
	}

    @GameTest(template="empty",timeoutTicks=80)
    public static void sameScratchBroadcastOnFarmerAndWitch(GameTestHelper helper){
        var level=helper.getLevel();
        var document=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        var key=new ModuleDefinition.Key("receive",1);
        var layout=new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(24,92),
            List.of(new FlowProgram.Procedure("handler",key,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,
                java.util.Optional.empty(),java.util.Optional.of("ready")),new FlowProgram.EndProcedure("end")))));
        var body=new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(Map.of(),List.of(
            new FlowProgram.Broadcast("post","ready",true)),new DataValue.RecordValue(Map.of()),layout,Map.of(),FlowProgram.Execution.REGISTER_V1);
        document.setFlow(java.util.Optional.of(body));
        for(boolean witch:List.of(false,true)){
            var pos=helper.absolutePos(new BlockPos(witch?4:2,1,2));
            var block=placeComputer(level,pos);var player=headlessPlayer(level,pos);
            var farmer=villager(level,4);farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
            player.setItemInHand(InteractionHand.MAIN_HAND,captured(witch?create(EntityType.WITCH,level):farmer));
            helper.assertValueEqual(block.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Install physical resident");
            var computer=block.getBehaviour(ComputerBehaviour.TYPE);
            helper.assertValueEqual(computer.hardware().limits().isa1a(),witch,"Native parser");
            helper.assertValueEqual(computer.hardware().limits().isa1b(),witch,"Native extended types");
            computer.installDocument(document.snapshot(),ComputerRuntime.MemoryPolicy.RESET);
            var source=computer.flowSnapshot().orElseThrow().program().steps().getFirst();
            helper.assertTrue(((FlowProgram.Broadcast)source).software()!=witch,"Installed image selected for its resident");
            for(int i=0;i<18 && computer.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++)computer.step();
            helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"Wait and complete");
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void physicalZombieTrapReturnsTextOverOneDeclaredClusterWire(GameTestHelper helper) {
        var level=helper.getLevel();var min=helper.absolutePos(new BlockPos(3,1,1));var max=min.offset(2,3,4);
        var ctl=min.offset(0,1,1);var a=ctl.east();var z=a.south(2);
        var sender=placeComputer(level,a);var zombie=placeComputer(level,z);var player=headlessPlayer(level,ctl);
        var priest=villager(level,5);priest.setVillagerData(priest.getVillagerData().setProfession(VillagerProfession.CLERIC));
        player.setItemInHand(InteractionHand.MAIN_HAND,captured(priest));helper.assertValueEqual(sender.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Install sending resident");
        player.setItemInHand(InteractionHand.MAIN_HAND,captured(create(EntityType.ZOMBIE_VILLAGER,level)));helper.assertValueEqual(zombie.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Install actual zombie");
        for(var pos:BlockPos.betweenClosed(min,max))if(pos.getX()==min.getX()||pos.getX()==max.getX()||pos.getY()==min.getY()||pos.getY()==max.getY()||pos.getZ()==min.getZ()||pos.getZ()==max.getZ())
            level.setBlock(pos,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),Block.UPDATE_ALL);
        level.setBlock(ctl,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.WEST),Block.UPDATE_ALL);
        var controller=(ClusterControllerBlockEntity)level.getBlockEntity(ctl);
        var peer=sender.getBehaviour(ComputerBehaviour.TYPE);var trap=zombie.getBehaviour(ComputerBehaviour.TYPE);
        var input=new DataType.RecordType(Map.of("count",DataType.Scalar.INTEGER));var textType=new DataType.RecordType(Map.of("text",DataType.Scalar.TEXT));
        var value=new DataValue.RecordValue(Map.of("count",new DataValue.IntegerValue(73)));
        peer.installFlow(new FlowProgram(Map.of("packet",input),List.of(new FlowProgram.Write("send","x0","packet"),new FlowProgram.Read("receive","x0","response",textType))).withAssembly(), new DataValue.RecordValue(Map.of("packet",value)));
        peer.configureEndpoint(new EndpointConfiguration("peer",true));trap.configureEndpoint(new EndpointConfiguration("trap",true));trap.tick();
        var nonce=UUID.randomUUID();var session=new ComputerControlSession(nonce);
        var action=session.apply(nonce,1,trap.programRevision(),ComputerControlSession.Action.CONFIGURE_BREAKPOINT_OUTPUT,
            ()->new DataValue.RecordValue(Map.of("pin",new DataValue.TextValue("x0"))),trap);
        helper.assertValueEqual(action.orElseThrow().code(),"ok","Menu chooses XBus, not a redstone signal pin");
        var members=Map.of("A",new ClusterConfiguration.Member(peer.communicationIdentity().orElseThrow(),a),
            "Z",new ClusterConfiguration.Member(trap.communicationIdentity().orElseThrow(),z));
        player.moveTo(Vec3.atCenterOf(ctl.west()));
        var wire=new XBusWire(new XBusWire.Pin("A","peer","x0"),new XBusWire.Pin("Z","trap","x0"),true);
        helper.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of("data",wire)),player),"configured","Explicit member wire");
        peer.setPaused(false);
        helper.succeedWhen(()->{
            var state=peer.flowSnapshot().orElseThrow().state();
            helper.assertValueEqual(state.status(),FlowRuntime.Status.COMPLETE,"Client obtains actual rendezvous result");
            var packet=(DataValue.RecordValue)state.locals().field("response");
            helper.assertTrue(packet.field("text") instanceof DataValue.TextValue text&&text.value().contains("73"),"Output is text, not the original integer");
            var status=(DataValue.RecordValue)trap.serialStatus().field("breakpoint");
            helper.assertValueEqual(status.field("state"),new DataValue.TextValue("halted"),"Stopped until reset");
            helper.assertValueEqual(status.field("outcome"),new DataValue.TextValue("xbus_complete"),"One completed output");
        });
    }

	private static ComputerBlockEntity placeComputer(ServerLevel level, BlockPos pos) {
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		BlockState lower = CBBlocks.COMPUTER.get().defaultBlockState()
			.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
		if (!level.setBlock(pos, lower, Block.UPDATE_ALL)
			|| !level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER),
				Block.UPDATE_ALL)
			|| !(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
			throw new IllegalStateException("Could not place Computer fixture at " + pos);
		return computer;
	}

	private static void assertStablePair(GameTestHelper helper, ServerLevel level, BlockPos pos) {
		BlockState lower = level.getBlockState(pos);
		BlockState upper = level.getBlockState(pos.above());
		helper.assertTrue(lower.is(CBBlocks.COMPUTER.get())
			&& lower.getValue(ComputerBlock.HALF) == DoubleBlockHalf.LOWER,
			"Delayed fixture must retain its lower Computer owner");
		helper.assertTrue(upper.is(CBBlocks.COMPUTER.get())
			&& upper.getValue(ComputerBlock.HALF) == DoubleBlockHalf.UPPER,
			"Delayed fixture must retain its matching upper Computer half");
		helper.assertValueEqual(upper.getValue(ComputerBlock.FACING),
			lower.getValue(ComputerBlock.FACING),
			"Delayed fixture halves must keep one facing");
		helper.assertTrue(level.getBlockEntity(pos.above()) == null,
			"The upper Computer fixture half must never own a block entity");
	}

	private static ServerPlayer headlessPlayer(ServerLevel level, BlockPos pos) {
		FakePlayer player = new FakePlayer(level,
			new GameProfile(UUID.randomUUID(), "computer-install-test"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(pos));
		return player;
	}

	private static Villager villager(ServerLevel level, int villagerLevel) {
		Villager villager = create(EntityType.VILLAGER, level);
		villager.setBaby(false);
		villager.setVillagerData(new VillagerData(VillagerType.PLAINS,
			VillagerProfession.NONE, villagerLevel));
		return villager;
	}

	private static ItemStack captured(LivingEntity entity) {
		ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
		if (!CapturedEntityBoxHelper.captureEntity(box, entity))
			throw new IllegalStateException("Could not capture " + entity.getType());
		return box;
	}

	private static void editCapturedEntityTag(ItemStack box, Consumer<CompoundTag> editor) {
		CompoundTag root = CBItemData.getOrEmpty(box);
		CompoundTag captured = root.getCompound("CapturedEntity");
		editor.accept(captured);
		root.put("CapturedEntity", captured);
		CBItemData.set(box, root);
	}

	private static void addCollision(ServerLevel level, Entity entity, String fixture) {
		entity.moveTo(0.5, 80, 0.5);
		if (!level.addFreshEntity(entity) || level.getEntity(entity.getUUID()) != entity)
			throw new IllegalStateException("Could not add " + fixture);
	}

	private static <T extends Entity> T create(EntityType<T> type, ServerLevel level) {
		return Objects.requireNonNull(type.create(level), () -> "Could not create " + type);
	}
}
