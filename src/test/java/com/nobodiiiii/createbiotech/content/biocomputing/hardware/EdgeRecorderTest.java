package com.nobodiiiii.createbiotech.content.biocomputing.hardware;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EdgeRecorderTest {
    @Test void channelsShareCapacityAndMissingSamplesAreNotZero() {
        var r = new EdgeRecorder(); r.arm(List.of("p0","p1"));
        r.sample(1, Map.of("p0",0,"p1",15), 2);
        r.sample(2, Map.of("p0",15), 2);
        r.sample(2, Map.of("p0",3,"p1",0), 2);
        assertEquals(List.of(new EdgeRecorder.Edge(2,"p0",0,15),new EdgeRecorder.Edge(2,"p0",15,3)),r.edges());
        r.sample(3,Map.of("p0",8,"p1",1),2);
        assertTrue(r.overflow()); assertFalse(r.armed()); assertEquals(2,r.edges().size());
    }
    @Test void aNonTickingOwnerCannotInventEdgesOnReturn(){
        var r=new EdgeRecorder();r.arm(List.of("p0"));r.sample(10,Map.of("p0",0),64);
        r.sample(15,Map.of("p0",9),64);assertTrue(r.edges().isEmpty());
        r.sample(15,Map.of("p0",3),64);assertEquals(List.of(new EdgeRecorder.Edge(15,"p0",9,3)),r.edges());
    }
    @Test void resumeAndReloadNeverInventAnEdge() {
        var r=new EdgeRecorder(); r.arm(List.of("p1")); r.sample(0,Map.of("p1",3),64);r.sample(1,Map.of("p1",4),64);
        var restored=new EdgeRecorder();restored.load(r.save());restored.sample(18,Map.of("p1",15),64);
        assertEquals(r.edges(),restored.edges());restored.freeze();restored.sample(19,Map.of("p1",0),64);
        assertEquals(r.edges(),restored.edges()); restored.resume();restored.sample(20,Map.of("p1",2),64);
        assertEquals(r.edges(),restored.edges());restored.sample(21,Map.of("p1",1),64);assertEquals(2,restored.edges().size());
        assertThrows(IllegalArgumentException.class,()->restored.arm(List.of("p0","p0"))); assertEquals(2,restored.edges().size());
    }
}
