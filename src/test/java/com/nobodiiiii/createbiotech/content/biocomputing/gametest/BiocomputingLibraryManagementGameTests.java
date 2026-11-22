package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingCodec;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryManagementGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void applicationArchiveRejectsPartialRestoreAndRebuildsTheRegisteredCore(GameTestHelper helper) {
        var f = new Fixture(helper); var level = helper.getLevel();
        f.core.tick();
        var original = f.core.saveWithFullMetadata(level.registryAccess());
        var book = f.shelf.getItem(2).copy(); var id = f.core.memberId();
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE).status();
        List<Consumer<net.minecraft.nbt.CompoundTag>> corruptions = List.of(
            pattern -> pattern.getCompound("ServerState").getCompound("RoutingConfiguration").remove("Revision"),
            pattern -> pattern.getCompound("ServerState").putInt("PageTopologies", 0),
            pattern -> pattern.getCompound("ServerState").putString("MovementClaimToken", "invalid"),
            pattern -> pattern.putByte("BindingStateInvalid", (byte)2),
            pattern -> pattern.putBoolean("UnknownEnvelopeField", true));
        for (var corrupt : corruptions) {
            var invalid = original.copy(); var pattern = invalid.getCompound("PatternLibrary");
            pattern.putUUID("LibraryId", UUID.randomUUID());
            pattern.putBoolean("BindingStateInvalid", true);
            pattern.getCompound("ServerState").putBoolean("PendingSafeRelease", true);
            pattern.getCompound("ServerState").putUUID("MovementClaimToken", UUID.randomUUID());
            corrupt.accept(pattern);
            f.core.loadWithComponents(invalid, level.registryAccess());
            helper.assertValueEqual(f.core.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary"),
                original.getCompound("PatternLibrary"), "Malformed envelope cannot partially replace any application owner");
            helper.assertValueEqual(f.core.memberId(), id, "Physical owner identity does not change on rejected load");
            helper.assertTrue(f.core.libraryIdentityReady() && f.core.canPlayerEdit(f.player), "Rejected archive cannot revoke the current admitted owner");
            helper.assertValueEqual(f.core.getBehaviour(LibraryServiceBehaviour.TYPE).status(), service, "Generic service stays independent");
            helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Archive recovery never rewrites the physical medium");
        }
        level.removeBlockEntity(f.pos);
        var rebuilt = (LibraryBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            f.pos, level.getBlockState(f.pos), original, level.registryAccess());
        level.setBlockEntity(rebuilt); rebuilt.initialize();
        helper.assertValueEqual(rebuilt.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary"),
            original.getCompound("PatternLibrary"), "Valid archive restores every application field in the actual registered entity");
        helper.assertTrue(rebuilt.libraryIdentityReady() && rebuilt.canPlayerEdit(f.player), "Rebuilt physical owner reclaims identity and local access");
        helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(43), "Independent generic entry is still readable");
        helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Positive rebuild also preserves the entire book");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void boundPhysicalBooksRemainIndexedWithoutStartingRetiredProduction(GameTestHelper helper) {
        var f = new Fixture(helper); var level = helper.getLevel(); var server = level.getServer();
        var binding = new ProductionBinding(UUID.randomUUID(), 6,
            new ProductionBinding.Authority(
                ProductionBinding.Role.LIBRARY, f.core.memberId()), List.of());
        var historical = f.core.saveWithFullMetadata(level.registryAccess());
        historical.getCompound("PatternLibrary").put("BindingState", ProductionBindingCodec.save(binding));
        f.core.loadWithComponents(historical, level.registryAccess());
        String recipe = "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:paper\",\"count\":1}],\"address\":\"session_probe\"}";
        var book = f.shelf.getItem(2).copy();
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(recipe))));
        f.shelf.setItem(2, book);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(f.core.summary().indexedPatterns(), 1,
                "Real book can be indexed without an orchestration server"))
            .thenExecute(() -> {
                var saved = f.core.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(f.pos);
                var rebuilt = (LibraryBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    f.pos, level.getBlockState(f.pos), saved, level.registryAccess());
                level.setBlockEntity(rebuilt);
                rebuilt.initialize(); rebuilt.tick();
                helper.assertValueEqual(rebuilt.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary").get("BindingState"), ProductionBindingCodec.save(binding), "Historical attachment survives without publishing");
                helper.assertTrue(com.nobodiiiii.createbiotech.CreateBiotech.class.getResource(
                    "/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.class") == null,
                    "Neither restoring nor ticking an attached book starts the retired backend");
                var target = rebuilt.advancedGoggleMetadata().capture(rebuilt);
                var details = com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryGoggleDetails.capture(rebuilt, target, 5);
                helper.assertTrue(details.library().stream().anyMatch(line -> line.contains("cachedPages="))
                    && details.slots().isEmpty() && details.agents().isEmpty() && details.logistics().isEmpty(),
                    "Advanced goggles report the Library, not the historical production binding");
                helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Pages and ordinary data stay on the same book");
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void historicalBindingRestrictsEditsWithoutPublishingTheRebuiltLibrary(GameTestHelper helper) {
        var f = new Fixture(helper); var level = helper.getLevel(); var group = UUID.randomUUID();
        var book = f.shelf.getItem(2).copy();
        var first = new ProductionBinding(group, 4,
            new ProductionBinding.Authority(
                ProductionBinding.Role.LIBRARY, f.core.memberId()), List.of());
        var saved = f.core.saveWithFullMetadata(level.registryAccess());
        var pattern = saved.getCompound("PatternLibrary");
        pattern.put("BindingState", ProductionBindingCodec.save(first));
        level.removeBlockEntity(f.pos);
        var rebuilt = (LibraryBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            f.pos, level.getBlockState(f.pos), saved, level.registryAccess());
        level.setBlockEntity(rebuilt); rebuilt.initialize(); rebuilt.tick();
        helper.assertTrue(rebuilt.libraryIdentityReady(), "Rebuilt book device reclaims its identity");
        helper.assertTrue(com.nobodiiiii.createbiotech.CreateBiotech.class.getResource(
            "/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndex.class") == null,
            "Historical binding must not publish the real Library in the production directory");
        helper.assertValueEqual(rebuilt.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary")
            .get("BindingState"), ProductionBindingCodec.save(first), "Keep the frozen binding without authorizing old production");
        var invalid = saved.copy();
        invalid.getCompound("PatternLibrary").getCompound("BindingState").putInt("Version", 999);
        rebuilt.loadWithComponents(invalid, level.registryAccess());
        helper.assertTrue(rebuilt.canPlayerUse(f.player) && !rebuilt.canPlayerEdit(f.player),
            "Unknown historical policy denies writes, not local read-only browsing");
        helper.assertValueEqual(rebuilt.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary").get("BindingState"),
            invalid.getCompound("PatternLibrary").get("BindingState"), "Unknown policy data is not silently erased");
        helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Production attachment never modifies the physical data medium or its pages");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void corruptColdLibraryPolicyIsNotResavedAsAnUnprotectedLibrary(GameTestHelper helper) {
        var f = new Fixture(helper); var level = helper.getLevel();
        var saved = f.core.saveWithFullMetadata(level.registryAccess());
        var pattern = saved.getCompound("PatternLibrary"); pattern.putString("BindingStateInvalid", "unreadable");
        var book = f.shelf.getItem(2).copy();
        level.removeBlockEntity(f.pos);
        var rebuilt = (LibraryBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            f.pos, level.getBlockState(f.pos), saved, level.registryAccess());
        level.setBlockEntity(rebuilt); rebuilt.initialize(); rebuilt.tick();
        helper.assertTrue(!rebuilt.canPlayerEdit(f.player) && !rebuilt.libraryIdentityReady(),
            "A malformed disk envelope cannot grant edits or activate a new storage identity");
        helper.assertValueEqual(rebuilt.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary"),
            pattern, "Keep the rejected disk envelope for recovery instead of saving unprotected defaults");
        helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Quarantine never edits the book");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void independentPlayerAccessRechecksActorRangeAndExactPhysicalOwner(GameTestHelper helper) {
        var f = new Fixture(helper);
        var access = new LibraryPlayerAccess(f.core, actor -> true);
        helper.assertTrue(access.canUse(f.player) && access.canEdit(f.player), "Generic access independently admits local building player");
        f.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        helper.assertTrue(access.canUse(f.player) && !access.canEdit(f.player), "Spectator retains viewing but cannot edit");
        f.player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
        helper.assertTrue(access.canUse(f.player) && !access.canEdit(f.player), "No-build actor retains viewing but cannot edit");
        f.player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(f.pos);
        f.player.setPos(center.add(8, 0, 0));
        helper.assertTrue(access.canUse(f.player), "Existing eight-block boundary is inclusive");
        f.player.setPos(center.add(8.01, 0, 0));
        helper.assertTrue(!access.canUse(f.player) && !access.canEdit(f.player), "Outside boundary denies both view and edit");
        f.player.setPos(center);
        var level = helper.getLevel(); var saved = f.core.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(f.pos);
        level.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(f.pos, level.getBlockState(f.pos), saved, level.registryAccess()));
        helper.assertTrue(!access.canUse(f.player), "Same coordinates and saved identity do not rebind a stale access object");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void openedBookMenuObservesLiveProductionRestrictionWithoutLosingReadAccess(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(f.core.openBookLibrary(f.player), "Actual core opens the bound generic access session");
        var level = helper.getLevel(); var original = f.core.saveWithFullMetadata(level.registryAccess());
        helper.startSequence().thenExecute(() -> f.ask(LibraryBookRequest.Action.REFRESH, -1, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> f.ask(LibraryBookRequest.Action.SELECT, 0, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> {
                var invalid = original.copy(); invalid.getCompound("PatternLibrary").putBoolean("BindingStateInvalid", true);
                f.core.loadWithComponents(invalid, level.registryAccess());
                helper.assertTrue(f.core.canPlayerUse(f.player) && !f.core.canPlayerEdit(f.player), "Production restriction revokes edits, not local viewing");
                var book = f.shelf.getItem(2).copy();
                f.ask(LibraryBookRequest.Action.PUT, -1, "reading", new DataValue.IntegerValue(99), "denied");
                helper.assertTrue(ItemStack.matches(book, f.shelf.getItem(2)), "Denied mutation leaves the complete medium unchanged");
            }).thenIdle(1).thenExecute(() -> {
                var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE); var before = service.status();
                f.ask(LibraryBookRequest.Action.CONFIGURE_SERVICE, -1, "", new DataValue.RecordValue(Map.of()), "denied");
                helper.assertValueEqual(service.status(), before, "Revoked application permission cannot change volume grants");
            }).thenIdle(1).thenExecute(() -> {
                var state = f.ask(LibraryBookRequest.Action.READ, -1, "reading", null, "ok");
                helper.assertValueEqual(((DataValue.RecordValue)state.field("loaded")).field("value"), new DataValue.IntegerValue(43), "Authorized local viewing still reads the same medium");
                f.core.loadWithComponents(original, level.registryAccess());
                helper.assertTrue(f.core.canPlayerEdit(f.player), "Restored valid application state is observed without recreating the menu");
            }).thenIdle(1).thenExecute(() -> {
                f.ask(LibraryBookRequest.Action.PUT, -1, "reading", new DataValue.IntegerValue(44), "ok");
                helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Live grant restoration allows the explicit new mutation");
                helper.assertValueEqual(f.shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT), f.pages, "Actor access never rewrites protected pages");
                f.player.containerMenu.removed(f.player);
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void localCoreAccessRejectsPlayerInAnotherDimensionAtTheSameCoordinates(GameTestHelper helper) {
        var f = new Fixture(helper);
        var otherLevel = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(otherLevel != null && otherLevel != helper.getLevel(), "Distinct loaded dimension for access regression");
        var foreign = new MenuPlayer(otherLevel);
        foreign.setPos(f.player.position());
        helper.assertTrue(f.core.canPlayerUse(f.player), "Nearby local player remains eligible");
        helper.assertTrue(!f.core.canPlayerUse(foreign), "Matching coordinates in another dimension must not grant local Library use");
        helper.assertTrue(!f.core.canPlayerEdit(foreign), "Foreign player cannot edit local book pages or grants");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rejectedCopiedIdentityCannotOpenLocalBookManagement(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        var saved = f.core.saveWithFullMetadata(level.registryAccess());
        var copyPos = f.pos.south(4);
        level.setBlock(copyPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(copyPos, level.getBlockState(f.pos), Block.UPDATE_ALL);
        level.setBlock(copyPos.above(), level.getBlockState(f.pos.above()), Block.UPDATE_ALL);
        var copy = (LibraryBlockEntity)level.getBlockEntity(copyPos);
        copy.loadWithComponents(saved, level.registryAccess());
        copy.initialize();
        helper.assertTrue(f.core.libraryIdentityReady() && !copy.libraryIdentityReady(), "Only original physical identity is admitted");
        f.player.setPos(copyPos.getX() + .5, copyPos.getY(), copyPos.getZ() + .5);
        helper.assertTrue(copy.canPlayerUse(f.player), "Ordinary nearby-player permission does not explain refusal");
        helper.assertTrue(!copy.openBookLibrary(f.player), "Rejected identity must not open local book management");
        helper.assertTrue(f.player.containerMenu == f.player.inventoryMenu, "No unowned book session is created");
        helper.assertValueEqual(f.player.lastMessage,
            net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.library.code.owner_unavailable"),
            "Authorized nearby player receives an explicit ownership-unavailable diagnosis, not a silent button");
        helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(43),
            "Original physical book remains unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void genericManagementReadsAndEditsPhysicalBookWithoutReplacingItsPages(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(LibraryBookManagement.open(new LibraryPlayerAccess(f.core, actor -> true), f.player), "New management owner opens the actual menu");
        helper.startSequence().thenExecute(() -> {
            var state = f.ask(LibraryBookRequest.Action.REFRESH, -1, "", null, "ok");
            helper.assertValueEqual(((DataValue.ListValue)state.field("books")).values().size(), 1, "Bridge reaches one real book");
        }).thenIdle(1).thenExecute(() -> f.ask(LibraryBookRequest.Action.SELECT, 0, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> {
                var state = f.ask(LibraryBookRequest.Action.READ, -1, "reading", null, "ok");
                helper.assertValueEqual(((DataValue.RecordValue)state.field("loaded")).field("value"), new DataValue.IntegerValue(43), "Read through public menu request");
            }).thenIdle(1).thenExecute(() -> {
                f.ask(LibraryBookRequest.Action.PUT, -1, "reading", new DataValue.IntegerValue(44), "ok");
                helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Write reached physical component");
                helper.assertValueEqual(f.shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT), f.pages, "Human and recipe pages unchanged");
                helper.assertTrue(f.player.clientMenu != null, "Opening data constructs the registered client menu");
                f.player.containerMenu.removed(f.player);
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void physicalDoorwayRetainsLiveEditPermissionAndExactOwner(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(f.core.openBookLibrary(f.player), "Actual core doorway opens generic management");
        var menu = f.player.containerMenu;
        var previousPosition = f.player.position();
        f.player.setPos(previousPosition.add(16, 0, 0));
        helper.assertTrue(!LibraryBookManagement.open(new LibraryPlayerAccess(f.core, actor -> true), f.player), "Out-of-range viewing cannot open another menu");
        f.player.setPos(previousPosition);
        helper.assertTrue(f.player.containerMenu == menu, "Denied opening leaves current menu untouched");
        helper.startSequence().thenExecute(() -> f.ask(LibraryBookRequest.Action.REFRESH, -1, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> f.ask(LibraryBookRequest.Action.SELECT, 0, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> {
                f.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                helper.assertTrue(!f.core.canPlayerEdit(f.player), "Real host revokes spectator edit permission");
                f.ask(LibraryBookRequest.Action.PUT, -1, "reading", new DataValue.IntegerValue(99), "denied");
                helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(43), "Permission loss cannot alter book");
            }).thenIdle(1).thenExecute(() -> {
                var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
                var before = service.status();
                f.ask(LibraryBookRequest.Action.CONFIGURE_SERVICE, -1, "", new DataValue.RecordValue(Map.of()), "denied");
                helper.assertValueEqual(service.status(), before, "Permission loss also protects service grants");
            }).thenIdle(1).thenExecute(() -> {
                var state = f.ask(LibraryBookRequest.Action.READ, -1, "reading", null, "ok");
                helper.assertValueEqual(((DataValue.RecordValue)state.field("loaded")).field("value"), new DataValue.IntegerValue(43), "Read access is distinct from edit access");
                var level = helper.getLevel(); var saved = f.core.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(f.pos);
                var replacement = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(f.pos, level.getBlockState(f.pos), saved, level.registryAccess());
                level.setBlockEntity(replacement);
                helper.assertTrue(!menu.stillValid(f.player), "Same position and saved identity cannot rebind old menu to a replacement entity");
                int replies = f.player.replies.size();
                new LibraryBookRequest(menu.containerId, f.player.nonce, ++f.intent, f.revision, LibraryBookRequest.Action.REFRESH,
                    -1, "", LibraryEntry.Kind.DATA, new byte[0]).handle(f.player);
                helper.assertValueEqual(f.player.replies.size(), replies, "Expired menu request is ignored");
                menu.removed(f.player);
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void bridgeUpdatesInvalidateSelectionWithoutRetargetingThePhysicalBook(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(f.core.openBookLibrary(f.player), "Open from actual core");
        helper.startSequence().thenExecute(() -> f.ask(LibraryBookRequest.Action.REFRESH, -1, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> f.ask(LibraryBookRequest.Action.SELECT, 0, "", null, "ok"))
            .thenIdle(1).thenExecute(() -> {
                helper.getLevel().setBlock(f.pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                int replies = f.player.replies.size(); f.player.containerMenu.broadcastChanges();
                helper.assertValueEqual(f.player.replies.size(), replies + 1, "Real bridge update pushes an invalidation without a refresh request");
                var reply = f.player.replies.getLast();
                helper.assertValueEqual(reply.code(), "changed", "Structure update is visible to the management session");
                var state = f.value(reply);
                helper.assertTrue(((DataValue.ListValue)state.field("books")).values().isEmpty(), "Detached medium no longer appears");
                helper.assertValueEqual(state.field("selected"), new DataValue.IntegerValue(-1), "Selection is cleared, not rebound");
                // Deliberately send the previously selected revision, as a delayed client could.
                f.ask(LibraryBookRequest.Action.PUT, -1, "reading", new DataValue.IntegerValue(99), "changed");
                helper.assertValueEqual(f.media.read(f.shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(43), "Detached book remains unchanged");
            }).thenIdle(1).thenExecute(() -> {
                helper.getLevel().setBlock(f.pos.east(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
                f.player.containerMenu.broadcastChanges();
                var reply = f.player.replies.getLast(); f.revision = reply.revision();
                helper.assertValueEqual(((DataValue.ListValue)f.value(reply).field("books")).values().size(), 1, "Rebuilt bridge is discovered through its update");
                helper.assertValueEqual(f.value(reply).field("selected"), new DataValue.IntegerValue(-1), "Reconnect does not silently restore a stale selection");
                f.ask(LibraryBookRequest.Action.SELECT, 0, "", null, "ok");
            }).thenIdle(1).thenExecute(() -> {
                var state = f.ask(LibraryBookRequest.Action.READ, -1, "reading", null, "ok");
                helper.assertValueEqual(((DataValue.RecordValue)state.field("loaded")).field("value"), new DataValue.IntegerValue(43), "Explicit reselection recovers original data");
                f.player.containerMenu.removed(f.player);
            }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final BlockPos pos;
        final LibraryBlockEntity core;
        final ChiseledBookShelfBlockEntity shelf;
        final MenuPlayer player;
        final LibraryBookMedia media;
        final WritableBookContent pages;
        long intent, revision;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var level = helper.getLevel(); pos = helper.absolutePos(new BlockPos(3, 2, 3));
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            core = (LibraryBlockEntity)level.getBlockEntity(pos);
            core.initialize(); // Fixture enters the same admission lifecycle as a loaded physical core.
            level.setBlock(pos.east(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.east(2), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.east(2));
            media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            var human = Filterable.passThrough("human notes");
            var recipe = Filterable.passThrough("recipe source page");
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(human, recipe)));
            book = media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43)));
            var id = com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation.Version
                .decode(media.stat(book, "reading").field("version")).medium();
            pages = new WritableBookContent(List.of(human, recipe, Filterable.passThrough(LibraryBookIdentityPage.text(id))));
            shelf.setItem(2, new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book, "logs"));
            player = new MenuPlayer(level);
            player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        }
        DataValue.RecordValue ask(LibraryBookRequest.Action action, int book, String name, DataValue value, String expected) {
            var bounds = CBConfigs.SERVER.biocomputing.current(true).archive();
            var request = new LibraryBookRequest(player.containerMenu.containerId, player.nonce, ++intent, revision,
                action, book, name, LibraryEntry.Kind.DATA, value == null ? new byte[0] : ProgramEditorWire.encodeValue(value, bounds));
            int before = player.replies.size();
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            try { request.write(buffer); new LibraryBookRequest(buffer).handle(player); } finally { buffer.release(); }
            helper.assertValueEqual(player.replies.size(), before + 1, "One actual response for one menu request");
            var reply = player.replies.getLast(); revision = reply.revision();
            helper.assertValueEqual(reply.intent(), intent, "Response belongs to current request");
            helper.assertValueEqual(reply.code(), expected, "Expected management result for " + action);
            return value(reply);
        }
        DataValue.RecordValue value(LibraryBookResponse reply) {
            return (DataValue.RecordValue)ProgramEditorWire.decodeValue(reply.state(), reply.limits());
        }
    }

    /** Real menu and packet codecs; no socket or rendered client is claimed by this test. */
    private static final class MenuPlayer extends FakePlayer {
        net.minecraft.network.chat.Component lastMessage;
        @Override public void displayClientMessage(net.minecraft.network.chat.Component message, boolean overlay) {
            lastMessage = message;
        }
        UUID nonce;
        LibraryBookMenu clientMenu;
        final List<LibraryBookResponse> replies = new ArrayList<>();
        MenuPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "library-management"));
            connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), this,
                net.minecraft.server.network.CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom && custom.payload() instanceof LibraryBookResponse reply) {
                        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
                        try { reply.write(buffer); replies.add(new LibraryBookResponse(buffer)); } finally { buffer.release(); }
                    }
                }
            };
        }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> openingData) {
            if (containerMenu != inventoryMenu) containerMenu.removed(this);
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                openingData.accept(buffer); nonce = buffer.readUUID(); buffer.readerIndex(0);
                clientMenu = CBMenuTypes.BIOCOMPUTING_LIBRARY.get().create(1, getInventory(), buffer);
            } finally { buffer.release(); }
            return OptionalInt.of(1);
        }
    }
}
