package com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
/** Immutable task observation: declared outputs or diagnostic, never a private continuation. */
public final class TaskOutcome {
 private TaskOutcome() {}
 public static DataType.RecordType type(Map<String,DataType> outputs) {
  return new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"error",new DataType.OptionalType(DataType.Scalar.ERROR),
   "value",new DataType.OptionalType(new DataType.RecordType(outputs))));
 }
 public static DataValue.RecordValue success(DataValue.RecordValue outputs) { return value(outputs,null); }
 public static DataValue.RecordValue failure(String code,String message) { return value(null,new DataValue.ErrorValue(code,message)); }
 private static DataValue.RecordValue value(DataValue.RecordValue outputs,DataValue.ErrorValue error) {
  return new DataValue.RecordValue(Map.of("ok",new DataValue.BooleanValue(error==null),"error",error==null?DataValue.NullValue.INSTANCE:error,
   "value",outputs==null?DataValue.NullValue.INSTANCE:outputs));
 }
}
