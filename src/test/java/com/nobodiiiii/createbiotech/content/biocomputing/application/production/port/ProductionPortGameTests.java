package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.AttachFace;
/** Real registered physical host, menu packet, serial wire and native packaging; not a GUI or recipe-completion test. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ProductionPortGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void oldPanelArchiveIsInertAndNewPortsDoNotMintClusterIdentities(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var retired = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_biotech", "factory_panel");
        h.assertFalse(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(retired), "No retired block");
        h.assertFalse(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(retired), "No retired item");
        h.assertFalse(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(retired), "No retired block entity");
        var state = CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState();
        level.setBlock(pos, state, 3);
        var host = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(pos);
        var registries = level.registryAccess();
        var fresh = host.saveWithoutMetadata(registries);
        h.assertTrue(!fresh.contains("PanelId") && !fresh.contains("BindingState"),
            "A newly placed material port must not manufacture a production cluster identity");
        h.assertTrue(!(host instanceof com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember), "No legacy runtime membership");
        var legacy = new net.minecraft.nbt.CompoundTag();
        legacy.putUUID("PanelId", UUID.randomUUID()); legacy.putUUID("ClusterId", UUID.randomUUID());
        var future = new net.minecraft.nbt.CompoundTag(); future.putString("UnknownFutureField", "opaque");
        legacy.put("BindingState", future); legacy.putString("LogisticsBindings", "malformed legacy list");
        legacy.putByte("BindingStateInvalid", (byte)7);
        legacy.putUUID("SelectedNetwork", UUID.randomUUID());
        var historic = fresh.copy(); historic.merge(legacy); host.loadWithComponents(historic, registries);
        var saved = host.saveWithoutMetadata(registries);
        var visible = host.getUpdateTag(registries);
        for (var key : legacy.getAllKeys()) {
            h.assertFalse(saved.contains(key), "Retired panel fields are not retained: " + key);
            h.assertFalse(visible.contains(key), "Historical field not published to clients: " + key);
        }
        var service = host.getBehaviour(ProductionPortBehaviour.TYPE);
        h.assertTrue(service.revision() == 0 && service.status().field("binding") == DataValue.NullValue.INSTANCE,
            "Neither archived networks nor a former authority can authorize this port");
        host.loadWithComponents(fresh, registries);
        h.assertFalse(host.saveWithoutMetadata(registries).contains("BindingState"), "Replacing a snapshot clears stale archive fields");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void realMenuSerialPackagingAndReloadKeepOneOrder(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        var cable = CBBlocks.DATA_CABLE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.WEST), true)
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock.connection(Direction.EAST), true);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), cable, 3);
        var panelState = CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        level.setBlock(pos, panelState, 3);
        var host = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(pos);
        var service = host.getBehaviour(ProductionPortBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)); actor.setShiftKeyDown(false);
        var emptyClick = actor.gameMode.useItemOn(actor, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
            new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.WEST, pos, false));
        h.assertTrue(emptyClick.consumesAction() && actor.containerMenu instanceof ProductionPortMenu,
            "A plain click opens the checked material port instead of the retired factory task panel");
        actor.containerMenu = actor.inventoryMenu;
        var tool = CBItems.LOGISTICS_CONFIGURATION_TOOL.get();
        var tuned = new ItemStack(tool);
        actor.setItemInHand(InteractionHand.MAIN_HAND, tuned);
        var hitPort = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.WEST, pos, false);
        actor.getAbilities().mayBuild = false;
        h.assertValueEqual(tool.useOn(new net.minecraft.world.item.context.UseOnContext(actor, InteractionHand.MAIN_HAND, hitPort)),
            net.minecraft.world.InteractionResult.FAIL, "The carried tool cannot bypass port management permission");
        h.assertTrue(actor.containerMenu == actor.inventoryMenu && service.revision() == 0, "Denied access opens no menu and creates no grant");
        actor.getAbilities().mayBuild = true;
        h.assertValueEqual(tool.useOn(new net.minecraft.world.item.context.UseOnContext(actor, InteractionHand.MAIN_HAND, hitPort)),
            net.minecraft.world.InteractionResult.SUCCESS, "Configuration tool opens the material port");
        h.assertTrue(actor.containerMenu instanceof ProductionPortMenu && service.revision() == 0, "Opening the replacement is not an implicit grant");
        actor.containerMenu = actor.inventoryMenu;
        computer.serialDevice().configure("s0", Direction.EAST, true);
        var network = UUID.randomUUID(); var packagerPos = origin.south(4);
        level.setBlock(packagerPos.west(), Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST), 3);
        level.setBlock(packagerPos.north(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
            .setValue(PackagerLinkBlock.FACING, Direction.NORTH), 3);
        var link = (PackagerLinkBlockEntity)level.getBlockEntity(packagerPos.north());
        var packager = (PackagerBlockEntity)level.getBlockEntity(packagerPos);
        LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
        var chest = (Container)level.getBlockEntity(packagerPos.west()); chest.setItem(0, new ItemStack(Items.STONE, 64));
        var stockLink = packagerPos.north();
        var hitLink = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(stockLink), Direction.NORTH, stockLink, false);
        h.assertValueEqual(tool.useOn(new net.minecraft.world.item.context.UseOnContext(actor, InteractionHand.MAIN_HAND, hitLink)),
            net.minecraft.world.InteractionResult.SUCCESS, "Select the physical Create stock link using the carried tool");
        h.assertValueEqual(LogisticallyLinkedBlockItem.networkFromStack(tuned), network, "The configured tool carries the real native network");
        h.assertValueEqual(tuned.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA).copyTag().getString("id"),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(link.getType()).toString(), "Selection uses the registered native link type");
        var storedTool = tuned.save(level.registryAccess());
        var restoredTool = ItemStack.parseOptional(level.registryAccess(), (net.minecraft.nbt.CompoundTag) storedTool);
        h.assertValueEqual(restoredTool.getItem(), tool, "The tuned non-placeable tool is valid player inventory data");
        h.assertValueEqual(LogisticallyLinkedBlockItem.networkFromStack(restoredTool), network, "Player save preserves the tuned network");
        h.assertValueEqual(service.revision(), 0L, "Selecting a native network does not grant the application access");
        var address = new DataValue.RecordValue(Map.of("x", integer(pos.getX()), "y", integer(pos.getY()), "z", integer(pos.getZ())));
        h.runAfterDelay(1400, () -> h.fail("Port stalled: source=" + computer.serialDevice().view() + "; service=" + service.status()));
        h.runAfterDelay(30, () -> {
            h.assertValueEqual(service.status().field("binding"), DataValue.NullValue.INSTANCE, "Cable does not grant production authority");
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.WEST, pos, false);
            h.assertValueEqual(panelState.useItemOn(tuned, level, actor, InteractionHand.MAIN_HAND, hit), net.minecraft.world.ItemInteractionResult.SUCCESS,
                "Interaction while holding a tuned Create item opens the actual management entry");
            h.assertTrue(actor.containerMenu instanceof ProductionPortMenu, "Actual registered server menu");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "Only physically reachable source is offered");
            var request = actor.client.request(ProductionPortRequest.Action.GRANT, 0, "workshop");
            new ProductionPortRequest(request.containerId(), UUID.randomUUID(), request.revision(), request.action(), request.choice(), request.address()).handle(actor);
            h.assertValueEqual(service.revision(), 0L, "Forged menu nonce cannot grant");
            actor.send(request); h.assertValueEqual(actor.client.code(), "configured", "Real menu codec grants selected source and actual network");
            actor.send(request); h.assertValueEqual(service.revision(), 1L, "Stale one-shot menu cannot replay");
            h.assertTrue(!host.getUpdateTag(level.registryAccess()).contains("BiocomputingProductionPort"), "Public sync omits private identity/grant/queue");
            var reader = new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockReadContext(level, pos, Direction.WEST, actor,
                panelState, host, CBConfigs.SERVER.biocomputing.peripherals(true).snapshot(), true);
            h.assertValueEqual(reader.serializedData(), new DataValue.ErrorValue("access_denied", "access_denied"), "Raw reads cannot disclose grants or frames");
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "Status reply takes real wire time");
                var token = (DataValue.RecordValue)status.field("state");
                var material = new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(
                    new DataValue.RecordValue(Map.of("item", text("minecraft:stone"), "count", integer(4)))))));
                var args = Map.of("device", token.field("device"), "session", token.field("session"), "order", text("job-1"), "data", (DataValue)material);
                var job = command(address, "attempt-1", "submit", args);
                exchange(h, computer, job, accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Public request is durably admitted");
                    h.assertValueEqual(chest.getItem(0).getCount(), 60, "Device tick packages exactly four actual items");
                    h.assertValueEqual(com.simibubi.create.content.logistics.box.PackageItem.getAddress(packager.heldBox), "workshop", "Caller cannot choose a different address");
                    exchange(h, computer, job, duplicate -> {
                        h.assertValueEqual(duplicate.field("code"), text("existing"), "Duplicate wire request keeps one order");
                        h.assertValueEqual(chest.getItem(0).getCount(), 60, "No second extraction");
                        var saved = host.saveWithoutMetadata(level.registryAccess());
                        level.removeBlock(pos, false); level.setBlock(pos, panelState, 3);
                        var restored = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(pos);
                        restored.loadWithComponents(saved, level.registryAccess());
                        h.runAfterDelay(4, () -> exchange(h, computer, command(address, "poll-after-reload", "order", Map.of("order", text("job-1"))), reply -> {
                            h.assertValueEqual(reply.field("phase"), text("waiting"), "Real BE restore reopens journal, not an empty order book");
                            h.assertValueEqual(chest.getItem(0).getCount(), 60, "Reload never resends native effects");
                            exchange(h, computer, job, stale -> {
                                h.assertValueEqual(stale.field("code"), text("session_changed"), "Pre-reload session cannot submit new work");
                                var after = restored.getBehaviour(ProductionPortBehaviour.TYPE);
                                long grantRevision = after.revision();
                                var rotated = panelState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                                level.setBlock(pos, rotated, 3);
                                h.runAfterDelay(2, () -> {
                                    h.assertValueEqual(after.device().view().ports().get("s0").side().orElseThrow(), Direction.EAST,
                                        "Restored physical side follows orientation without changing the logical grant");
                                    h.assertValueEqual(after.revision(), grantRevision, "Rotation does not invalidate pending business authorization");
                                    h.assertValueEqual(after.configure(actor, after.revision(), Optional.empty(), ""), "configured", "Explicit revoke remains available");
                                    var latest = restored.saveWithoutMetadata(level.registryAccess()); after.unload();
                                    var identity = latest.getCompound("BiocomputingProductionPort").getUUID("Identity");
                                    var file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                                        .resolve("data/biocomputing/production").resolve(identity.toString()).resolve("production-orders.nbt");
                                    try {
                                        var original = java.nio.file.Files.readAllBytes(file);
                                        try {
                                            java.nio.file.Files.writeString(file, "invalid archive");
                                            after.read(latest, level.registryAccess(), false);
                                            var failed = after.status();
                                            h.assertValueEqual(failed.field("orders"), DataValue.NullValue.INSTANCE, "Unavailable count is unknown, never zero");
                                            h.assertValueEqual(failed.field("diagnostic"), text("storage_unavailable"), "Archive failure is visible");
                                        } finally { after.unload(); java.nio.file.Files.write(file, original); }
                                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                                    h.succeed();
                                });
                            });
                        }));
                    });
                });
            }));
        });
    }
    static DataValue.RecordValue command(DataValue.RecordValue address, String request, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("production_command"), "version", integer(1), "target", address,
            "request", text(request), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }
    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3); level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "redstone-computer")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Normal checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); return computer;
    }
    static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue request, Consumer<DataValue.RecordValue> finish) {
        h.assertValueEqual(computer.serialDevice().send("s0", request), "queued", "No instantaneous device shortcut");
        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "Reply consumes wire time");
        awaitReply(h, computer, request.field("request"), finish);
    }
    private static void awaitReply(GameTestHelper h, ComputerBehaviour computer, DataValue correlation, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { awaitReply(h, computer, correlation, finish); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), correlation, "Explicit matching reply"); finish.accept(reply.orElseThrow());
        });
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }

    private static final class MenuPlayer extends FakePlayer {
        ProductionPortMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "redstone-control")); }
        @Override public OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.PRODUCTION_PORT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(ProductionPortRequest packet) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new ProductionPortRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
