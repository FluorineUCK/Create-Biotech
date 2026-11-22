package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutedProductionProtocolTest {
 static final UUID DEVICE=new UUID(1,2),SESSION=new UUID(3,4),SOURCE=new UUID(5,6);
 static final DataValue.RecordValue TARGET=new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(1),"y",new DataValue.IntegerValue(2),"z",new DataValue.IntegerValue(3)));
 static DataValue.TextValue text(String s){return new DataValue.TextValue(s);}
 static DataValue.RecordValue frame(String operation,DataValue.RecordValue args){return new DataValue.RecordValue(Map.of("kind",text("production_command"),"version",new DataValue.IntegerValue(1),"target",TARGET,"request",text("actual-correlation-1"),"operation",text(operation),"arguments",args));}
 static ProductionPortProtocol host(String address,ProductionOrderService orders)throws Exception{
  return new ProductionPortProtocol(DEVICE,SESSION,7L,TARGET,address,orders);
 }
 @Test void onlyGrantedCurrentHostStatusExposesItsCanonicalDestination()throws Exception{
  var journal=new Journal(){public Snapshot load(){return Snapshot.empty();}public boolean commit(long expected,Snapshot next){fail("Status cannot commit orders");return false;}};
  var backend=new Backend(){public boolean authorized(UUID id){return SOURCE.equals(id);}public boolean supports(String e,int v){return false;}public Preparation prepare(UUID s,DataValue.RecordValue r){throw new AssertionError("No prepare from status");}public Dispatch dispatch(Operation o,DataValue.RecordValue c){throw new AssertionError("No dispatch");}public Observation observe(Operation o,DataValue.RecordValue r){throw new AssertionError("No observe");}};
  var protocol=host("workshop",new ProductionOrderService(journal,backend,new Limits(4,4,65536)));
  var empty=new DataValue.RecordValue(Map.of());var status=frame("status",empty);
  var granted=protocol.respond(SOURCE,status).orElseThrow();var state=(DataValue.RecordValue)granted.field("state");
  assertEquals(text("workshop"),state.field("address"));assertEquals(Set.of("device","session","revision","address"),state.fields().keySet());
  assertEquals(text("actual-correlation-1"),granted.field("request"));
  assertFalse(protocol.respond(null,status).orElseThrow().fields().containsKey("state"));
  assertFalse(ProductionPortProtocol.unavailable(SOURCE,TARGET,status,"storage_unavailable").orElseThrow().fields().containsKey("state"));
  assertFalse(protocol.respond(SOURCE,frame("status",new DataValue.RecordValue(Map.of("address",text("forged"))))).orElseThrow().fields().containsKey("state"));
 }
 @Test void aKnownPackagingOrderExposesOnlyItsFrozenRouteAfterDispatchAndRebind()throws Exception{
  class Store implements Journal {Snapshot state=Snapshot.empty();public Snapshot load(){return state;}public boolean commit(long expected,Snapshot next){if(state.revision()!=expected)return false;state=next;return true;}}
  class Adapter implements Backend {
   String address="original-route";long revision=1;int preparations,dispatches;
   public boolean authorized(UUID id){return SOURCE.equals(id);}public boolean supports(String e,int v){return e.equals("create.packaging")&&v==1;}
   public Preparation prepare(UUID source,DataValue.RecordValue data){preparations++;return Preparation.ready(new Plan("create.packaging",1,List.of(new DataValue.RecordValue(Map.of("network",text(new UUID(7,8).toString()),"address",text(address),"revision",new DataValue.IntegerValue(revision),"data",data)))));}
   public Dispatch dispatch(Operation operation,DataValue.RecordValue command){dispatches++;assertEquals(text("original-route"),command.field("address"));return Dispatch.accepted(new DataValue.RecordValue(Map.of("native",new DataValue.IntegerValue(1))));}
   public Observation observe(Operation operation,DataValue.RecordValue receipt){return Observation.pending();}
  }
  var store=new Store();var adapter=new Adapter();var orders=new ProductionOrderService(store,adapter,new Limits(4,4,65536));
  var patch=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of()));var row=new DataValue.RecordValue(Map.of("item",text("minecraft:stone"),"count",new DataValue.IntegerValue(2),"components",patch));
  var data=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(row,row))));
  var args=new DataValue.RecordValue(Map.of("device",text(DEVICE.toString()),"session",text(SESSION.toString()),"order",text("stable-job"),"data",data));
  var first=host(adapter.address,orders).respond(SOURCE,frame("submit",args)).orElseThrow();assertEquals(text("original-route"),first.field("address"));assertEquals(text("accepted"),first.field("code"));
  orders.advance();assertEquals(1,adapter.dispatches);adapter.address="changed-route";adapter.revision=2;
  var nextSession=UUID.randomUUID();var restored=new ProductionPortProtocol(DEVICE,nextSession,8,TARGET,adapter.address,new ProductionOrderService(store,adapter,new Limits(4,4,65536)));
  assertEquals(text("session_changed"),restored.respond(SOURCE,frame("submit",args)).orElseThrow().field("code"));
  var query=restored.respond(SOURCE,frame("order",new DataValue.RecordValue(Map.of("order",text("stable-job"))))).orElseThrow();
  assertEquals(text("original-route"),query.field("address"));assertEquals(text("waiting"),query.field("phase"));assertEquals(text("dispatch_accepted"),query.field("milestone"));
  var fresh=new LinkedHashMap<>(args.fields());fresh.put("session",text(nextSession.toString()));
  var existing=restored.respond(SOURCE,frame("submit",new DataValue.RecordValue(fresh))).orElseThrow();assertEquals(text("existing"),existing.field("code"));assertEquals(text("original-route"),existing.field("address"));
  fresh.put("data",new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(row)))));
  var conflict=restored.respond(SOURCE,frame("submit",new DataValue.RecordValue(fresh))).orElseThrow();assertEquals(text("request_conflict"),conflict.field("code"));assertEquals(text("original-route"),conflict.field("address"));
  assertEquals(1,adapter.preparations);assertEquals(1,adapter.dispatches);assertEquals(1,store.state.orders().size());
 }

 @Test void unsupportedOrMalformedFrozenPlansPublishExplicitlyUnavailableNeverTheCurrentRoute()throws Exception{
  var data=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("item",text("minecraft:stone"),"count",new DataValue.IntegerValue(1)))))));
  var valid=new DataValue.RecordValue(Map.of("network",text(new UUID(7,8).toString()),"address",text("original-route"),"revision",new DataValue.IntegerValue(1),"data",data));
  var plans=new ArrayList<Plan>();plans.add(new Plan("other",1,List.of(valid)));plans.add(new Plan("create.packaging",2,List.of(valid)));plans.add(new Plan("create.packaging",1,List.of(valid,valid)));
  for(String bad:List.of("missing_address","non_text","padded","control","network","revision","data")){var fields=new LinkedHashMap<>(valid.fields());switch(bad){case "missing_address"->fields.remove("address");case "non_text"->fields.put("address",new DataValue.IntegerValue(1));case "padded"->fields.put("address",text(" original "));case "control"->fields.put("address",text("bad\nroute"));case "network"->fields.put("network",text("invalid"));case "revision"->fields.put("revision",new DataValue.IntegerValue(-1));case "data"->fields.put("data",text("invalid"));}plans.add(new Plan("create.packaging",1,List.of(new DataValue.RecordValue(fields))));}
  for(var plan:plans){var journal=new Journal(){Snapshot state=Snapshot.empty();public Snapshot load(){return state;}public boolean commit(long expected,Snapshot next){state=next;return true;}};var backend=new Backend(){public boolean authorized(UUID id){return SOURCE.equals(id);}public boolean supports(String e,int v){return true;}public Preparation prepare(UUID source,DataValue.RecordValue request){return Preparation.ready(plan);}public Dispatch dispatch(Operation o,DataValue.RecordValue c){throw new AssertionError("Read facts must not dispatch");}public Observation observe(Operation o,DataValue.RecordValue r){throw new AssertionError("No observe");}};var protocol=host("current-route",new ProductionOrderService(journal,backend,new Limits(4,4,65536)));var reply=protocol.respond(SOURCE,frame("submit",new DataValue.RecordValue(Map.of("device",text(DEVICE.toString()),"session",text(SESSION.toString()),"order",text("job"),"data",data)))).orElseThrow();assertEquals(DataValue.NullValue.INSTANCE,reply.field("address"));assertFalse(reply.fields().containsKey("network"));assertFalse(reply.fields().containsKey("actor"));assertEquals(text("accepted"),reply.field("code"));}
 }

}
