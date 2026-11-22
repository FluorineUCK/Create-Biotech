package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryAdvancedGoggleProjection;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget;
import java.util.*;
import com.nobodiiiii.createbiotech.content.factorycluster.*;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
/** Bounded read-only production projections. No worker, scanner, admission, or command path. */
public final class FactoryAdvancedGoggleCapture {
 private FactoryAdvancedGoggleCapture() {}
 public static void agentResolution(Rows rows,com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution resolution) {
  rows.add(rows.agents,"resolution="+resolution.getClass().getSimpleName());
  if(resolution instanceof com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution.Conflict conflict)
   conflict.registrations().stream().limit(rows.limit).forEach(claim->rows.add(rows.agents,"conflictingAgent="+claim.agentId()+" generation="+claim.agentGeneration()+" owner="+claim.ownerClusterId()));
 }

 public static final class Rows {
  private int count;public final int limit;public final List<String> slots=new ArrayList<>(),routes=new ArrayList<>(),listeners=new ArrayList<>(),logistics=new ArrayList<>(),library=new ArrayList<>(),agents=new ArrayList<>(),formation=new ArrayList<>(),errors=new ArrayList<>();
  public Rows(int limit) {this.limit=Math.min(limit,FactoryAdvancedGoggleProjection.MAX_ROWS);}
  public void add(List<String> list,Object line){if(count<limit){list.add(FactoryAdvancedGoggleProjection.text(line));count++;}}
 }
 public static FactoryAdvancedGoggleProjection capture(BlockEntity be,FactoryGoggleTarget target,FactoryRuntimeConfig config) {
  FactoryGoggleCapture.requireServerThread(be);var level=(ServerLevel)be.getLevel();var rows=new Rows(config.panelRowLimit());UUID cluster=null;
  if(be instanceof ClusterMember member&&member.bindingState()!=null) {
   var binding=member.bindingState();cluster=binding.clusterId();rows.add(rows.formation,"cluster="+cluster+" bindingRevision="+binding.revision());
   binding.logisticsBindings().stream().limit(rows.limit).forEach(bindingRow->rows.add(rows.logistics,"network="+bindingRow.logisticsNetworkId()+" bindingRevision="+binding.revision()));
  }
  if(be instanceof HistoricalProductionHost computer) {
   cluster=computer.production().epoch().map(e->e.groupId()).orElse(cluster);
   rows.add(rows.formation,"formation="+computer.production().availabilityReason()+" online="+computer.production().structureOnline());
   computer.production().epoch().ifPresent(e->rows.add(rows.formation,"cluster="+e.groupId()+" epoch="+e.epochId()));
   computer.production().currentStructureSnapshot().ifPresent(s->rows.add(rows.formation,"bounds="+s.layout().bounds()));
   computer.production().latchedEpochFaults().stream().limit(rows.limit).forEach(f->rows.add(rows.errors,f));
   var listeners=computer.production().externalListenerConfiguration();listeners.frequencies().stream().limit(rows.limit).forEach(f->rows.add(rows.listeners,"frequency="+f.value()+" revision="+listeners.revision()));
  }
  if(be instanceof LibraryBlockEntity core) {
   core.diagnosticIndexRows().forEach(line->rows.add(rows.library,line));var s=core.summary();rows.add(rows.formation,"formation="+s.reason());var structure=core.structureSnapshot();if(structure!=null)rows.add(rows.formation,"bounds="+structure.min()+".."+structure.max());
   rows.add(rows.library,"library="+core.memberId()+" routingRevision="+core.routingConfiguration().revision());
   rows.add(rows.library,"indexedPages="+s.indexedPages()+"/"+s.pages()+" indexedPatterns="+s.indexedPatterns()+" queuedPages="+s.queuedPages()+" scanning="+s.scanning());
   s.errors().stream().limit(rows.limit).forEach(e->rows.add(rows.errors,e));
  }
  if(be instanceof FactoryPanelAgentAccess access) {
   var c=access.createBiotech$getAgentConfiguration().orElse(null);
   if(c!=null){cluster=c.ownerClusterId();rows.add(rows.agents,"agent="+c.agentId()+" epoch="+c.clusterEpochId()+" configRevision="+c.configurationRevision()+" generation="+c.agentGeneration());rows.add(rows.agents,"role="+c.role()+" completion="+c.completionMode()+" output="+c.outputMode()+" outputGeneration="+c.outputPolicyGeneration());}
  }
  if (!(be instanceof LibraryBlockEntity)) FactoryOrchestrationServer.captureAdvancedGoggles(be,cluster,target.memberId(),rows);
  return new FactoryAdvancedGoggleProjection(target,level.getServer().overworld().getGameTime(),config.goggleDiagnosticsRequestIntervalTicks(),rows.limit,rows.slots,rows.routes,rows.listeners,rows.logistics,rows.library,rows.agents,rows.formation,rows.errors);
 }
}
