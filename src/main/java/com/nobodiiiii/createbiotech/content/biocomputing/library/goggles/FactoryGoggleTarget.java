package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
/** Diagnostic fence only: incarnation is never persisted or used as gameplay identity. */
public record FactoryGoggleTarget(SpaceAddress address, UUID memberId, long generation, UUID incarnation) {
 public static final String ROOT="AdvancedGoggleTarget";
 public FactoryGoggleTarget { Objects.requireNonNull(address);Objects.requireNonNull(memberId);Objects.requireNonNull(incarnation);if(generation<0)throw new IllegalArgumentException("negative generation"); }
 public void write(RegistryFriendlyByteBuf b) { b.writeResourceLocation(address.dimension().location());b.writeBoolean(address.subLevelId()!=null);if(address.subLevelId()!=null)b.writeUUID(address.subLevelId());b.writeBlockPos(address.localPos());b.writeUUID(memberId);b.writeVarLong(generation);b.writeUUID(incarnation); }
 public static FactoryGoggleTarget read(RegistryFriendlyByteBuf b) { var dim=ResourceKey.create(Registries.DIMENSION,b.readResourceLocation());var sub=b.readBoolean()?b.readUUID():null; return new FactoryGoggleTarget(new SpaceAddress(dim,sub,b.readBlockPos()),b.readUUID(),b.readVarLong(),b.readUUID()); }
 public CompoundTag save() { var t=new CompoundTag();t.put("Address",address.save());t.putUUID("Member",memberId);t.putLong("Generation",generation);t.putUUID("Incarnation",incarnation);return t; }
 public static Optional<FactoryGoggleTarget> load(CompoundTag t) {
  if(!t.getAllKeys().equals(Set.of("Address","Member","Generation","Incarnation"))||!t.hasUUID("Member")||!t.hasUUID("Incarnation")||!t.contains("Generation",4))return Optional.empty();
  try { return SpaceAddress.tryLoad(t.getCompound("Address")).map(a->new FactoryGoggleTarget(a,t.getUUID("Member"),t.getLong("Generation"),t.getUUID("Incarnation"))); } catch(IllegalArgumentException e) { return Optional.empty(); }
 }
}
