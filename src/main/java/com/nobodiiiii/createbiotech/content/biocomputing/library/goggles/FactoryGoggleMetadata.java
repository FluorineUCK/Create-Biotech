package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
/** Per-BE volatile diagnostic metadata. Never serialized to disk or published to gameplay registries. */
public final class FactoryGoggleMetadata {
 private UUID incarnation=UUID.randomUUID();private FactoryGoggleTarget client,published;
 public Optional<FactoryGoggleTarget> clientTarget() { return Optional.ofNullable(client); }
 public void read(CompoundTag tag) { client=FactoryGoggleTarget.load(tag.getCompound(FactoryGoggleTarget.ROOT)).orElse(null); }
 public void reset() { incarnation=UUID.randomUUID();client=null;published=null; }
 public void write(BlockEntity be,CompoundTag tag) { var t=capture(be);published=t;if(t!=null)tag.put(FactoryGoggleTarget.ROOT,t.save()); }
 public boolean changed(BlockEntity be) {return !Objects.equals(published,capture(be));}
 public FactoryGoggleTarget capture(BlockEntity be) {
  com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryGoggleReadout.requireServerThread(be);if(be.getLevel()==null)return null;
  if (!(be instanceof LibraryBlockEntity core) || core.memberId()==null) return null;
  return new FactoryGoggleTarget(SpaceAddress.capture(be.getLevel(),be.getBlockPos()),
      core.memberId(),core.diagnosticGeneration(),incarnation);
 }
}
