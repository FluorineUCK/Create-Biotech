package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FisherListenerTest {
    final FisherListener f = new FisherListener();
    final List<String> calls = new ArrayList<>();
    final Map<String,Object> signals = new HashMap<>();
    String result = "ok";

    private void bind(long i, String mode, String pin) {
        var req = new DataValue.RecordValue(Map.of("op",new DataValue.TextValue("bind"), "channel",new DataValue.IntegerValue(i),
            "mode",new DataValue.TextValue(mode),"pin",new DataValue.TextValue(pin),"entry",new DataValue.TextValue("arrival")));
        f.configure(req,HardwarePins.DEFAULT,"arrival"::equals);
    }
    private void tick(long n) { f.tick(n,b -> signals.get(b.pin()),e -> { calls.add(e); return result; }); }

    @Test void risingAndFallingAreIndependentWithNoSyntheticFirstOrRepeatedEdges() {
        bind(0,"rising","p0"); bind(1,"falling","p1");
        signals.put("p0",false); signals.put("p1",true); tick(1); assertTrue(calls.isEmpty());
        signals.put("p0",true); signals.put("p1",false); tick(2); tick(2); tick(3);
        assertEquals(List.of("arrival","arrival"),calls);
        signals.clear(); tick(4);
        signals.put("p0",true); signals.put("p1",false); tick(5); assertEquals(2,calls.size());
    }
    @Test void onePendingLatchRetriesAndSurvivesPauseOrUnloadButUnobservedEdgesDoNot() {
        bind(0,"rising","p0"); signals.put("p0",false); tick(1); result="busy";
        signals.put("p0",true); tick(2); signals.put("p0",false); tick(3); signals.put("p0",true); tick(4);
        result="ok"; tick(5); tick(6); assertEquals(4,calls.size());
        signals.put("p0",false); tick(7); signals.put("p0",true); result="busy"; tick(8);
        var saved=f.save(); f.gap(); signals.put("p0",false); f.load(saved); result="ok";
        tick(60); tick(61); assertEquals(6,calls.size());
        f.load(f.save()); signals.put("p0",true); tick(62); assertEquals(6,calls.size());
        signals.put("p0",false); tick(63); signals.put("p0",true); tick(64); assertEquals(7,calls.size());
    }
    private final UUID owner=UUID.randomUUID(),flow=UUID.randomUUID();
    private FisherListener.Writer writer(int op) { return new FisherListener.Writer(owner,flow,"x0",op); }
    @Test void readyTracksSeparateSenderOperationsButNeverReplaysWhileHighOrAcrossAGap() {
        bind(0,"ready","x0"); signals.put("x0",writer(1)); tick(10); tick(11);
        signals.put("x0",writer(2)); tick(12); assertEquals(2,calls.size());
        signals.put("x0",writer(3)); tick(300); tick(301); assertEquals(3,calls.size());
        f.load(f.save()); tick(302); assertEquals(3,calls.size());
        signals.put("x0",writer(4)); tick(303); assertEquals(4,calls.size());
        assertThrows(IllegalArgumentException.class,()->bind(2,"ready","x0"));
        assertThrows(IllegalArgumentException.class,()->bind(1,"rising","x0"));
    }
}
