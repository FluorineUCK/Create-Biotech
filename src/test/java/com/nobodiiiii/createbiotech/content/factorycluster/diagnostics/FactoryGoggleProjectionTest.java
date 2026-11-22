package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FactoryGoggleProjectionTest {
 @Test void everyComponentRoundTripsAsBoundedPresentationOnlyState() {
  for (var projection : examples()) {
   var state = FactoryGoggleProjectionFormatter.project(projection);
   assertEquals(state, FactoryGoggleState.load(state.save()).orElseThrow());
   assertTrue(state.rows().size() <= FactoryGoggleState.MAX_ROWS);
   assertFalse(state.save().toString().contains("00000000-0000-0000-0000-000000000001"));
  }
 }
 @Test void rejectsOversizedAndUnknownClientPayloads() {
  var tag = FactoryGoggleProjectionFormatter.project(examples().getFirst()).save();
  tag.putUUID("memberId", UUID.randomUUID());
  assertTrue(FactoryGoggleState.load(tag).isEmpty());
  assertThrows(IllegalArgumentException.class, () -> new FactoryGoggleState(Collections.nCopies(129, new FactoryGoggleState.Row("state", List.of("RUN")))));
 }
 @Test void listenerFrequenciesHaveLiteralTwentyFiveCharacterBound() {
  assertThrows(IllegalArgumentException.class, () -> new FactoryGoggleProjection.Computer("abcd1234", "AGENT", "FORMED", "FARMER", 4, 2, 4, "x".repeat(26), List.of(), "RUN"));
 }
 @Test void unclaimedPanelShowsUnpublishedInsteadOfOperationalCluster() {
  var state=FactoryGoggleProjectionFormatter.project(new FactoryGoggleProjection.Panel("", "WEST", "TRANSPARENT", "NONE", List.of(), 0, 0));
  assertTrue(state.rows().stream().anyMatch(row -> row.key().equals("formation") && row.values().equals(List.of("@NOT_PUBLISHED"))));
 }
 static List<FactoryGoggleProjection> examples() {
  return List.of(new FactoryGoggleProjection.Computer("abcd1234", "AGENT", "FORMED", "FARMER", 4, 2, 4, "Factory-A", List.of("B"), "RUN"),
   new FactoryGoggleProjection.Core("VALID", "Factory-A", 3, 7, 2, 4, 1),
   new FactoryGoggleProjection.Panel("abcd1234", "NORTH", "OPAQUE", "RUN", List.of("Factory-A"), 3, 2),
   new FactoryGoggleProjection.Endpoint("INPUT", "Factory-A", "Main warehouse", 3, 2, "RUN"),
   new FactoryGoggleProjection.Radio("1.0, 2.0, 3.0", true, 20, "64.0", "ORDINARY"));
 }

 @Test void typedProjectionItselfCannotRetainFullIdsOrOversizedText() {
  var projection=new FactoryGoggleProjection.Endpoint("INPUT","00000000-0000-0000-0000-000000000001","x".repeat(512),1,0,"IDLE");
  assertFalse(projection.address().contains("-")); assertTrue(projection.alias().length()<=128);
 }

 @Test void c1ControlsAreRemovedOnlyFromTheNativeEndpointDisplayCopy() {
  for (char control : new char[]{0x85, 0x9F}) {
   String createAddress = "A" + control + "B";
   var endpoint = new FactoryGoggleProjection.Endpoint("FACTORY", createAddress, "Warehouse", null, -1, "NOT_APPLICABLE");
   var state = assertDoesNotThrow(() -> FactoryGoggleProjectionFormatter.project(endpoint));
   assertEquals("AB", endpoint.address());
   assertEquals("A" + control + "B", createAddress);
   assertEquals(state, FactoryGoggleState.load(state.save()).orElseThrow());
   assertDoesNotThrow(() -> FactoryGoggleProjectionFormatter.normal(state));
   assertTrue(state.rows().stream().flatMap(row -> row.values().stream())
     .noneMatch(value -> value.codePoints().anyMatch(Character::isISOControl)));
  }
 }
 @Test void oversizedC1EndpointDisplayStillHasBoundedRoundTripAndFormatting() {
  String createAddress = ("A" + (char) 0x85 + "B" + (char) 0x9F).repeat(100);
  var endpoint = new FactoryGoggleProjection.Endpoint("FACTORY", createAddress, "Warehouse", null, -1, "NOT_APPLICABLE");
  var state = assertDoesNotThrow(() -> FactoryGoggleProjectionFormatter.project(endpoint));
  assertEquals(state, FactoryGoggleState.load(state.save()).orElseThrow());
  assertDoesNotThrow(() -> FactoryGoggleProjectionFormatter.normal(state));
  assertTrue(endpoint.address().length() <= 128);
  assertTrue(state.rows().stream().flatMap(row -> row.values().stream())
    .allMatch(value -> value.length() <= 128 && value.codePoints().noneMatch(Character::isISOControl)));
  assertEquals(("A" + (char) 0x85 + "B" + (char) 0x9F).repeat(100), createAddress);
 }
}
