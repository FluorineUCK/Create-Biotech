package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
/** Single target cache, client clock TTL; stale replies never replace another incarnation. */
public final class FactoryGoggleClientCache {
 private final Map<Long,Long> requests=new LinkedHashMap<>();private long requestId;
 private FactoryGoggleTarget target; private FactoryAdvancedGoggleProjection value;private long expires,lastRequest=Long.MIN_VALUE;
 public boolean observe(FactoryGoggleTarget next,boolean goggles,boolean sneaking,long now,int interval) {
  if(!goggles||!sneaking)next=null;
  synchronizeTarget(next);
  value(now);if(target==null)return false;
  if(lastRequest!=Long.MIN_VALUE&&now>=lastRequest&&now-lastRequest<interval)return false;
  lastRequest=now;requestId=Math.incrementExact(requestId);requests.put(requestId,now);while(requests.size()>2)requests.remove(requests.keySet().iterator().next());return true;
 }

 /** Invalidate observed identity changes without allocating a request ID or consuming cadence. */
 void synchronizeTarget(FactoryGoggleTarget next) {
  if (Objects.equals(next, target)) return;
  target = next;
  value = null;
  lastRequest = Long.MIN_VALUE;
  requests.clear();
 }

 public long requestId() { return requestId; }
 public boolean accept(FactoryAdvancedGoggleProjection response,long responseId,long now) {
  Long sent=requests.get(responseId);
  if(!response.target().equals(target)||sent==null||now<sent||now-sent>=2L*response.intervalTicks()||value!=null&&response.sampleTick()<value.sampleTick())return false;
  value=response;expires=now+2L*response.intervalTicks();return true;
 }
 public Optional<FactoryAdvancedGoggleProjection> value(long now) { if(now>=expires)value=null;return Optional.ofNullable(value); }
 public void clear() {target=null;value=null;lastRequest=Long.MIN_VALUE;requests.clear();}
}
