package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutedProductionCapacityTest {
 @Test void completePublishedRolesRetainIndependentDefaultBudgets()throws Exception{
  for(String role:List.of("a","e","d","f")){var d=RoutedProductionDocuments.build(role);var p=d.flow().orElseThrow().program(d.archive());assertEquals(Map.of("a",243L,"e",254L,"d",249L,"f",217L).get(role),p.usage(new ResourceLimits(256,65536,8)).programUnits());d.validate(new ResourceLimits(256,65536,8));}
  var old=ProductionDriverDocuments.build("a");assertEquals(243,old.flow().orElseThrow().program(old.archive()).usage(new ResourceLimits(256,65536,8)).programUnits());
 }
}
