package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
class FactoryGoggleProjectionRadioTest {
 @Test void nearestLinkUsesExistingPolicyAndImmutableFrameWithoutInventingMissingConnections() {
  var source=endpoint(1,1,0,"A"); var peer=endpoint(2,2,64,"A"); var wrong=endpoint(3,3,1,"B");
  var frame=frame(true,source,peer,wrong);
  var projection=FactoryGoggleCapture.radio(source,List.of(wrong,peer),frame,new RadioLinkPolicy(64,128),40).orElseThrow();
  assertEquals("64.0",projection.distance()); assertEquals("ORDINARY",projection.linkType()); assertEquals(20,projection.frameAge());
  var unavailable=FactoryGoggleCapture.radio(source,List.of(wrong),frame,new RadioLinkPolicy(64,128),40).orElseThrow();
  assertEquals("UNAVAILABLE",unavailable.linkType()); assertEquals("@UNAVAILABLE",unavailable.distance());
 }
 @Test void unloadedOrWrongGenerationSamplesCannotBecomeNormalRadioLinks() {
  var source=endpoint(1,1,0,"A"); var peer=endpoint(2,2,32,"A");
  var good=frame(true,source,peer); var samples=new HashMap<>(good.samples());
  samples.put(peer.key(),samples.get(peer.key()).withLoaded(false));
  assertEquals("UNAVAILABLE",FactoryGoggleCapture.radio(source,List.of(peer),new RadioTopologyFrame(20,true,samples),new RadioLinkPolicy(64,128),40).orElseThrow().linkType());
  assertTrue(FactoryGoggleCapture.radio(peer,List.of(source),new RadioTopologyFrame(20,true,samples),new RadioLinkPolicy(64,128),40).isEmpty());
 }
 @Test void missingDynamicCrossStructureConnectionIsNotInventedFromStaleWorldCenters() {
  var source=endpoint(1,1,0,"A"); var peer=endpoint(2,2,32,"A");
  assertEquals("UNAVAILABLE",FactoryGoggleCapture.radio(source,List.of(peer),frame(false,source,peer),new RadioLinkPolicy(64,128),40).orElseThrow().linkType());
 }
 private static RadioEndpoint endpoint(long cluster,long member,int x,String frequency) {
  return new RadioEndpoint(new UUID(0,cluster),new UUID(0,member),new SpaceAddress(Level.OVERWORLD,new UUID(1,member),new BlockPos(x,0,0)),0,CommunicationFrequency.of(frequency),2,Set.of());
 }
 private static RadioTopologyFrame frame(boolean dynamic,RadioEndpoint... endpoints) {
  var samples=new HashMap<RadioEndpoint.Key,RadioTopologyFrame.Sample>();
  for(var e:endpoints) samples.put(e.key(),new RadioTopologyFrame.Sample(Level.OVERWORLD,e.address(),Vec3.atCenterOf(e.address().localPos()),true,0,Optional.ofNullable(e.address().subLevelId())));
  return new RadioTopologyFrame(20,dynamic,samples);
 }
}
