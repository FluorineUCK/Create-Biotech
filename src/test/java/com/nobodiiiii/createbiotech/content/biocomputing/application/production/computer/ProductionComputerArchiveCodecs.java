package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerArchive;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import net.minecraft.nbt.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerArchive.compoundCodec;

/** Temporary child-codec assembly only; the new application owns the envelope and recovery. */
public final class ProductionComputerArchiveCodecs {
    private ProductionComputerArchiveCodecs() {}
    public static ProductionComputerArchive<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
        ExternalListenerConfiguration, Set<EpochFault>> create() {
        return new ProductionComputerArchive<>(
            compoundCodec(ProductionStructureCodec::loadRevision, ProductionStructureCodec::saveRevision),
            compoundCodec(tag -> ClusterBinding.tryLoad(tag).filter(value -> value.save().equals(tag)), ClusterBinding::save),
            compoundCodec(ProductionEpochCodec::load, ProductionEpochCodec::save),
            compoundCodec(ExternalListenerConfiguration::load, ExternalListenerConfiguration::save),
            ExternalListenerConfiguration.empty(), new ProductionComputerArchive.Codec<Set<EpochFault>>() {
                @Override public Optional<Set<EpochFault>> load(Tag raw) {
                    if (!(raw instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_STRING)
                        return Optional.empty();
                    var result = EnumSet.noneOf(EpochFault.class);
                    try {
                        for (Tag value : list)
                            if (!(value instanceof StringTag name) || !result.add(EpochFault.valueOf(name.getAsString())))
                                return Optional.empty();
                        return Optional.of(Set.copyOf(result));
                    } catch (IllegalArgumentException invalid) { return Optional.empty(); }
                }
                @Override public Tag save(Set<EpochFault> values) {
                    var list = new ListTag(); values.stream().sorted().map(Enum::name).map(StringTag::valueOf).forEach(list::add);
                    return list;
                }
            }, Set.of());
    }
}
