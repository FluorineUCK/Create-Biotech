package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import org.junit.jupiter.api.*;
class FlowTaskJoinWorldTest {
 @BeforeAll static void setup() throws Exception { bootstrap(); }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void realDimensionCycleKeepsOneLocalBudgetDuringTaskWaitWithOrWithoutClusterRouting(boolean cluster) throws Exception {
  var f=fixture(); var block=(Host)f.computer().blockEntity; var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(100,100000,1),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3));
  var constructor=ComputerBehaviour.class.getDeclaredConstructor(com.simibubi.create.foundation.blockEntity.SmartBlockEntity.class,java.util.function.BooleanSupplier.class,java.util.function.Supplier.class); constructor.setAccessible(true);
  var computer=(ComputerBehaviour)constructor.newInstance(block,(java.util.function.BooleanSupplier)()->true,(java.util.function.Supplier<HardwareSelection>)()->new HardwareSelection(HardwareSelection.Status.READY,Optional.empty(),"task-test",limits)); block.computer=computer;
  var key=new ModuleDefinition.Key("c",1); var types=Map.<String,DataType>of("delay",DataType.Scalar.INTEGER); var p=new FlowProgram(types,List.of(
   new FlowProgram.TaskStart("launch",key,Map.of("delay","delay"),"h"),new FlowProgram.TaskJoin("join",key,"h","result"),new FlowProgram.Procedure("child",key,types,Map.of("answer",DataType.Scalar.INTEGER),Map.of("answer","delay")),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
  computer.install(p.expressions(),ComputerRuntime.MemoryPolicy.RESET); computer.configureEndpoint(new EndpointConfiguration("worker",true));
  if(cluster)assertEquals("configured",f.controller().configure(configuration(computer),f.player()));
  computer.installFlow(p,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(2)))); computer.setPaused(false);
  boolean waiting=false; long previousWait=-1;
  for(int tick=0;tick<20;tick++) {
   var cycle=new ComputerExecutionCycle(f.world(),List.of(computer),f.world().clock); long spent=0;
   for(int i=0;i<3;i++) spent+=cycle.advanceRound(0).work(); assertTrue(spent<=1,"One Computer, one CPU allowance, even inside Cluster");
   var saved=computer.flowSnapshot().orElseThrow();
   if(saved.state().status()==FlowRuntime.Status.WAITING_TASK) { waiting=true;if(previousWait>=0)assertEquals(previousWait,saved.state().work());previousWait=saved.state().work();}
   f.world().clock++;
  }
  var saved=computer.flowSnapshot().orElseThrow(); assertTrue(waiting); assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status()); assertTrue(saved.tasks().contexts().isEmpty());
  assertEquals(new DataValue.IntegerValue(2),((DataValue.RecordValue)((DataValue.RecordValue)saved.state().locals().field("result")).field("value")).field("answer"));
 }
}
