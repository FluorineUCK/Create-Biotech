package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
import java.util.function.Supplier;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
/** One token per player (not per target); capture is lazy and strictly after authority checks. */
public final class FactoryGoggleProjectionService {
 public enum Result { APPLIED,NO_GOGGLES,NOT_SNEAKING,OUT_OF_RANGE,UNLOADED,WRONG_TARGET,STALE_TARGET,RATE_LIMITED }
 public record Context(boolean goggles,boolean sneaking,boolean inRange,boolean loaded,SpaceAddress pointed,FactoryGoggleTarget actual) {}
 public record Reply(Result result,Optional<FactoryAdvancedGoggleProjection> projection) {}
 private final Map<Object,Long> lastRequests=new WeakHashMap<>();
 public Reply request(Object player,Context c,FactoryGoggleTarget target,long tick,int interval,Supplier<FactoryAdvancedGoggleProjection> capture) {
  if(interval<1||interval>100||tick<0)throw new IllegalArgumentException("invalid request clock");
  Result result=!c.goggles()?Result.NO_GOGGLES:!c.sneaking()?Result.NOT_SNEAKING:!c.inRange()?Result.OUT_OF_RANGE:!c.loaded()?Result.UNLOADED:!target.address().equals(c.pointed())?Result.WRONG_TARGET:!target.equals(c.actual())?Result.STALE_TARGET:null;
  if(result!=null)return new Reply(result,Optional.empty());
  Long last=lastRequests.get(player);if(last!=null&&(tick<last||tick-last<interval))return new Reply(Result.RATE_LIMITED,Optional.empty());
  lastRequests.put(player,tick);var projection=Objects.requireNonNull(capture.get());if(!projection.target().equals(target))throw new IllegalArgumentException("capture changed target");
  return new Reply(Result.APPLIED,Optional.of(projection));
 }
}
