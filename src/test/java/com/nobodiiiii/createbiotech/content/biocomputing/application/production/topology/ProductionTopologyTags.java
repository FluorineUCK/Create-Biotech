package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfileCodecs;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;

/** Shared strict value tags for production snapshots and epochs, not an object serialization escape hatch. */
final class ProductionTopologyTags {
    private static final Set<String> BOUNDS = Set.of("MinX","MinY","MinZ","MaxX","MaxY","MaxZ");
    private ProductionTopologyTags() {}

    static void writeLayout(CompoundTag tag, ProductionLayout layout) {
        var b = layout.bounds(); var bounds = new CompoundTag();
        bounds.putInt("MinX",b.minX()); bounds.putInt("MinY",b.minY()); bounds.putInt("MinZ",b.minZ());
        bounds.putInt("MaxX",b.maxX()); bounds.putInt("MaxY",b.maxY()); bounds.putInt("MaxZ",b.maxZ()); tag.put("Bounds",bounds);
        var nodes = new ListTag();
        for (var member : layout.members()) {
            var child = new CompoundTag(); child.putUUID("ComputerId",member.computerId());
            child.put("Address",address(member.address()));
            if (member.profile()!=null) child.put("Profile",ProductionResourceProfileCodecs.save(member.profile()));
            nodes.add(child);
        }
        tag.put("Nodes",nodes); var panels = new ListTag();
        for (var panel : layout.panels()) {
            var child = new CompoundTag(); child.putUUID("PanelId",panel.panelId());
            child.put("Address",address(panel.address())); child.putString("Facing",panel.facing().getSerializedName());
            child.putString("BaseMaterial",panel.baseMaterial().getSerializedName()); panels.add(child);
        }
        tag.put("Panels",panels);
    }

    static ProductionLayout readLayout(CompoundTag tag) {
        require(has(tag,"Bounds",Tag.TAG_COMPOUND) && has(tag,"Nodes",Tag.TAG_LIST) && has(tag,"Panels",Tag.TAG_LIST));
        var b = tag.getCompound("Bounds");
        require(b.getAllKeys().equals(BOUNDS) && BOUNDS.stream().allMatch(key -> has(b,key,Tag.TAG_INT)));
        var bounds = new Bounds(b.getInt("MinX"),b.getInt("MinY"),b.getInt("MinZ"),b.getInt("MaxX"),b.getInt("MaxY"),b.getInt("MaxZ"));
        var rawNodes = (ListTag)tag.get("Nodes"); var rawPanels = (ListTag)tag.get("Panels");
        require(!rawNodes.isEmpty() && rawNodes.size()<=MAX_MEMBERS && rawNodes.getElementType()==Tag.TAG_COMPOUND
            && rawPanels.size()<=MAX_VOLUME && (rawPanels.isEmpty() || rawPanels.getElementType()==Tag.TAG_COMPOUND));
        var members = new ArrayList<Member>();
        for (var raw : rawNodes) {
            var child = (CompoundTag)raw; boolean profile = child.contains("Profile");
            require(child.getAllKeys().equals(profile ? Set.of("ComputerId","Address","Profile") : Set.of("ComputerId","Address"))
                && child.hasUUID("ComputerId") && has(child,"Address",Tag.TAG_COMPOUND)
                && (!profile || has(child,"Profile",Tag.TAG_COMPOUND)));
            members.add(new Member(child.getUUID("ComputerId"),readAddress(child.getCompound("Address")),
                profile ? ProductionResourceProfileCodecs.load(child.getCompound("Profile")).orElseThrow() : null));
        }
        var panels = new ArrayList<Panel>();
        for (var raw : rawPanels) {
            var child = (CompoundTag)raw;
            require(child.getAllKeys().equals(Set.of("PanelId","Address","Facing","BaseMaterial")) && child.hasUUID("PanelId")
                && has(child,"Address",Tag.TAG_COMPOUND) && has(child,"Facing",Tag.TAG_STRING) && has(child,"BaseMaterial",Tag.TAG_STRING));
            var facing = Direction.byName(child.getString("Facing")); require(facing!=null);
            var material = Arrays.stream(ComputerShellMaterial.values())
                .filter(value -> value.getSerializedName().equals(child.getString("BaseMaterial"))).findFirst().orElseThrow();
            panels.add(new Panel(child.getUUID("PanelId"),readAddress(child.getCompound("Address")),facing,material));
        }
        return new ProductionLayout(bounds,members,panels);
    }

    static CompoundTag address(Address value) {
        var tag = new CompoundTag(); tag.putString("Dimension",value.dimension().location().toString());
        if (value.subLevelId()!=null) tag.putUUID("SubLevel",value.subLevelId());
        tag.putLong("Pos",value.localPos().asLong()); return tag;
    }
    static Address readAddress(CompoundTag tag) {
        require(tag.getAllKeys().equals(tag.contains("SubLevel") ? Set.of("Dimension","Pos","SubLevel") : Set.of("Dimension","Pos"))
            && has(tag,"Dimension",Tag.TAG_STRING) && !tag.getString("Dimension").isBlank() && has(tag,"Pos",Tag.TAG_LONG)
            && (!tag.contains("SubLevel") || tag.hasUUID("SubLevel")));
        var id = ResourceLocation.tryParse(tag.getString("Dimension")); require(id!=null);
        return new Address(ResourceKey.create(Registries.DIMENSION,id),tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null,
            BlockPos.of(tag.getLong("Pos")));
    }
    static boolean has(CompoundTag tag, String key, int type) {
        var value = tag.get(key); return value!=null && value.getId()==type;
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("invalid production topology tag"); }
}
