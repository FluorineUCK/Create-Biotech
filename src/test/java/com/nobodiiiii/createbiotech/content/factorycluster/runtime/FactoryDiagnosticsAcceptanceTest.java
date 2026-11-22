package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsNetworkAliasService;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.nobodiiiii.createbiotech.content.factorycluster.*;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.*;

/** Executable failure-matrix checks. Browser cases are blocked by the missing prerequisite,
 * not represented by skipped or vacuous tests; see task-5-report.md. */
class FactoryDiagnosticsAcceptanceTest {
    private static UUID id(long n) { return new UUID(0, n); }
    private static CommunicationFrequency frequency(String value) { return CommunicationFrequency.of(value); }

    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void physicalResidentProjectionDoesNotDependOnTemporarilyMismatchedProductionProfile() throws Exception {
        var constructor = com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.class.getDeclaredConstructor(net.minecraft.world.level.block.entity.BlockEntityType.class, BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
        constructor.setAccessible(true);
        var computer = constructor.newInstance(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE,
            BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());
        ItemStack resident = new ItemStack(Items.PAPER);
        var captured = new net.minecraft.nbt.CompoundTag();
        captured.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(net.minecraft.world.entity.EntityType.VILLAGER).toString()); captured.putUUID("UUID", id(101));
        var data = new net.minecraft.nbt.CompoundTag(); data.putString("profession", net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN).toString()); data.putInt("level", 1);
        captured.put("VillagerData", data);
        var root = new net.minecraft.nbt.CompoundTag(); root.put("CapturedEntity", captured);
        com.nobodiiiii.createbiotech.foundation.item.CBItemData.set(resident, root);
        assertTrue(ResidentProjection.fromCapturedResident(resident, net.minecraft.core.RegistryAccess.EMPTY).isPresent(), "fixture must contain a valid resident projection before profile corruption");
        var install = com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.class.getDeclaredMethod("installResidentSnapshot", ItemStack.class, ProductionResourceProfile.class);
        install.setAccessible(true);
        install.invoke(computer, resident, ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 5, 0).orElseThrow());
        var write = com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.class.getDeclaredMethod("write", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class, boolean.class);
        write.setAccessible(true);
        var durableBefore = new net.minecraft.nbt.CompoundTag();
        write.invoke(computer, durableBefore, net.minecraft.core.RegistryAccess.EMPTY, false);
        var packet = new net.minecraft.nbt.CompoundTag();
        assertDoesNotThrow(() -> write.invoke(computer, packet, net.minecraft.core.RegistryAccess.EMPTY, true),
            "read-only diagnostics must not crash a server during loaded profile replacement");
        var client = ComputerResidentView.load(packet.getCompound("ComputerClientState")).orElseThrow();
        assertTrue(client.renderResident(), "A physical resident must not disappear because its production Profile differs");
        assertEquals(1, client.resident().level(), "Display the actual resident, not the production profile level");
        assertEquals(net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, client.resident().profession());
        var durableAfter = new net.minecraft.nbt.CompoundTag();
        write.invoke(computer, durableAfter, net.minecraft.core.RegistryAccess.EMPTY, false);
        assertEquals(durableBefore, durableAfter, "diagnostics must not repair frozen Profile or resident data");
        assertFalse(FactoryGoggleState.load(packet.getCompound(FactoryGoggleState.ROOT)).orElseThrow().rows().isEmpty());
    }

    @Test void unformedClusterCannotDeclareOrPublishAgentService() {
        var directory = new ClusterCommunicationDirectory();
        var command = new ClusterCommunicationCommandService.CommandAuthority(id(1), id(2),
            new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, id(3)), id(4));
        var service = new ClusterCommunicationCommandService(directory,
            request -> ProductionCommunicationAuthorityPolicy.validate(request,
                new ProductionCommunicationAuthorityPolicy.Evidence(Optional.empty(), List.of(), List.of())),
            ClusterCommunicationCommandService.RoleTransitionPolicy.permissive());
        assertEquals(ClusterCommunicationCommandService.ChangeResult.UNFORMED,
            service.changeRole(command, ClusterServiceRole.AGENT, 0));
        assertTrue(directory.configuration(id(1)).isEmpty());
    }

    @Test void blankAndLiteralMismatchedFrequenciesCannotRouteAcrossClusters() {
        for (String other : List.of("", "north", "*")) {
            var source = endpoint(1, null, "North"); var target = endpoint(2, null, other);
            var policy = new RadioLinkPolicy(64, 128);
            assertFalse(policy.edge(source, target, frame(true, source, target)).reachable(), other);
            assertTrue(policy.edge(source, endpoint(2, null, "North"),
                frame(true, source, endpoint(2, null, "North"))).reachable());
        }
    }

    @Test void disabledDynamicTopologyCannotReuseCrossSpacePose() {
        var source = endpoint(1, null, "North"); var target = endpoint(2, id(80), "North");
        var policy = new RadioLinkPolicy(64, 128);
        assertTrue(policy.edge(source, target, frame(true, source, target)).reachable());
        assertFalse(policy.edge(source, target, frame(false, source, target)).reachable());
    }

    @Test void endpointUnloadInvalidatesDiagnosticsBeforeNextTwentyTickFrame() {
        var lifecycle = new RadioTopologyLifecycle(true);
        var source = endpoint(1, null, "North"); var target = endpoint(2, null, "North");
        var pose = new RadioTopologyTracker.Pose(Optional.empty(), Vec3.ZERO);
        lifecycle.observe(source, pose, 1); lifecycle.observe(target, pose, 1);
        lifecycle.refresh(20, (address, local) -> new RadioTopologyTracker.ResolvedPose(Vec3.ZERO, Optional.empty()));
        assertTrue(new RadioLinkPolicy(64, 128).edge(source, target, lifecycle.frame()).reachable());
        lifecycle.unload(target.memberId(), target.address(), 21);
        assertFalse(new RadioLinkPolicy(64, 128).edge(source, target, lifecycle.frame()).reachable());
        var projection = FactoryGoggleCapture.radio(source, List.of(source, target), lifecycle.frame(), new RadioLinkPolicy(64, 128), 21).orElseThrow();
        assertEquals("UNAVAILABLE", projection.linkType());
    }

    @Test void staleAliasAndAgentDeclarationDoNotReplaceCurrentState() throws Exception {
        var directory = new LogisticsNetworkDirectory(); directory.ensure(id(1));
        var constructor = LogisticsNetworkAliasService.class.getDeclaredConstructor(LogisticsNetworkDirectory.class, java.util.function.BiPredicate.class);
        constructor.setAccessible(true);
        var aliases = constructor.newInstance(directory, (java.util.function.BiPredicate<java.util.UUID, net.minecraft.world.entity.player.Player>) (network, player) -> true);
        assertEquals(LogisticsNetworkAliasService.RenameResult.APPLIED, aliases.rename(null, id(1), 0, Optional.of("Current")));
        assertEquals(LogisticsNetworkAliasService.RenameResult.STALE, aliases.rename(null, id(1), 0, Optional.of("Stale")));
        assertEquals(Optional.of("Current"), directory.find(id(1)).orElseThrow().customAlias());
        var communications = new ClusterCommunicationDirectory();
        var commands = new ClusterCommunicationCommandService(communications, ignored -> ClusterCommunicationCommandService.AuthorityStatus.READY,
            ClusterCommunicationCommandService.RoleTransitionPolicy.permissive());
        var authority = new ClusterCommunicationCommandService.CommandAuthority(id(1), id(2), new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, id(3)), id(4));
        assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED, commands.changePrimary(authority, frequency("Current"), 0));
        assertEquals(ClusterCommunicationCommandService.ChangeResult.STALE, commands.changeRole(authority, ClusterServiceRole.AGENT, 0));
        assertNotEquals(ClusterServiceRole.AGENT, communications.configuration(id(1)).orElseThrow().role());
    }

    @Test void duplicateLiveAgentClaimsExposeConflictRatherThanSelectingOne() {
        var directory = new AgentAddressDirectory(8, 8);
        directory.publish(claim(10, AgentAddressRegistration.LoadState.LOADED));
        directory.publish(claim(11, AgentAddressRegistration.LoadState.LOADED));
        var conflict = assertInstanceOf(AgentResolution.Conflict.class, directory.resolve(route(), id(4), frequency("North")));
        assertEquals(2, conflict.registrations().size());
        var rows = new FactoryAdvancedGoggleCapture.Rows(8);
        FactoryAdvancedGoggleCapture.agentResolution(rows, conflict);
        assertTrue(rows.agents.getFirst().contains("Conflict"));
        assertEquals(3, rows.agents.size());
    }

    @Test void knownOfflineAgentNeverBecomesBareFallback() {
        var directory = new AgentAddressDirectory(8, 8);
        directory.publish(claim(10, AgentAddressRegistration.LoadState.UNLOADED));
        assertInstanceOf(AgentResolution.KnownUnavailable.class, directory.resolve(route(), id(4), frequency("North")));
        assertInstanceOf(AgentResolution.NeverRegistered.class, directory.resolve(new CreateRouteKey(id(5), "unknown"), id(4), frequency("North")));
    }

    @Test void exhaustedListenersRetainConfigurationAndRejectStaleEdit() {
        class Computer implements ExternalListenerService.ListenerComputer {
            ExternalListenerConfiguration state = ExternalListenerConfiguration.empty();
            public UUID id() { return FactoryDiagnosticsAcceptanceTest.id(1); }
            public ProductionResourceProfile profile() { return ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 0).orElseThrow(); }
            public ExternalListenerConfiguration configuration() { return state; }
            public boolean listenerPersistenceAuthorized() { return true; }
            public boolean compareAndSet(ExternalListenerConfiguration expected, ExternalListenerConfiguration next) {
                if (!state.equals(expected)) return false; state = next; return true;
            }
        }
        var computer = new Computer();
        var scheduler = new ExternalListenerService.Scheduler() {
            public Optional<ExternalListenerService.Reservation> reserve(UUID computerId, CommunicationFrequency frequency, int limit) { return Optional.empty(); }
            public void release(ExternalListenerService.Reservation reservation) { throw new AssertionError("no reservation exists"); }
            public int occupancy(UUID computerId, ProcessKind kind) { return 2; }
        };
        var service = new ExternalListenerService(scheduler, new RadioRouter(8, 32, 8),
            (host, frequency, reservation) -> { throw new AssertionError("no capacity must not publish route"); }, (host, frequency, authority) -> true);
        assertEquals(ExternalListenerService.EditResult.APPLIED, service.replace(computer, 0, List.of(frequency("North"))));
        assertEquals(WaitReason.PROCESS, service.projection(computer.id()).getFirst().waitReason().orElseThrow());
        assertEquals(ExternalListenerService.EditResult.STALE, service.replace(computer, 0, List.of(frequency("Stale"))));
        assertEquals(List.of(frequency("North")), computer.configuration().frequencies());
        assertTrue(service.publications(computer.id()).isEmpty());
    }

    @Test void uncertainIrreversibleDispatchIsRetainedButNeverResent() {
        var intent = new ProductionEffect.CreateDispatch(id(10), id(1), 2,
            new ProductionEffect.TargetFence(id(2), id(3), 1, 1), id(4), route(),
            List.of(new OrderedIngredient(new StackKey(new ItemStack(Items.STONE)), 1)));
        var state = new ClusterEngineState(id(1), id(2), 2, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
            Map.of(intent.intentId(), intent), Set.of(intent.intentId()), 0, null);
        var config = new FactoryRuntimeConfig(1, 500, 4, 4, 4, 4, 64, 128, 20, 10, 100, 3, 32, 16);
        try (var runtime = new FactoryRuntimeService(config)) {
            runtime.start(); runtime.restoreSnapshot(state);
            assertTrue(runtime.drainIntents(id(1), 21).isEmpty());
            assertTrue(runtime.snapshot(id(1)).orElseThrow().uncertainIntentIds().contains(id(10)));
            assertEquals(intent, runtime.snapshot(id(1)).orElseThrow().pendingIntents().get(id(10)));
        }
    }


    private static CreateRouteKey route() { return new CreateRouteKey(id(5), "mixer"); }
    private static AgentAddressRegistration claim(long agent, AgentAddressRegistration.LoadState loaded) {
        return new AgentAddressRegistration(route(), id(agent), id(3), 1, frequency("North"), loaded, AgentAddressRegistration.Reachability.REACHABLE);
    }
    private static RadioEndpoint endpoint(long n, UUID sublevel, String frequency) {
        return new RadioEndpoint(id(n), id(n + 10), new SpaceAddress(Level.OVERWORLD, sublevel, new BlockPos((int)n, 64, 0)), 1,
            frequency(frequency), 1, Set.of());
    }
    private static RadioTopologyFrame frame(boolean dynamic, RadioEndpoint a, RadioEndpoint b) {
        var samples = new LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample>();
        for (var endpoint : List.of(a, b)) samples.put(endpoint.key(), new RadioTopologyFrame.Sample(Level.OVERWORLD,
            endpoint.address(), Vec3.ZERO, true, 1, Optional.ofNullable(endpoint.address().subLevelId())));
        return new RadioTopologyFrame(20, dynamic, samples);
    }
}
