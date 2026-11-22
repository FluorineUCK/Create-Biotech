package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.FactoryAdvancedGoggleCapture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
class FactoryGoggleProjectionServiceTest {
 static FactoryGoggleTarget target(long generation) { return new FactoryGoggleTarget(new SpaceAddress(Level.OVERWORLD,null,BlockPos.ZERO),new UUID(0,1),generation,new UUID(0,2)); }
 static FactoryAdvancedGoggleProjection projection(FactoryGoggleTarget t) { return new FactoryAdvancedGoggleProjection(t,100,5,2,List.of("slot"),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()); }
 @Test void authorityRejectsBeforeAnyCaptureAndRateIsPerPlayerNotTarget() {
  var s=new FactoryGoggleProjectionService(); var p=UUID.randomUUID(); var t=target(1); var calls=new AtomicInteger();
  for(var result:List.of(FactoryGoggleProjectionService.Result.NO_GOGGLES,FactoryGoggleProjectionService.Result.NOT_SNEAKING,FactoryGoggleProjectionService.Result.OUT_OF_RANGE,FactoryGoggleProjectionService.Result.UNLOADED,FactoryGoggleProjectionService.Result.WRONG_TARGET,FactoryGoggleProjectionService.Result.STALE_TARGET)) {
   var ctx=new FactoryGoggleProjectionService.Context(result!=FactoryGoggleProjectionService.Result.NO_GOGGLES,result!=FactoryGoggleProjectionService.Result.NOT_SNEAKING,result!=FactoryGoggleProjectionService.Result.OUT_OF_RANGE,result!=FactoryGoggleProjectionService.Result.UNLOADED,result==FactoryGoggleProjectionService.Result.WRONG_TARGET?null:t.address(),result==FactoryGoggleProjectionService.Result.STALE_TARGET?target(2):t);
   assertEquals(result,s.request(p,ctx,t,100,5,()->{calls.incrementAndGet(); return projection(t);}).result());
  }
  assertEquals(0,calls.get()); var ctx=new FactoryGoggleProjectionService.Context(true,true,true,true,t.address(),t);
  assertEquals(FactoryGoggleProjectionService.Result.APPLIED,s.request(p,ctx,t,100,5,()->projection(t)).result());
  assertEquals(FactoryGoggleProjectionService.Result.RATE_LIMITED,s.request(p,ctx,t,101,5,()->{fail(); return null;}).result());
  assertEquals(FactoryGoggleProjectionService.Result.APPLIED,s.request(p,ctx,t,105,5,()->projection(t)).result());
 }
 @Test void cacheRejectsOldGenerationIncarnationAndClearsForEveryEligibilityChange() {
  var c=new FactoryGoggleClientCache(); var t=target(1); c.observe(t,true,true,100,5); assertTrue(c.accept(projection(t),c.requestId(),100));
  assertTrue(c.value(104).isPresent()); assertTrue(c.value(110).isEmpty());
  c.observe(target(2),true,true,111,5); assertFalse(c.accept(projection(t),c.requestId(),111));
  c.observe(t,true,false,112,5); assertFalse(c.accept(projection(t),c.requestId(),112));
  c.observe(t,false,true,113,5); assertFalse(c.accept(projection(t),c.requestId(),113));
  var replacement=new FactoryGoggleTarget(t.address(),t.memberId(),t.generation(),UUID.randomUUID());
  c.observe(replacement,true,true,114,5); assertFalse(c.accept(projection(t),c.requestId(),114));
  c.observe(null,true,true,115,5); assertTrue(c.value(115).isEmpty());
 }
 @Test void collectionBudgetsAreEnforcedBeforeSerialization() {
  var t=target(1); var tooMany=List.of("a","b","c");
  for(int collection=0;collection<8;collection++) { var lists=new ArrayList<List<String>>(Collections.nCopies(8,List.of())); lists.set(collection,tooMany);
   assertThrows(IllegalArgumentException.class,()->new FactoryAdvancedGoggleProjection(t,100,5,2,lists.get(0),lists.get(1),lists.get(2),lists.get(3),lists.get(4),lists.get(5),lists.get(6),lists.get(7)));
  }
 }
 @Test void configHasApprovedCadence() { assertEquals(5,FactoryRuntimeConfig.fromServerConfig().goggleDiagnosticsRequestIntervalTicks()); }

 @Test void returningToSameTargetRejectsTheOldRequestAndOldResponseDoesNotEvictNewCache() {
  var c=new FactoryGoggleClientCache();var a=target(1);var b=target(2);
  c.observe(a,true,true,100,5);long old=c.requestId();c.observe(b,true,true,101,5);long current=c.requestId();
  assertTrue(c.accept(projection(b),current,102));assertFalse(c.accept(projection(a),old,103));assertEquals(b,c.value(103).orElseThrow().target());
  c.observe(a,true,true,104,5);assertFalse(c.accept(projection(a),old,105));
 }
 @Test void differentSpaceMemberAndTargetCannotReuseAuthorityOrRateToken() {
  var s=new FactoryGoggleProjectionService();var p=UUID.randomUUID();var t=target(1);
  var otherSpace=new FactoryGoggleTarget(new SpaceAddress(Level.OVERWORLD,UUID.randomUUID(),BlockPos.ZERO),t.memberId(),t.generation(),t.incarnation());
  var c=new FactoryGoggleProjectionService.Context(true,true,true,true,otherSpace.address(),otherSpace);
  assertEquals(FactoryGoggleProjectionService.Result.WRONG_TARGET,s.request(p,c,t,100,5,()->{fail();return null;}).result());
  var otherMember=new FactoryGoggleTarget(t.address(),UUID.randomUUID(),1,t.incarnation());
  assertEquals(FactoryGoggleProjectionService.Result.STALE_TARGET,s.request(p,new FactoryGoggleProjectionService.Context(true,true,true,true,t.address(),otherMember),t,100,5,()->{fail();return null;}).result());
  assertEquals(FactoryGoggleProjectionService.Result.APPLIED,s.request(p,new FactoryGoggleProjectionService.Context(true,true,true,true,t.address(),t),t,100,5,()->projection(t)).result());
  assertEquals(FactoryGoggleProjectionService.Result.RATE_LIMITED,s.request(p,c,otherSpace,101,5,()->{fail();return null;}).result());
 }
 @Test void projectionRemovesAllIsoControlsWithoutTruncatingIds() {
  var id=UUID.randomUUID().toString();assertEquals(id,FactoryAdvancedGoggleProjection.text(id));
  assertEquals("AB",FactoryAdvancedGoggleProjection.text("A"+(char)0x85+"B"));
 }

 @Test void aggregateWireBudgetCannotBeMultipliedByEightCollections() {
  var one=List.of("row");assertThrows(IllegalArgumentException.class,()->new FactoryAdvancedGoggleProjection(target(1),100,5,2,one,one,one,one,one,one,one,one));
  var rows=new FactoryAdvancedGoggleCapture.Rows(2);rows.add(rows.slots,"a");rows.add(rows.routes,"b");rows.add(rows.errors,"c");assertEquals(2,rows.slots.size()+rows.routes.size()+rows.errors.size());
 }
 @Test void agentConflictsKeepBoundedFullClaimIds() {
  var rows=new FactoryAdvancedGoggleCapture.Rows(2);var route=new com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey(UUID.randomUUID(),"input");
  var claims=new ArrayList<com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration>();
  for(int n=0;n<4;n++)claims.add(new com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration(route,new UUID(0,n+1),new UUID(0,9),3,com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("A"),com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration.LoadState.LOADED,com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration.Reachability.REACHABLE));
  FactoryAdvancedGoggleCapture.agentResolution(rows,new com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution.Conflict(route,claims));
  assertEquals(2,rows.agents.size());assertTrue(rows.agents.getFirst().contains("Conflict"));assertTrue(rows.agents.get(1).contains(new UUID(0,1).toString()));
 }
}
