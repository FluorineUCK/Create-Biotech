package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import java.util.*;
/** Local capabilities are never portable values. This guard uses construction-time cached metadata. */
public final class LocalReferences {
 private LocalReferences(){}
 public static void portable(DataValue value){if(Objects.requireNonNull(value).localReferences()!=0)throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Local references cannot cross this value boundary; read a snapshot first");}
 public static DataValue.RecordValue describeRoots(DataValue.RecordValue value){if(value.localReferences()==0)return value;var fields=new LinkedHashMap<String,DataValue>();value.fields().forEach((name,child)->fields.put(name,child instanceof DataValue.LocalReference r?new DataValue.TextValue(r.toString()+" cell="+r.cell()):child.localReferences()==0?child:new DataValue.TextValue("Invalid nested local capability")));return new DataValue.RecordValue(fields);}
 public static boolean contains(DataType type){var pending=new ArrayDeque<DataType>();pending.push(type);var visited=Collections.newSetFromMap(new IdentityHashMap<DataType,Boolean>());while(!pending.isEmpty()){var next=pending.pop();if(!visited.add(next))continue;switch(next){case DataType.RefType ignored->{return true;}case DataType.ListType t->pending.push(t.element());case DataType.MapType t->pending.push(t.element());case DataType.OptionalType t->pending.push(t.value());case DataType.RecordType t->t.fields().values().forEach(pending::push);default->{}}}return false;}
}
