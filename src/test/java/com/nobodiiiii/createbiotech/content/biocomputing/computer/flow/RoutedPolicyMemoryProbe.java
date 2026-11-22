package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
/** Test-only full retained-owner ledger. Sampling is not an unobserved intra-tick transient peak. */
final class RoutedPolicyMemoryProbe {
 static long committed(FlowBlockHost.Saved saved,ComputerHostLimits limits){
  return NativeLibraryServiceSerialTest.units(saved,limits)+saved.cancellations().stream().mapToLong(w->w.value().storageUnits()).sum();
 }
 static long observedNextPreflight(FlowBlockHost.Saved saved,ComputerHostLimits limits){
  var image=FlowXBus.image(saved.program());var contexts=new ArrayList<FlowRuntime.Checkpoint>();contexts.add(saved.state());contexts.addAll(saved.tasks().contexts());
  return contexts.stream().anyMatch(s->s.status()==FlowRuntime.Status.READY&&s.assembly().isPresent()&&s.assembly().orElseThrow().pc()<image.code().size()&&image.code().get(s.assembly().orElseThrow().pc()).instruction() instanceof RegisterProgram.TextNext)?Math.addExact(committed(saved,limits),RegisterProgram.TextNext.RESULT_UNITS):0;
 }
}
