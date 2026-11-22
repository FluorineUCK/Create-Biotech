package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookMedia;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeases;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllItems;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryBlockGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void boxedLibrarianConvertsVanillaLectern(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 2, 3));
        var lectern = Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, lectern);
        var player = new MenuPlayer(level);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() - 1.5);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var hand = InteractionHand.MAIN_HAND;
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
        var villager = EntityType.VILLAGER.create(level);
        for (var profession : List.of(VillagerProfession.FARMER, VillagerProfession.LIBRARIAN)) {
            villager.setVillagerData(new VillagerData(VillagerType.SWAMP, profession, 4));
            var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            CapturedEntityBoxHelper.captureEntity(box, villager);
            var before = box.copy();
            player.setItemInHand(hand, box);
            if (profession == VillagerProfession.LIBRARIAN) level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
            player.gameMode.useItemOn(player, level, box, hand, hit);
            helper.assertTrue(level.getBlockState(pos).equals(lectern) && ItemStack.matches(before, player.getItemInHand(hand)),
                "Wrong profession or obstructed headroom preserves lectern and resident");
        }
        level.removeBlock(pos.above(), false);
        var box = player.getItemInHand(hand);
        var savedBox = box.copy();
        player.gameMode.useItemOn(player, level, box, hand, hit);
        helper.assertTrue(LibraryCoreBlock.isComplete(level.getBlockState(pos), level.getBlockState(pos.above())),
            "Box on vanilla lectern forms the two-block Library in place");
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        helper.assertTrue(level.getBlockState(pos).getValue(LibraryCoreBlock.FACING) == Direction.EAST
            && core.libraryIdentityReady() && core.clientState().renderLibrarian(), "Facing, resident and usable identity");
        helper.assertTrue(ItemStack.matches(savedBox, core.captureLibraryRemoval().resident())
            && player.getItemInHand(hand).is(CBItems.CARDBOARD_BOX.get()) && !CapturedEntityBoxHelper.hasCapturedEntity(box),
            "The original resident moved exactly once, leaving the empty box");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void registeredLibraryRestoresHistoricalIdAndKeepsMediaExternal(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertValueEqual(f.core.getClass().getName(),
            "com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity",
            "The registered device, not a second optional implementation, is owned by biocomputing");
        var resident = EntityType.VILLAGER.create(f.level);
        resident.setVillagerData(new VillagerData(VillagerType.SWAMP, VillagerProfession.LIBRARIAN, 4));
        resident.setHealth(9);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Capture a non-default resident");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, box);
        helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, box, InteractionHand.MAIN_HAND,
            f.hit(f.pos)).consumesAction(), "Install resident through the actual held-item path");
        var book = f.shelf.getItem(2).copy();
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("""
            {"version":2,"type":"standard","inputs":[],
             "outputs":[{"item":"minecraft:paper","count":1}],"address":"registered-library"}
            """))));
        f.shelf.setItem(2, book.copy());
        var configuration = new LibraryServiceConfiguration(Optional.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("archive", false)),
            Map.of("logs", LibraryServiceConfiguration.Access.READ_WRITE));
        helper.assertValueEqual(f.core.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, configuration),
            "configured", "Save a non-default authorized service configuration");
        helper.runAfterDelay(80, () -> {
            helper.assertValueEqual(f.core.recipeSnapshot().records().size(), 1,
                "Natural maintenance builds real recipe history before the registry round trip");
            helper.assertTrue(f.core.clientState().renderLibrarian(), "Source archive includes the installed resident");
            var saved = f.core.saveWithFullMetadata(f.level.registryAccess());
            helper.assertValueEqual(saved.getString("id"), "create_biotech:pattern_storage_core",
                "Existing worlds retain the historical registry identity");
            f.level.removeBlockEntity(f.pos);
            var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                f.pos, f.level.getBlockState(f.pos), saved, f.level.registryAccess());
            helper.assertTrue(restored != null && restored.getClass() == f.core.getClass(),
                "Vanilla resolves a saved historical ID to the actual new entity factory");
            f.level.setBlockEntity(restored);
            helper.assertValueEqual(restored.saveWithFullMetadata(f.level.registryAccess()), saved,
                "Non-default resident, recipe history, routing and generic service archive survive together");
            ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)restored).initialize();
            var service = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)restored)
                .getBehaviour(LibraryServiceBehaviour.TYPE);
            helper.assertTrue(service != null, "Reloaded device installs its real external storage service");
            helper.assertValueEqual(service.status().field("configuration"), configuration.value(),
                "Port name, disabled state and explicit volume write grant remain intact");
            helper.assertTrue(((com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreHost)restored)
                .libraryIdentityReady(), "Reload retires the old claim and admits the exact new device");
            helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)),
                "Recipe page and generic book component remain external, unchanged physical media");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void admittedServiceSurvivesActualWrenchItemCodecAndPlacement(GameTestHelper helper) {
        var f = new Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        var grants = Map.of("logs", LibraryServiceConfiguration.Access.READ_ONLY);
        var open = new LibraryServiceConfiguration(Optional.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("books", true)), grants);
        var closed = new LibraryServiceConfiguration(Optional.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("books", false)), grants);
        helper.assertValueEqual(service.configure(0, open), "configured", "Explicitly authorize one volume");
        var sender = UUID.randomUUID(); var event = UUID.randomUUID();
        var request = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(
            sender, 0, event, 0, new DataValue.RecordValue(Map.of("op", new DataValue.TextValue("read"),
                "volume", new DataValue.TextValue("logs"), "entry", new DataValue.TextValue("reading"))),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageProtocol.Request(
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestContext(
                    new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestCorrelation(sender, event, 0),
                    "missing-return")));
        helper.assertValueEqual(service.receive(request),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.ACCEPTED,
            "Core durably accepts the request before removal");
        helper.assertValueEqual(service.configure(1, closed), "configured", "Disable execution without discarding admitted work");
        var before = f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT).copy();
        f.wrench(true);
        helper.assertValueEqual(f.coreDrops().size(), 1, "One ordinary item owns the admitted work");
        var dropped = f.coreDrops().getFirst();
        var ops = f.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = ItemStack.CODEC.encodeStart(ops, dropped.getItem()).getOrThrow();
        var held = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
        dropped.discard();
        f.player.setItemInHand(InteractionHand.MAIN_HAND, held);
        helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND,
            f.hit(f.pos.below())).consumesAction(), "Saved item places through the actual player dispatcher");
        var placed = (LibraryBlockEntity)f.level.getBlockEntity(f.pos);
        helper.assertTrue(placed != null && held.isEmpty(), "Placement consumes exactly the carrying item");
        var restored = placed.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(restored.status().field("configuration"), closed.value(), "Explicit volume grant and disabled port survive");
        helper.assertValueEqual(restored.status().field("queued"), new DataValue.IntegerValue(1), "Admitted request survives item time");
        helper.assertValueEqual(placed.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT), before,
            "Exact archive preserves dispatch identity, revisions, receipt and request together");
        helper.assertValueEqual(restored.configure(2, open), "configured", "Original configuration revision remains usable");
        helper.assertValueEqual(restored.receive(request),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.DUPLICATE,
            "Retry remains a duplicate rather than admitting the request again");
        f.assertBookUnchanged(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void survivalBreakDispatchRemovesIndependentLibraryHost(GameTestHelper helper) {
        verifyIndependentPlayerBreak(helper, GameType.SURVIVAL);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void creativeBreakDispatchStillRecoversOneClaimingCore(GameTestHelper helper) {
        verifyIndependentPlayerBreak(helper, GameType.CREATIVE);
    }

    private static void verifyIndependentPlayerBreak(GameTestHelper helper, GameType mode) {
        var f = new Fixture(helper);
        var item = f.core.asConfiguredStack();
        var host = f.installIndependentHost();
        f.player.gameMode.changeGameModeForPlayer(mode);
        f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        f.player.gameMode.destroyBlock(f.pos.above());
        helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()),
            "Actual registered break subscriber removes the independent host in " + mode);
        helper.assertTrue(host.released, "Break dispatch commits exactly the admitted host");
        helper.assertValueEqual(f.coreDrops().size(), 1, "Survival and creative recover one item, never a second vanilla drop");
        helper.assertTrue(ItemStack.matches(item, f.coreDrops().getFirst().getItem()), "Break preserves the original claiming configuration");
        f.assertBookUnchanged();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void protectedPlayerBreakDoesNotRunLibraryRemoval(GameTestHelper helper) {
        var f = new Fixture(helper);
        var host = f.installIndependentHost();
        f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        int[] seen = {0};
        Consumer<net.neoforged.neoforge.event.level.BlockEvent.BreakEvent> protect = event -> {
            if (event.getLevel() == f.level && event.getPos().equals(f.pos.above())) {
                seen[0]++;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, protect);
        try {
            f.player.gameMode.destroyBlock(f.pos.above());
            helper.assertValueEqual(seen[0], 1, "Actual player dispatch encounters the protection event");
            helper.assertTrue(f.level.getBlockEntity(f.pos) == host && !host.released
                && LibraryCoreBlock.isComplete(f.level.getBlockState(f.pos), f.level.getBlockState(f.pos.above())),
                "Canceled break preserves both parts and the original host");
            helper.assertTrue(f.coreDrops().isEmpty(), "Protection cannot leave a provisional claiming drop");
            f.assertBookUnchanged();
        } finally { NeoForge.EVENT_BUS.unregister(protect); }
        // Same interaction after removing protection proves this was cancellation, not an unreachable handler.
        f.player.gameMode.destroyBlock(f.pos.above());
        helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()) && host.released,
            "The same player can remove the host once protection is lifted");
        helper.assertValueEqual(f.coreDrops().size(), 1, "Only the unprotected attempt emits a core");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void actualWrenchRemovesIndependentLibraryHostWithoutProductionEntity(GameTestHelper helper) {
        var f = new Fixture(helper);
        var item = f.core.asConfiguredStack();
        var independent = f.installIndependentHost();
        f.wrench(true);
        helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()),
            "Real wrench dispatch must remove a Library host without requiring a production BlockEntity");
        helper.assertTrue(independent.released, "Successful removal commits the host release hook");
        helper.assertValueEqual(f.coreDrops().size(), 1, "Exactly one claiming item is emitted");
        helper.assertTrue(ItemStack.matches(item, f.coreDrops().getFirst().getItem()), "Physical output preserves the claiming item");
        f.assertBookUnchanged();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rejectedCopiedIdentityCannotServeItsCopiedStorageGrant(GameTestHelper helper) {
        var f = new Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        var configuration = new LibraryServiceConfiguration(Optional.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("owned-books", true)),
            Map.of("logs", LibraryServiceConfiguration.Access.READ_WRITE));
        helper.assertValueEqual(service.configure(0, configuration), "configured", "Real placed owner opens its own endpoint");
        var saved = f.core.saveWithFullMetadata(f.level.registryAccess());
        var copyPos = f.pos.south(4);
        f.level.setBlock(copyPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        f.level.setBlock(copyPos, f.level.getBlockState(f.pos), Block.UPDATE_ALL);
        f.level.setBlock(copyPos.above(), f.level.getBlockState(f.pos.above()), Block.UPDATE_ALL);
        var copy = (LibraryBlockEntity)f.level.getBlockEntity(copyPos);
        copy.loadWithComponents(saved, f.level.registryAccess());
        copy.initialize();
        helper.assertValueEqual(copy.memberId(), f.core.memberId(), "Fixture carries the exact duplicated identity");
        helper.assertTrue(copy.asConfiguredStack().isEmpty(), "Existing owner makes duplicate identity admission fail");
        var copiedService = copy.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertTrue(copiedService.advertisements().isEmpty(), "Rejected identity must not advertise copied storage grants");
        var message = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(
            UUID.randomUUID(), 0, UUID.randomUUID(), 0, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(7))));
        helper.assertValueEqual(copiedService.receive(message),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.UNAVAILABLE,
            "Rejected identity cannot enqueue remote work");
        helper.assertValueEqual(copiedService.configure(1, configuration), "unavailable", "Rejected identity cannot change its grants");
        helper.assertValueEqual(copiedService.status().field("code"), new DataValue.TextValue("unavailable"),
            "An unavailable device is not an empty working Library");
        helper.assertValueEqual(copy.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT),
            saved.get(LibraryServiceBehaviour.ROOT), "Rejected operations preserve stored service data");
        helper.assertValueEqual(service.advertisement().orElseThrow(), configuration.endpoint().orElseThrow(), "Owner remains available");
        f.assertBookUnchanged();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void freshLibraryPlacesAndMovesWithoutTuning(GameTestHelper helper) {
        var item = CBItems.PATTERN_STORAGE_CORE.get();
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(item).toString(),
            "create_biotech:pattern_storage_core", "Existing item ID remains loadable");
        helper.assertTrue(item.getClass().getPackageName().startsWith(
            "com.nobodiiiii.createbiotech.content.biocomputing."), "Actual registered Library item belongs to the new module");
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var player = new MenuPlayer(level);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() - 1.5);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var stack = new ItemStack(item);
        var name = net.minecraft.network.chat.Component.literal("physical-library-item");
        stack.set(DataComponents.CUSTOM_NAME, name);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, .5, 0), Direction.UP, pos.below(), false);
        helper.assertTrue(player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit).consumesAction(),
            "First click places the Library");
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "No pre-placement tuning menu");
        helper.assertTrue(stack.isEmpty(), "Survival placement consumes one item");
        helper.assertTrue(LibraryCoreBlock.isComplete(level.getBlockState(pos), level.getBlockState(pos.above())),
            "New item creates both physical halves");
        var host = (LibraryBlockEntity)level.getBlockEntity(pos);
        helper.assertFalse(host.routingConfiguration().frequency().configured(), "No implicit routing frequency");
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            var id = host.memberId();
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            var upper = new BlockHitResult(Vec3.atCenterOf(pos.above()), Direction.NORTH, pos.above(), false);
            helper.assertTrue(player.gameMode.useItemOn(player, level, player.getMainHandItem(),
                InteractionHand.MAIN_HAND, upper).consumesAction(), "Sneak wrench recovers untuned core");
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2), e -> e.getItem().is(item));
            helper.assertValueEqual(drops.size(), 1, "Exactly one Library item");
            var recovered = drops.getFirst().getItem().copy();
            drops.getFirst().discard();
            helper.assertValueEqual(LibraryCoreItemData.read(recovered).orElseThrow(), LibraryCoreItemData.identityOnly(id),
                "Recovery carries the Library identity without a routing frequency");
            player.setItemInHand(InteractionHand.MAIN_HAND, recovered);
            helper.assertTrue(player.gameMode.useItemOn(player, level, recovered, InteractionHand.MAIN_HAND, hit).consumesAction(),
                "Even sneak-placement does not reopen a tuning form");
            helper.assertTrue(level.getBlockEntity(pos) instanceof LibraryBlockEntity moved && moved.memberId().equals(id)
                && player.containerMenu == player.inventoryMenu, "One click reclaims the same Library");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void registeredLibraryOwnsPhysicalStateWithoutChangingItsSavedShape(GameTestHelper helper) {
        var block = CBBlocks.PATTERN_STORAGE_CORE.get();
        helper.assertValueEqual(BuiltInRegistries.BLOCK.getKey(block).toString(),
            "create_biotech:pattern_storage_core", "Saved block ID is unchanged");
        helper.assertTrue(block.getClass().getPackageName().startsWith(
            "com.nobodiiiii.createbiotech.content.biocomputing."), "Library physical block belongs to biocomputing");
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (var half : DoubleBlockHalf.values()) {
                var state = block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
                var tag = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                helper.assertValueEqual(BlockState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow(), state,
                    "Saved physical state survives ownership change");
                for (var side : Direction.values())
                    helper.assertValueEqual(((DataPort)block).acceptsCable(state, side),
                        half == DoubleBlockHalf.LOWER && side == facing.getOpposite(), "Only lower rear accepts cable");
                if (half == DoubleBlockHalf.UPPER)
                    helper.assertTrue(((EntityBlock)block).newBlockEntity(BlockPos.ZERO, state) == null, "Upper has no independent host");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualItemPlacesBothHalvesAndWrenchPreservesBooks(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            for (var part : List.of(f.pos, f.pos.above())) {
                f.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var result = f.player.gameMode.useItemOn(f.player, f.level, ItemStack.EMPTY,
                    InteractionHand.MAIN_HAND, f.hit(part));
                helper.assertTrue(result.consumesAction(), "Both physical halves open the player doorway");
                helper.assertTrue(f.player.containerMenu instanceof PatternStorageCoreMenu menu
                    && menu.ownsServerCore(f.core), "Both halves open the same actual host");
                helper.assertTrue(f.player.clientMenu != null, "Real opening bytes construct the registered client menu");
            }
            f.player.containerMenu.removed(f.player);
            f.wrench(false);
            helper.assertTrue(f.level.getBlockEntity(f.pos) == f.core, "Rotation keeps the same host instance");
            helper.assertValueEqual(f.level.getBlockState(f.pos).getValue(LibraryCoreBlock.FACING), Direction.EAST,
                "Top-face wrench rotates lower half clockwise");
            helper.assertValueEqual(f.level.getBlockState(f.pos.above()).getValue(LibraryCoreBlock.FACING), Direction.EAST,
                "Top-face wrench rotates upper half too");
            f.assertBookUnchanged();
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(f.level.getBlockEntity(f.pos) == f.core, "Delayed validation retains rotated pair");
            helper.assertTrue(f.coreDrops().isEmpty(), "Rotation produces no recovered core");
            f.wrench(true);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()), "Sneak wrench removes both halves");
            helper.assertValueEqual(f.coreDrops().size(), 1, "Exactly one configured core is recovered");
            var recovered = LibraryCoreItemData.read(f.coreDrops().getFirst().getItem()).orElseThrow();
            helper.assertValueEqual(recovered.preservedLibraryId(), Optional.of(f.core.memberId()), "Recovered item keeps Library identity");
            helper.assertValueEqual(recovered.frequency(), f.frequency, "Recovered item keeps configured frequency");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void rejectedCoreOutputRestoresThePairAndItsStorageAuthorization(GameTestHelper helper) {
        var f = new Fixture(helper);
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.configure(0, new LibraryServiceConfiguration(Optional.empty(),
            Map.of("logs", LibraryServiceConfiguration.Access.READ_ONLY))), "configured", "Seed non-default storage authorization");
        var originalService = f.core.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT).copy();
        var rejected = new int[1];
        Consumer<EntityJoinLevelEvent> rejectCore = event -> {
            if (event.getLevel() == f.level && event.getEntity() instanceof ItemEntity item
                && LibraryCoreItemData.read(item.getItem()).flatMap(LibraryCoreItemData::preservedLibraryId)
                    .filter(f.core.memberId()::equals).isPresent()) {
                rejected[0]++;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(rejectCore);
        try {
            for (var part : List.of(f.pos, f.pos.above())) {
                f.level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(LibraryCoreBlock.isComplete(f.level.getBlockState(f.pos), f.level.getBlockState(f.pos.above())),
                    "Rejected output restores both halves after either initiating part");
                var restored = (LibraryBlockEntity)f.level.getBlockEntity(f.pos);
                helper.assertTrue(restored != null && !restored.isRemoved(), "Superclass removal must not delete the restored host");
                helper.assertValueEqual(restored.memberId(), f.core.memberId(), "Rollback preserves exact Library identity");
                helper.assertValueEqual(restored.routingConfiguration(), f.core.routingConfiguration(), "Rollback preserves routing configuration");
                helper.assertValueEqual(restored.saveWithoutMetadata(f.level.registryAccess()).get(LibraryServiceBehaviour.ROOT),
                    originalService, "Rollback preserves generic storage service, not only production data");
                helper.assertTrue(f.coreDrops().isEmpty(), "Failed output produces no duplicate core");
                f.assertBookUnchanged();
            }
        } finally { NeoForge.EVENT_BUS.unregister(rejectCore); }
        helper.assertValueEqual(rejected[0], 2, "Both real removal attempts reached the denied spawn boundary");
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertTrue(f.level.getBlockEntity(f.pos) instanceof LibraryBlockEntity restored && !restored.isRemoved(),
                "Restored host survives scheduled validation");
            f.level.setBlock(f.pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()), "Later permitted removal clears the pair");
            helper.assertValueEqual(f.coreDrops().size(), 1, "Later permitted removal outputs once");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void lostSupportSchedulesOneRecoveryWithoutConsumingTheBook(GameTestHelper helper) {
        checkDelayedValidation(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void missingMovedUpperSchedulesRecoveryAndOrphansDoNotDropCores(GameTestHelper helper) {
        checkDelayedValidation(helper, false);
    }

    private static void checkDelayedValidation(GameTestHelper helper, boolean loseSupport) {
        var f = new Fixture(helper);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            if (loseSupport) f.level.setBlock(f.pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            else f.level.setBlock(f.pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            helper.assertTrue(f.level.getBlockEntity(f.pos) == f.core && f.coreDrops().isEmpty(),
                "Neighbor update only schedules validation, not inline destruction");
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()), "Delayed check removes invalid pair");
            helper.assertValueEqual(f.coreDrops().size(), 1, "Invalid pair emits one configured core");
            f.assertBookUnchanged();
            f.level.setBlock(f.pos.above(), CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
                .setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(f.pos.above()), "Orphan upper is removed by its own scheduled check");
            helper.assertValueEqual(f.coreDrops().size(), 1, "An upper with no host cannot duplicate a core");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void capturedResidentUsesOnlyLowerPartAndIsReleasedOnceAfterRotation(GameTestHelper helper) {
        var f = new Fixture(helper);
        for (var floor : BlockPos.betweenClosed(f.pos.offset(-2, -1, -2), f.pos.offset(2, -1, 2)))
            f.level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var resident = EntityType.VILLAGER.create(f.level);
        resident.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.LIBRARIAN, 3));
        resident.setHealth(11);
        var residentId = resident.getUUID();
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Capture real resident fixture into actual item");
        var captured = box.copy(); f.player.setItemInHand(InteractionHand.MAIN_HAND, box);
        f.player.gameMode.useItemOn(f.player, f.level, box, InteractionHand.MAIN_HAND, f.hit(f.pos.above()));
        helper.assertTrue(ItemStack.matches(box, captured) && !f.core.clientState().renderLibrarian(),
            "Upper rejects captured resident without consuming or releasing it");
        helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, box, InteractionHand.MAIN_HAND, f.hit(f.pos)).consumesAction(),
            "Lower accepts resident through actual held-item path");
        helper.assertTrue(!CapturedEntityBoxHelper.hasCapturedEntity(box) && f.core.clientState().renderLibrarian(),
            "Resident ownership transfers from held box to host");
        f.wrench(false);
        helper.assertTrue(f.level.getBlockEntity(f.pos) == f.core && f.core.clientState().renderLibrarian(), "Rotation retains resident host");
        f.wrench(true);
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(f.pos) && f.level.isEmptyBlock(f.pos.above()), "Resident release permits whole-pair dismantling");
            var released = f.level.getEntitiesOfClass(Villager.class, new AABB(f.pos).inflate(5), entity -> entity.getUUID().equals(residentId));
            helper.assertValueEqual(released.size(), 1, "Exactly one resident with the captured identity is released");
            helper.assertValueEqual(released.getFirst().getHealth(), 11f, "Resident health is not reset");
            helper.assertValueEqual(released.getFirst().getVillagerData().getProfession(), VillagerProfession.LIBRARIAN, "Resident profession survives");
            helper.assertValueEqual(f.coreDrops().size(), 1, "Resident release still returns one core");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void residentSurvivesHostReconstructionBeforePlayerRelease(GameTestHelper helper) {
        var f = new Fixture(helper);
        for (var floor : BlockPos.betweenClosed(f.pos.offset(-2, -1, -2), f.pos.offset(2, -1, 2)))
            f.level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var resident = EntityType.VILLAGER.create(f.level);
        resident.setVillagerData(new VillagerData(VillagerType.SWAMP, VillagerProfession.LIBRARIAN, 4));
        resident.setCustomName(net.minecraft.network.chat.Component.literal("Persistent Archivist"));
        resident.setHealth(9);
        var residentId = resident.getUUID();
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Capture actual resident");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, box);
        helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, box, InteractionHand.MAIN_HAND, f.hit(f.pos)).consumesAction(),
            "Install through actual player path before saving");
        var saved = f.core.saveWithFullMetadata(f.level.registryAccess());
        var savedResident = saved.getCompound("PatternLibrary").getCompound("ServerState").get("LibrarianSnapshotBox").copy();
        f.level.removeBlockEntity(f.pos);
        var restored = (LibraryBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            f.pos, f.level.getBlockState(f.pos), saved, f.level.registryAccess());
        helper.assertTrue(restored != null && restored != f.core, "Registered BE codec reconstructs another host");
        f.level.setBlockEntity(restored);
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(restored.memberId(), f.core.memberId(), "Resident reconstruction keeps Library identity");
            helper.assertValueEqual(restored.saveWithoutMetadata(f.level.registryAccess()).getCompound("PatternLibrary")
                .getCompound("ServerState").get("LibrarianSnapshotBox"), savedResident, "Full capture survives the real host codec");
            helper.assertTrue(!CapturedEntityBoxHelper.hasCapturedEntity(box), "Reload does not refill source box");
            f.wrench(true);
        }).thenIdle(3).thenExecute(() -> {
            var released = f.level.getEntitiesOfClass(Villager.class, new AABB(f.pos).inflate(5), entity -> entity.getUUID().equals(residentId));
            helper.assertValueEqual(released.size(), 1, "Reconstructed host releases exactly one original resident");
            helper.assertValueEqual(released.getFirst().getHealth(), 9f, "Health preserved across host reconstruction");
            helper.assertValueEqual(released.getFirst().getCustomName().getString(), "Persistent Archivist", "Name preserved");
            helper.assertValueEqual(released.getFirst().getVillagerData().getType(), VillagerType.SWAMP, "Type preserved");
            helper.assertValueEqual(released.getFirst().getVillagerData().getLevel(), 4, "Level preserved");
            helper.assertValueEqual(f.coreDrops().size(), 1, "Only one configured core output");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void retainedIdentityRejectsRealPlacementUntilExactLeaseDisposal(GameTestHelper helper) {
        var f = new Fixture(helper);
        f.wrench(true);
        var dropped = f.coreDrops().getFirst();
        var held = dropped.getItem().copy(); dropped.discard();
        var before = held.copy();
        var destination = f.pos.offset(4, 0, 0);
        f.level.setBlock(destination.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        f.player.setPos(destination.getX() + .5, destination.getY(), destination.getZ() - 1.5);
        f.player.setItemInHand(InteractionHand.MAIN_HAND, held);
        var leases = LibraryMovementLeases.get(f.level.getServer());
        var token = UUID.randomUUID();
        helper.assertTrue(leases.reserve(f.core.memberId(), token), "Physical movement authority retains the recovered identity");
        try {
            var rejected = f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND, f.hit(destination.below()));
            helper.assertTrue(!rejected.consumesAction(), "A held copy cannot place while the saved identity belongs to movement");
            helper.assertTrue(ItemStack.matches(before, held), "Rejected identity claim does not consume or rewrite the item");
            helper.assertTrue(f.level.isEmptyBlock(destination) && f.level.isEmptyBlock(destination.above())
                && f.level.getBlockEntity(destination) == null, "Rejected lower placement rolls back without a partial pair");
            helper.assertTrue(!leases.release(f.core.memberId(), UUID.randomUUID()), "Wrong token cannot dispose authority");
            helper.assertTrue(leases.release(f.core.memberId(), token), "Exact disposal permits an ordinary item claim again");
            helper.assertTrue(f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND,
                f.hit(destination.below())).consumesAction(), "Same untouched item now places through the real player dispatcher");
            var placed = (LibraryBlockEntity)f.level.getBlockEntity(destination);
            helper.assertValueEqual(placed.memberId(), f.core.memberId(), "Replaced physical core preserves original Library identity");
            helper.assertTrue(held.isEmpty() && LibraryCoreBlock.isComplete(f.level.getBlockState(destination),
                f.level.getBlockState(destination.above())), "Exactly one item creates a complete pair");
            f.assertBookUnchanged();
        } finally { leases.release(f.core.memberId(), token); }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            var placed = (LibraryBlockEntity)f.level.getBlockEntity(destination);
            helper.assertTrue(placed != null && !placed.isRemoved(), "Initialized new host retains its identity claim");
            helper.assertValueEqual(placed.asConfiguredStack().getComponents(), before.getComponents(),
                "Active host can recover the exact item configuration after initialization");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void freshPlacementRejectsUnavailableAuthorityWithoutChangingHeldItemOrWorld(GameTestHelper helper)
        throws ReflectiveOperationException {
        var f = new Fixture(helper);
        var held = new ItemStack(CBItems.PATTERN_STORAGE_CORE.get());
        LibraryCoreItemData.write(held, LibraryCoreItemData.configured(f.frequency, 0, Optional.empty(), 1));
        var before = held.copy();
        var destination = f.pos.offset(4, 0, 0);
        f.level.setBlock(destination.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        f.player.setPos(destination.getX() + .5, destination.getY(), destination.getZ() - 1.5);
        f.player.setItemInHand(InteractionHand.MAIN_HAND, held);
        var server = f.level.getServer();
        var synchronizedItems = new java.util.ArrayList<ItemStack>();
        f.player.inventoryMenu.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
            @Override public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu menu,
                net.minecraft.core.NonNullList<ItemStack> items, ItemStack carried, int[] data) {
                items.forEach(item -> synchronizedItems.add(item.copy()));
            }
            @Override public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot, ItemStack item) {
                synchronizedItems.add(item.copy());
            }
            @Override public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu menu, ItemStack item) {}
            @Override public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int id, int value) {}
        });
        synchronizedItems.clear(); // The server already advertised this stack before client-side placement prediction.
        var original = LibraryMovementLeases.get(server);
        var originalData = original.save(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess());
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putBoolean("FailClosed", true);
        var unavailable = LibraryMovementLeases.load(tag, f.level.registryAccess());
        // Synchronous fault fixture only: retain the actual SavedData owner and restore it before yielding a tick.
        var field = LibraryMovementLeases.class.getDeclaredField("LOADED"); field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var loaded = (Map<net.minecraft.server.MinecraftServer, LibraryMovementLeases>)field.get(null);
        synchronized (loaded) { loaded.put(server, unavailable); }
        try {
            helper.assertTrue(!f.player.gameMode.useItemOn(f.player, f.level, held, InteractionHand.MAIN_HAND,
                f.hit(destination.below())).consumesAction(), "Unavailable authority rejects fresh placement instead of blocking the server thread");
            helper.assertTrue(ItemStack.matches(before, held), "Rejected fresh item is neither consumed nor reconfigured");
            f.player.inventoryMenu.broadcastChanges();
            helper.assertTrue(synchronizedItems.stream().anyMatch(item -> ItemStack.matches(before, item)),
                "Rejected server placement must correct the client-predicted item consumption even when server inventory did not change");
            helper.assertTrue(f.level.isEmptyBlock(destination) && f.level.isEmptyBlock(destination.above())
                && f.level.getBlockEntity(destination) == null, "Rejected placement leaves no half or host");
            helper.assertTrue(unavailable.save(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess()).getBoolean("FailClosed"),
                "Placement cannot clear unavailable authority");
            f.assertBookUnchanged();
        } finally { synchronized (loaded) { loaded.put(server, original); } }
        helper.assertTrue(LibraryMovementLeases.get(server) == original
            && originalData.equals(original.save(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess())),
            "Fault fixture restores the untouched real owner before any other test tick");
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertTrue(f.level.isEmptyBlock(destination) && f.level.isEmptyBlock(destination.above()),
                "No deferred initialization resurrects the rejected core");
            f.assertBookUnchanged();
        }).thenSucceed();
    }

    static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos pos;
        final LibraryBlockEntity core;
        final ChiseledBookShelfBlockEntity shelf;
        final MenuPlayer player;
        final ItemStack originalBook;
        final CommunicationFrequency frequency = CommunicationFrequency.of("library-physical-test");

        Fixture(GameTestHelper helper) {
            this.helper = helper; level = helper.getLevel(); pos = helper.absolutePos(new BlockPos(3, 2, 3));
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            player = new MenuPlayer(level);
            player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() - 1.5);
            player.setYRot(0); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            var item = new ItemStack(CBBlocks.PATTERN_STORAGE_CORE.get());
            LibraryCoreItemData.write(item, LibraryCoreItemData.configured(frequency, 0, Optional.empty(), 0));
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            helper.assertTrue(item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit(pos.below()))).consumesAction(),
                "Actual configured item places the Library");
            helper.assertTrue(LibraryCoreBlock.isComplete(level.getBlockState(pos), level.getBlockState(pos.above())),
                "Actual placement creates the complete pair");
            core = (LibraryBlockEntity)level.getBlockEntity(pos);
            helper.assertTrue(core != null && level.getBlockEntity(pos.above()) == null, "Only lower half owns the host");
            level.setBlock(pos.east(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.east());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("human and recipe pages"))));
            var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            shelf.setItem(2, media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))));
            originalBook = shelf.getItem(2).copy();
        }

        IndependentLibraryHost installIndependentHost() {
            var item = core.asConfiguredStack();
            var id = core.memberId();
            level.removeBlockEntity(pos);
            var independent = new IndependentLibraryHost(pos, level.getBlockState(pos), item);
            level.setBlockEntity(independent);
            helper.assertTrue(independent.claim(level.getServer(), id), "Independent physical host legitimately owns the existing identity");
            return independent;
        }
        BlockHitResult hit(BlockPos part) {
            return new BlockHitResult(Vec3.atCenterOf(part).add(0, .5, 0), Direction.UP, part, false);
        }
        void wrench(boolean sneaking) {
            player.setShiftKeyDown(sneaking);
            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            var menuBefore = player.containerMenu;
            helper.assertTrue(player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit(pos.above())).consumesAction(),
                "Player right-click dispatch reaches the Create wrench on the upper half");
            helper.assertTrue(player.containerMenu == menuBefore, "Wrench use must not be consumed by the Library menu");
            player.setShiftKeyDown(false);
        }
        List<ItemEntity> coreDrops() {
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2),
                item -> item.getItem().is(CBBlocks.PATTERN_STORAGE_CORE.get().asItem()));
        }
        void assertBookUnchanged() {
            helper.assertTrue(level.getBlockEntity(pos.east()) == shelf && ItemStack.matches(shelf.getItem(2), originalBook),
                "Book, original pages and typed data remain owned by the physical shelf");
        }
    }

    /** Deliberately not the legacy BE or its subclass; only the removal contract is implemented. */
    private static final class IndependentLibraryHost extends com.simibubi.create.foundation.blockEntity.SmartBlockEntity
        implements com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRemovalHost {
        private final com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityLifecycle identity =
            new com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityLifecycle(this);
        private final ItemStack item;
        boolean released;
        IndependentLibraryHost(BlockPos pos, BlockState state, ItemStack item) {
            super(com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes.PATTERN_STORAGE_CORE.get(), pos, state);
            this.item = item.copy();
        }
        boolean claim(net.minecraft.server.MinecraftServer server, UUID id) { return identity.claimPlacement(server, Optional.of(id)); }
        @Override public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {}
        @Override public State captureLibraryRemoval() { return new State(ItemStack.EMPTY, false, item); }
        @Override public void completeLibraryResidentRelease() { released = true; setChanged(); }
        @Override public void deferLibraryResidentRelease() { throw new AssertionError("Residentless fixture must not defer a release"); }
        @Override public boolean reclaimLibraryAfterRemoval() { return false; }
        @Override public void invalidate() { try { identity.invalidate(); } finally { super.invalidate(); } }
    }

    /** Actual server and opening-codec path; rendered client acceptance is separate. */
    static final class MenuPlayer extends FakePlayer {
        PatternStorageCoreMenu clientMenu;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "library-physical")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> openingData) {
            if (containerMenu != inventoryMenu) containerMenu.removed(this);
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                openingData.accept(buffer);
                clientMenu = CBMenuTypes.PATTERN_STORAGE_CORE.get().create(1, getInventory(), buffer);
            } finally { buffer.release(); }
            return OptionalInt.of(1);
        }
    }
}
