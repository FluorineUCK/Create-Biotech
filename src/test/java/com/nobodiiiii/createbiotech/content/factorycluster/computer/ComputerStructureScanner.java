package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Temporary legacy world/config adapter. The production application owns the only scanning algorithm. */
public final class ComputerStructureScanner {
    private ComputerStructureScanner() {}
    public interface View {
        boolean isLoaded(BlockPos pos);
        boolean sameSpace(BlockPos origin, BlockPos pos);
        CellKind cellAt(BlockPos pos);
        @Nullable ComputerObservation computerAt(BlockPos pos);
        @Nullable PanelObservation panelAt(BlockPos pos);
    }
    public enum CellKind { AIR, CASING, COMPUTER, ALLOWED_INTERNAL, ILLEGAL }
    public enum ProfileLoadState { EMPTY, VALID, CORRUPT }
    public record ComputerObservation(@Nullable UUID computerId, @Nullable UUID computerStructureMemberId,
        ProfileLoadState profileState, @Nullable ProductionResourceProfile profile, @Nullable SpaceAddress address) {}
    public record PanelObservation(@Nullable UUID panelId, @Nullable SpaceAddress address,
        @Nullable Direction facing, @Nullable ComputerShellMaterial baseMaterial) {}
    public enum State {
        UNFORMED, VALID, VALID_NOT_READY, PARTIAL_UNLOADED, SIZE, VOLUME,
        OPEN_SHELL, ILLEGAL_INTERNAL, DISCONNECTED_BUS, NODE_LIMIT, SPACE,
        IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS
    }
    public record Limits(int minSize, int maxSize, int maxVolume, int maxNodes) {
        public Limits { new ProductionStructureScanner.Limits(minSize, maxSize, maxVolume, maxNodes); }
        public static Limits fromConfig() {
            return fromValues(3, 7, 32);
        }
        static Limits fromValues(int min, int max, int nodes) {
            var value = ProductionStructureScanner.Limits.fromValues(min, max, nodes);
            return new Limits(value.minSize(), value.maxSize(), value.maxVolume(), value.maxNodes());
        }
        int observationWindowVolume() { return productionValue().observationWindowVolume(); }
        ProductionStructureScanner.Limits productionValue() {
            return new ProductionStructureScanner.Limits(minSize, maxSize, maxVolume, maxNodes);
        }
    }
    public record ScanResult(State state, @Nullable ProductionStructure structure,
        @Nullable UUID observedStructureMemberId) {
        public ScanResult { Objects.requireNonNull(state, "state"); }
    }
    public static ScanResult scan(View view, BlockPos seed, Limits limits) {
        Objects.requireNonNull(view, "view"); Objects.requireNonNull(limits, "limits");
        var result = ProductionStructureScanner.scan(new ProductionStructureScanner.View() {
            public boolean isLoaded(BlockPos pos) { return view.isLoaded(pos); }
            public boolean sameSpace(BlockPos origin, BlockPos pos) { return view.sameSpace(origin, pos); }
            public ProductionStructureScanner.CellKind cellAt(BlockPos pos) {
                return ProductionStructureScanner.CellKind.valueOf(view.cellAt(pos).name());
            }
            public ProductionStructureScanner.ComputerObservation computerAt(BlockPos pos) {
                var value = view.computerAt(pos);
                return value == null ? null : new ProductionStructureScanner.ComputerObservation(value.computerId(),
                    value.computerStructureMemberId(), value.profileState() == null ? null
                        : ProductionStructureScanner.ProfileLoadState.valueOf(value.profileState().name()),
                    value.profile(), address(value.address()));
            }
            public ProductionStructureScanner.PanelObservation panelAt(BlockPos pos) {
                var value = view.panelAt(pos);
                return value == null ? null : new ProductionStructureScanner.PanelObservation(value.panelId(),
                    address(value.address()), value.facing(), value.baseMaterial());
            }
        }, seed, limits.productionValue());
        return new ScanResult(State.valueOf(result.state().name()), result.structure() == null ? null
            : result.structure(), result.observedStructureMemberId());
    }
    private static @Nullable ProductionLayout.Address address(@Nullable SpaceAddress address) {
        return address == null ? null : new ProductionLayout.Address(address.dimension(), address.subLevelId(), address.localPos());
    }
}
