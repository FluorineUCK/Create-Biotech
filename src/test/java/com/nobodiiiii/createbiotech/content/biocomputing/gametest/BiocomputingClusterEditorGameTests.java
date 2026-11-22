package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.PeripheralCaptureService;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real server request/menu path; FakePlayer's no-op screen transport alone is adapted below. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterEditorGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void physicalMemberCanBeInspectedBeforeHardwareIsReady(GameTestHelper helper) throws Exception {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, false); var level = helper.getLevel();
        var target = f.config().members().get("A").position().south();
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(
            com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(target, lower);
        level.setBlockAndUpdate(target.above(), lower.setValue(
            com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        var player = new MenuPlayer(level); player.moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        openController(f.controller(), player);
        // Observe the actual server-built view, not a replacement candidate factory.
        var method = ClusterControllerMenu.class.getDeclaredMethod("publicView", net.minecraft.server.level.ServerPlayer.class);
        method.setAccessible(true);
        var view = (ClusterControlView)method.invoke(player.containerMenu, player);
        var candidate = view.candidates().stream().filter(c -> c.position().equals(target)).findFirst().orElseThrow();
        helper.assertTrue(candidate.editable() && !candidate.selectable(), "Physical editor access must not require ready hardware or communication");
        new ClusterControlRequest(player.containerMenu.containerId, player.nonce, 1, f.controller().configurationRevision(), ClusterControlSession.Action.OPEN_COMPUTER,
            ClusterControlWire.encodeComputerPosition(target, CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive())).handle(player);
        helper.assertTrue(player.containerMenu instanceof ComputerProgramMenu && player.containerMenu.stillValid(player), "Unready member opens its diagnostic editor");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void editorRejectsForeignTargetsAndRevokesOnDistanceAndDeviceReplacement(GameTestHelper helper) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, false); var level = helper.getLevel();
        var player = new MenuPlayer(level); var anchor = Vec3.atCenterOf(f.controller().getBlockPos().west()); player.moveTo(anchor);
        var target = f.config().members().get("A").position();
        openController(f.controller(), player); var controlMenu = player.containerMenu; var controlNonce = player.nonce;
        var archive = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive();
        new ClusterControlRequest(controlMenu.containerId, controlNonce, 1, f.controller().configurationRevision(), ClusterControlSession.Action.OPEN_COMPUTER,
            ClusterControlWire.encodeComputerPosition(f.min().west(), archive)).handle(player);
        helper.assertTrue(player.containerMenu == controlMenu, "Client cannot open a target outside geometric membership");
        new ClusterControlRequest(controlMenu.containerId, controlNonce, 2, f.controller().configurationRevision() + 1, ClusterControlSession.Action.OPEN_COMPUTER,
            ClusterControlWire.encodeComputerPosition(target, archive)).handle(player);
        helper.assertTrue(player.containerMenu == controlMenu, "Stale configuration cannot open a member");
        new ClusterControlRequest(controlMenu.containerId, controlNonce, 3, f.controller().configurationRevision(), ClusterControlSession.Action.OPEN_COMPUTER,
            ClusterControlWire.encodeComputerPosition(target, archive)).handle(player);
        helper.assertTrue(player.containerMenu instanceof ComputerProgramMenu, "Current valid request opens target");
        var editor = player.containerMenu;
        player.moveTo(anchor.add(-20, 0, 0)); helper.assertTrue(!editor.stillValid(player), "Walking away from controller revokes access");
        player.moveTo(anchor);
        var original = level.getBlockEntity(target); var saved = original.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(target);
        var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(target, level.getBlockState(target), saved, level.registryAccess());
        level.setBlockEntity(Objects.requireNonNull(restored));
        helper.assertTrue(!editor.stillValid(player), "Replacement at the same position cannot inherit an open editor");
        openController(f.controller(), player);
        new ClusterControlRequest(player.containerMenu.containerId, player.nonce, 1, f.controller().configurationRevision(), ClusterControlSession.Action.OPEN_COMPUTER,
            ClusterControlWire.encodeComputerPosition(target, archive)).handle(player);
        helper.assertTrue(player.containerMenu instanceof ComputerProgramMenu && player.containerMenu.stillValid(player), "A new explicit session can inspect the restored member");
        var controllerPos = f.controller().getBlockPos(); var controllerSave = f.controller().saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(controllerPos);
        level.setBlockEntity(Objects.requireNonNull(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(controllerPos,
            level.getBlockState(controllerPos), controllerSave, level.registryAccess())));
        helper.assertTrue(!player.containerMenu.stillValid(player), "Controller replacement revokes the editor even with preserved UUID and position");
        helper.succeed();
    }
    private static void openController(com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity controller, MenuPlayer player) {
        var provider = ClusterControllerMenu.provider(controller); player.openMenu(provider, provider::writeOpeningData);
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void controllerOpensUnprogrammedMemberAndRevokesEditorWhenShellBreaks(GameTestHelper helper) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, false);
        var pos = f.config().members().get("A").position().south();
        var owner = BiocomputingWorldGameTests.computer(helper, pos); var computer = BiocomputingWorldGameTests.host(owner);
        helper.getLevel().setBlockAndUpdate(pos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState());
        helper.assertTrue(computer.document().isEmpty(), "Fresh target has no program and no communication identity");
        var player = new MenuPlayer(helper.getLevel()); player.moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west(7)));
        helper.assertTrue(player.distanceToSqr(Vec3.atCenterOf(pos)) > 64, "Editor must be anchored to controller, not nearby target");
        var config = f.controller().routingState().orElseThrow().configuration(); var a = f.a().flowSnapshot();
        var provider = ClusterControllerMenu.provider(f.controller()); player.openMenu(provider, provider::writeOpeningData);
        UUID nonce = player.nonce;
        var archive = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive();
        new ClusterControlRequest(player.containerMenu.containerId, nonce, 1, f.controller().configurationRevision(),
            ClusterControlSession.Action.OPEN_COMPUTER, ProgramEditorWire.encodeValue(position(pos), archive)).handle(player);
        helper.assertTrue(player.containerMenu instanceof ComputerProgramMenu, "Controller opens the actual program menu");
        helper.assertTrue(player.containerMenu.stillValid(player), "Controller authorizes a distant touching Computer");
        helper.assertTrue(computer.document().isEmpty(), "Opening does not install a program");
        helper.assertValueEqual(f.controller().routingState().orElseThrow().configuration(), config, "Opening does not bind communication");
        helper.assertValueEqual(f.a().flowSnapshot(), a, "Other member state is untouched");
        var empty = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of());
        var document = new ProgramDocument(empty.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.empty()));
        new ProgramEditorRequest(player.containerMenu.containerId, player.nonce, 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Public install uses the controller-authorized session");
        var endpoint = new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("edited", true);
        new ProgramControlRequest(player.containerMenu.containerId, player.nonce, 1, computer.programRevision(),
            ComputerControlSession.Action.CONFIGURE_ENDPOINT, ProgramEditorWire.encodeValue(endpoint.value(), computer.hardware().limits().archive())).handle(player);
        helper.assertTrue(computer.advertisements().stream().anyMatch(e -> e.enabled() && e.name().equals("edited")),
            "Distant controller-authorized editor must also authorize explicit endpoint configuration");
        var access = new ClusterMemberEditorAccess(f.controller(), owner);
        helper.assertTrue(PeerDiscovery.discover(access, player).fields().containsKey("peers"), "Controller anchor supports peer discovery");
        // Earlier assertions proved opening leaves A intact. Repurpose that interior slot as a real
        // reader fixture now: the closed shell remains unchanged and the target is its north wall.
        helper.getLevel().setBlockAndUpdate(pos.north(), CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(
            com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlock.FACING, net.minecraft.core.Direction.NORTH));
        var captured = PeripheralCaptureService.capture(access, player);
        helper.assertTrue(((DataValue.RecordValue)captured.field("readers")).fields().size() == 1, "Controller anchor captures the physically connected reader");
        helper.assertTrue(rejected(() -> PeerDiscovery.discover(owner, player)), "Direct discovery still enforces Computer reach");
        helper.assertTrue(rejected(() -> PeripheralCaptureService.capture(owner, player)), "Direct capture still enforces Computer reach");
        long revision = computer.programRevision();
        helper.getLevel().setBlockAndUpdate(f.min(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(!player.containerMenu.stillValid(player), "Broken shell revokes the open editor immediately");
        helper.assertTrue(rejected(() -> PeerDiscovery.discover(access, player)), "Broken shell revokes anchored discovery");
        helper.assertTrue(rejected(() -> PeripheralCaptureService.capture(access, player)), "Broken shell revokes anchored capture");
        new ProgramControlRequest(player.containerMenu.containerId, player.nonce, 2, revision,
            ComputerControlSession.Action.DISABLE_ENDPOINT, new byte[0]).handle(player);
        helper.assertTrue(computer.advertisements().stream().anyMatch(e -> e.enabled() && e.name().equals("edited")), "Revoked control cannot disable endpoint");
        new ProgramEditorRequest(player.containerMenu.containerId, player.nonce, 2, revision, true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.programRevision(), revision, "Revoked session cannot reinstall or reset"); helper.succeed();
    }
    private static boolean rejected(Runnable operation) {
        try { operation.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private static DataValue.RecordValue position(BlockPos pos) {
        return new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(pos.getX()),
            "y", new DataValue.IntegerValue(pos.getY()), "z", new DataValue.IntegerValue(pos.getZ())));
    }
    private static final class MenuPlayer extends FakePlayer {
        private UUID nonce;
        private int nextMenu;
        private MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "cluster-editor")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> openingData) {
            containerMenu = provider.createMenu(++nextMenu, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { openingData.accept(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
            return OptionalInt.of(nextMenu);
        }
    }
}
