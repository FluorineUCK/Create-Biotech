package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
class PatternRecipeGraphTest {
 @Test void materialIsOnlyAnInputOnceConnectedAndDisconnectRemovesExactlyThatEdge() throws Exception {
  Class<?> type=Class.forName("com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternRecipeGraph");
  PatternRecipeDraft draft=PatternRecipeDraft.class.getConstructor().newInstance();
  Object graph=type.getConstructor(PatternRecipeDraft.class).newInstance(draft);
  PatternIngredient ingredient=new PatternIngredient(null,ResourceLocation.parse("c:ingots/iron"),3,DataComponentPredicate.EMPTY);
  Method add=type.getMethod("addMaterial",PatternIngredient.class,double.class,double.class);
  long a=(long)add.invoke(graph,ingredient,10d,20d), b=(long)add.invoke(graph,ingredient,30d,20d);
  assertTrue(draft.inputs(-1).isEmpty());
  Method connect=type.getMethod("connect",long.class,int.class);
  assertEquals(true,connect.invoke(graph,a,-1)); assertEquals(true,connect.invoke(graph,b,-1)); assertEquals(false,connect.invoke(graph,a,-1));
  assertEquals(List.of(ingredient,ingredient),draft.inputs(-1));
  type.getMethod("move",long.class,double.class,double.class).invoke(graph,a,80d,90d);
  assertEquals(List.of(ingredient,ingredient),draft.inputs(-1));
  type.getMethod("disconnect",long.class,int.class).invoke(graph,a,-1);
  assertEquals(List.of(ingredient),draft.inputs(-1));
 }
 @Test void stageReorderRetargetsEdgesWithoutFlatteningStageInputs() throws Exception {
  Class<?> type=Class.forName("com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternRecipeGraph");
  PatternRecipeDraft draft=PatternRecipeDraft.class.getConstructor().newInstance();
  draft.setAddress(-1,"machine"); draft.addStage(); draft.addStage();
  draft.setAddress(0,"cut"); draft.setAddress(1,"press");
  Object graph=type.getConstructor(PatternRecipeDraft.class).newInstance(draft);
  PatternIngredient ingredient=new PatternIngredient(null,ResourceLocation.parse("c:ingots/iron"),3,DataComponentPredicate.EMPTY);
  long material=(long)type.getMethod("addMaterial",PatternIngredient.class,double.class,double.class).invoke(graph,ingredient,10d,20d);
  type.getMethod("connect",long.class,int.class).invoke(graph,material,0);
  type.getMethod("moveStage",int.class,int.class).invoke(graph,0,1);
  assertEquals("press",draft.address(0)); assertEquals("cut",draft.address(1));
  assertTrue(draft.inputs(0).isEmpty()); assertEquals(List.of(ingredient),draft.inputs(1));
  type.getMethod("disconnect",long.class,int.class).invoke(graph,material,1);
  assertTrue(draft.inputs(1).isEmpty());
 } @Test void pointerHitUsesMovedWorldNodeAndFindsOnlyRealInputEdges() throws Exception {
  var draft=new PatternRecipeDraft();var graph=new PatternRecipeGraph(draft);
  var ingredient=new PatternIngredient(null,ResourceLocation.parse("c:ingots/iron"),3,DataComponentPredicate.EMPTY);
  long id=graph.addMaterial(ingredient,30,30);graph.connect(id,-1);
  Method nodeAt=PatternRecipeGraph.class.getMethod("nodeAt",double.class,double.class);
  Method edgeAt=PatternRecipeGraph.class.getMethod("edgeAt",double.class,double.class,double.class);
  assertEquals(graph.get(id),nodeAt.invoke(graph,40d,40d));
  assertEquals(new PatternRecipeGraph.Edge(id,-1),edgeAt.invoke(graph,170d,60d,2d));
  graph.move(id,400,400);
  assertNull(nodeAt.invoke(graph,40d,40d)); assertEquals(graph.get(id),nodeAt.invoke(graph,410d,410d));
  graph.disconnect(id,-1);assertNull(edgeAt.invoke(graph,170d,60d,2d));
 }
 @Test void rejectedOverfullStageConnectionIsAtomicAndDoesNotThrowFromClick() {
  var draft=new PatternRecipeDraft();draft.setAddress(-1,"press");draft.addStage();
  var ingredient=new PatternIngredient(null,ResourceLocation.parse("c:ingots/iron"),1,DataComponentPredicate.EMPTY);
  for(int i=0;i<net.minecraft.world.item.component.WritableBookContent.PAGE_EDIT_LENGTH;i++)draft.setInput(0,i,ingredient);
  var graph=new PatternRecipeGraph(draft);long id=graph.addMaterial(ingredient,20,20);
  int edges=graph.edges().size();
  assertFalse(graph.connect(id,0));
  assertEquals(edges,graph.edges().size());assertEquals(net.minecraft.world.item.component.WritableBookContent.PAGE_EDIT_LENGTH,draft.inputs(0).size());
 }}


