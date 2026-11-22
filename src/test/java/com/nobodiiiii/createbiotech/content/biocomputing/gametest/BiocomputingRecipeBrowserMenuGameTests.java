package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.*;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingRecipeBrowserMenuGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void independentApplicationHostOpensTheRegisteredMenuAndPreservesTheWire(GameTestHelper helper) {
        var player = new MenuPlayer(helper.getLevel());
        var host = new Host(player, helper.absolutePos(new BlockPos(3, 1, 3)));
        helper.assertTrue(RecipeBrowserMenus.open(host, player, Optional.empty()),
            "Independent non-legacy host must open the actual registered recipe menu");
        var menu = (PatternStorageCoreMenu)player.containerMenu;
        helper.assertValueEqual(host.snapshotReads, 1, "One coherent snapshot supplies generation and initial page");
        helper.assertTrue(menu.ownsServerCore(host), "Exact application host retained");
        helper.assertValueEqual(player.client.openingFence(), menu.openingFence(), "Real opening codec round trip");
        var historical = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        try {
            var pos = host.libraryLocation(); var fence = menu.openingFence();
            historical.writeVarInt(5);
            historical.writeResourceLocation(pos.dimension().location()); historical.writeBoolean(true);
            historical.writeUUID(pos.subLevelId()); historical.writeBlockPos(pos.localPos());
            historical.writeUUID(host.libraryId());
            historical.writeInt(0x43465131); historical.writeByte(1); historical.writeUtf("independent");
            historical.writeInt(7); historical.writeLong(3);
            historical.writeUUID(fence.sessionId());
            historical.writeResourceLocation(pos.dimension().location()); historical.writeBoolean(true);
            historical.writeUUID(pos.subLevelId()); historical.writeBlockPos(pos.localPos());
            historical.writeUUID(host.libraryId()); historical.writeVarLong(9);
            historical.writeBoolean(false); historical.writeVarInt(0);
            var expected = new byte[historical.readableBytes()]; historical.readBytes(expected);
            helper.assertTrue(Arrays.equals(expected, player.openingBytes), "Exact historical v5 menu byte layout remains unchanged");
        } finally { historical.release(); }
        helper.assertTrue(menu.stillValid(player), "Current host is usable");
        host.allowed = false;
        helper.assertFalse(menu.stillValid(player), "Open menu observes dynamic access revocation");
        helper.assertFalse(RecipeBrowserMenus.open(host, player, Optional.empty()), "Revoked host cannot reopen");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void independentMenuFencesIdentityLocationActorsAndMutationIntents(GameTestHelper helper) {
        var player = new MenuPlayer(helper.getLevel()); var stranger = new MenuPlayer(helper.getLevel());
        var host = new Host(player, helper.absolutePos(new BlockPos(3, 1, 3)));
        helper.assertFalse(RecipeBrowserMenus.open(host, stranger, Optional.empty()), "Different actor cannot open");
        helper.assertFalse(RecipeBrowserMenus.open(host, player, Optional.of(UUID.randomUUID())), "Missing selected recipe cannot open editor");
        helper.assertTrue(RecipeBrowserMenus.open(host, player, Optional.empty()), "Ordinary browser opens");
        var menu = (PatternStorageCoreMenu)player.containerMenu; var fence = menu.openingFence();
        var request = new PatternCreateRequestPacket(1, fence, 1, "{}");
        menu.handleCreate(stranger, request);
        helper.assertValueEqual(host.mutations, 0, "Different actor cannot consume intent");
        menu.handleCreate(player, request); menu.handleCreate(player, request);
        helper.assertValueEqual(host.mutations, 1, "Same intent only dispatched once, even when application rejects it");
        var foreign = new BrowserFence(UUID.randomUUID(), fence.coreAddress(), fence.libraryId(), fence.generation());
        menu.handleCreate(player, new PatternCreateRequestPacket(1, foreign, 2, "{}"));
        helper.assertValueEqual(host.mutations, 1, "Foreign session cannot invoke host");
        var savedId = host.id; host.id = UUID.randomUUID();
        helper.assertFalse(menu.stillValid(player), "Changed identity invalidates an existing menu"); host.id = savedId;
        var savedLocation = host.location;
        host.location = new RecipeSourceLocation(savedLocation.dimension(), UUID.randomUUID(), savedLocation.localPos());
        helper.assertFalse(menu.stillValid(player), "Same coordinates in another subspace do not match");
        host.location = savedLocation;
        helper.assertTrue(menu.stillValid(player), "Restored exact host is usable");
        helper.assertFalse(menu.ownsServerCore(new Host(player, savedLocation.localPos())), "Similar host is never the captured object");
        player.containerMenu = player.inventoryMenu;
        menu.handleCreate(player, new PatternCreateRequestPacket(1, fence, 2, "{}"));
        helper.assertValueEqual(host.mutations, 1, "Closed menu cannot dispatch late requests");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void manualMutationIsNotDispatchedAfterEditPermissionIsRevoked(GameTestHelper helper) {
        var player = new MenuPlayer(helper.getLevel());
        var host = new Host(player, helper.absolutePos(new BlockPos(3, 1, 3)));
        helper.assertTrue(RecipeBrowserMenus.open(host, player, Optional.empty()), "Open while authorized");
        var menu = (PatternStorageCoreMenu)player.containerMenu;
        host.editable = false;
        helper.assertTrue(menu.stillValid(player), "Read-only viewing remains usable");
        menu.handleCreate(player, new PatternCreateRequestPacket(1, menu.openingFence(), 1, "{}"));
        menu.handleEdit(player, new PatternEditRequestPacket(1, menu.openingFence(), 2, UUID.randomUUID(), "{}"));
        helper.assertValueEqual(host.mutations, 0, "Manual menu must not invoke a mutation on a now read-only host");
        helper.succeed();
    }

    private static final class Host implements RecipeBrowserHost {
        private final ServerPlayer actor;
        private RecipeSourceLocation location;
        private UUID id = UUID.randomUUID();
        boolean allowed = true, editable = true;
        int mutations, snapshotReads;
        Host(ServerPlayer actor, BlockPos pos) {
            this.actor = actor; location = new RecipeSourceLocation(actor.level().dimension(), UUID.randomUUID(), pos);
        }
        @Override public UUID libraryId() { return id; }
        @Override public RecipeSourceLocation libraryLocation() { return location; }
        @Override public ProductionLibraryRouting routingConfiguration() { return new ProductionLibraryRouting(CommunicationFrequency.of("independent"), 7, 3); }
        @Override public RecipeIndexSnapshot recipeSnapshot() { snapshotReads++; return new RecipeIndexSnapshot(9, false, List.of(), 0, 0, List.of()); }
        @Override public boolean canPlayerUse(Player player) { return allowed && actor == player; }
        @Override public boolean canPlayerEdit(ServerPlayer player) { return editable && canPlayerUse(player); }
        @Override public boolean openBookLibrary(ServerPlayer player) { return canPlayerUse(player); }
        @Override public PatternBrowserService browserService(BrowserFence fence) { return new PatternBrowserService(fence, this::recipeSnapshot); }
        @Override public PatternBrowserDiagnostics browserDiagnostics() { return PatternBrowserDiagnostics.empty(); }
        @Override public String editPattern(ServerPlayer player, BrowserFence fence, UUID pattern, long generation, String json) { mutations++; return "denied"; }
        @Override public String createPattern(ServerPlayer player, BrowserFence fence, String json) { mutations++; return "denied"; }
    }

    private static final class MenuPlayer extends FakePlayer {
        PatternStorageCoreMenu client;
        byte[] openingBytes;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "recipe-menu")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                opening.accept(buffer); openingBytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), openingBytes);
                client = CBMenuTypes.PATTERN_STORAGE_CORE.get().create(1, getInventory(), buffer);
            }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
    }
}
