package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import java.util.*;
/** Verified instruction metadata shared by owner admission, pin claims, receipts and recovery.
 * No world/device access, no source interpretation. Old Flow source contracts remain unchanged. */
public final class FlowXBus {
 private FlowXBus(){}
 private static final Map<FlowProgram,RegisterProgram> IMAGES=Collections.synchronizedMap(new WeakHashMap<>());
 public static RegisterProgram image(FlowProgram p){synchronized(IMAGES){var old=IMAGES.get(p);if(old!=null)return old;var compiled=p.compile(new ResourceLimits(Long.MAX_VALUE,Long.MAX_VALUE/4,1)).assembly().orElseThrow();if(IMAGES.size()>=64)IMAGES.remove(IMAGES.keySet().iterator().next());IMAGES.put(p,compiled);return compiled;}}
 public record Operation(String pin,RegisterXBus.Kind kind,int input,int output,DataType payload,boolean result){}
 public static Operation at(FlowProgram p,int cursor,int instruction){
  var step=p.steps().get(cursor);
  if(instruction>=0){var i=image(p);if(instruction>=i.code().size() || i.source(instruction)!=cursor || !(i.code().get(instruction).instruction() instanceof RegisterXBus.Instruction))throw new IllegalArgumentException("XBus instruction differs from source");var d=(RegisterXBus.Instruction)i.code().get(instruction).instruction();boolean result=step instanceof FlowProgram.Read r?r.result():d.kind()!=RegisterXBus.Kind.READ&&d.output()>=0;var type=d.kind()==RegisterXBus.Kind.READ?(step instanceof FlowProgram.Read r?r.type():i.registerTypes().get(d.output())):null;return new Operation(d.pin(),d.kind(),d.input(),d.output(),type,result);}
  return switch(step){case FlowProgram.Read r->new Operation(r.port(),RegisterXBus.Kind.READ,-1,-1,r.type(),r.result());case FlowProgram.Write w->new Operation(w.port(),RegisterXBus.Kind.WRITE,-1,-1,null,w.result().isPresent());case FlowProgram.AwaitReadable w->new Operation(w.port(),RegisterXBus.Kind.WAIT_READABLE,-1,-1,null,w.result().isPresent());default->throw new IllegalArgumentException("Not an XBus operation");};
 }
 public static Operation at(FlowProgram p,FlowRuntime.Checkpoint s){return at(p,s.cursor(),s.assembly().map(RegisterState::pc).orElse(-1));}
 public static boolean matches(XBusReceipt.Position position,FlowRuntime.Checkpoint state){return position.cursor()==state.cursor() && (position.instruction()<0 || state.assembly().map(r->r.pc()==position.instruction()).orElse(false));}
 public static String pin(FlowProgram p,FlowRuntime.Checkpoint s){return at(p,s).pin();}
 public static DataValue.RecordValue payload(FlowProgram p,FlowRuntime.Checkpoint s){var op=at(p,s);if(op.kind()!=RegisterXBus.Kind.WRITE)throw new IllegalArgumentException("Not a write");var payload=(DataValue.RecordValue)(s.assembly().isPresent()?s.assembly().orElseThrow().registers().get(op.input()):s.locals().field(((FlowProgram.Write)p.steps().get(s.cursor())).local()));LocalReferences.portable(payload);return payload;}
 public static Optional<RegisterProgram> authoredImage(FlowProgram.Procedure p){return authoredImage(p,Map.of());}
 public static Optional<RegisterProgram> authoredImage(FlowProgram.Procedure p,Map<String,ProgramDefinition.Cell> cells){return p.assembly().map(source -> ProcedureAssembler.assemble(source,p.inputs(),p.outputs(),p.returns(),com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES,Long.MAX_VALUE,cells).program());}
 public static List<RegisterProgram.Code> authored(FlowProgram.Procedure p){return authored(p,Map.of());}
 public static List<RegisterProgram.Code> authored(FlowProgram.Procedure p,Map<String,ProgramDefinition.Cell> cells){return authoredImage(p,cells).map(RegisterProgram::code).orElse(List.of());}
}
