package com.nobodiiiii.createbiotech.foundation.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CanvasInteractionTest {
 @Test void nodeDraggingConvertsScreenDeltaToWorldDeltaAtCurrentZoom() {
  CanvasViewport viewport=new CanvasViewport(); viewport.zoomAt(0,0,8);
  CanvasInteraction<String> interaction=new CanvasInteraction<>(viewport);
  AtomicReference<Double> movedX=new AtomicReference<>(),movedY=new AtomicReference<>();
  interaction.beginNode("node");
  assertTrue(interaction.drag(10,-6,(node,dx,dy)->{assertEquals("node",node);movedX.set(dx);movedY.set(dy);}));
  assertEquals(5.0,movedX.get()); assertEquals(-3.0,movedY.get());
  CanvasInteraction.Release<String> release=interaction.release();
  assertTrue(release.active()); assertNull(release.clickedNode());
 }
 @Test void panningMovesViewportInScreenSpaceAndNeverReportsClick() {
  CanvasViewport viewport=new CanvasViewport(); CanvasInteraction<String> interaction=new CanvasInteraction<>(viewport);
  interaction.beginPan();
  assertTrue(interaction.drag(7,-4,(node,dx,dy)->fail("pan must not move a node")));
  assertEquals(7.0,viewport.screenX(0)); assertEquals(-4.0,viewport.screenY(0));
  CanvasInteraction.Release<String> release=interaction.release();
  assertTrue(release.active()); assertNull(release.clickedNode());
 }
 @Test void nodePressWithoutMovementReportsClick() {
  CanvasInteraction<String> interaction=new CanvasInteraction<>(new CanvasViewport()); interaction.beginNode("node");
  CanvasInteraction.Release<String> release=interaction.release();
  assertTrue(release.active()); assertEquals("node",release.clickedNode());
 }
 @Test void cancelDropsGestureWithoutClickOrMovement() {
  CanvasInteraction<String> interaction=new CanvasInteraction<>(new CanvasViewport()); AtomicInteger moves=new AtomicInteger();
  interaction.beginNode("node"); interaction.cancel();
  assertFalse(interaction.drag(4,3,(node,dx,dy)->moves.incrementAndGet())); assertEquals(0,moves.get());
  CanvasInteraction.Release<String> release=interaction.release(); assertFalse(release.active()); assertNull(release.clickedNode());
 }
 @Test void zeroDeltaIsConsumedButKeepsNodeGestureAsClick() {
  CanvasInteraction<String> interaction=new CanvasInteraction<>(new CanvasViewport()); AtomicInteger moves=new AtomicInteger(); interaction.beginNode("node");
  assertTrue(interaction.drag(0,0,(node,dx,dy)->moves.incrementAndGet()));
  assertEquals(0,moves.get()); assertEquals("node",interaction.release().clickedNode());
 }
 @Test void releaseWithoutGestureIsInactive() {
  CanvasInteraction<String> interaction=new CanvasInteraction<>(new CanvasViewport()); CanvasInteraction.Release<String> release=interaction.release();
  assertFalse(release.active()); assertNull(release.clickedNode());
 }
}
