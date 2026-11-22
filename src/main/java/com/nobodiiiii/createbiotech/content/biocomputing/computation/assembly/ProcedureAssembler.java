package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
/** Bounded local symbol linking; emitted code is verified/executed by the ordinary ISA. No Flow or GUI dependency. */
public final class ProcedureAssembler {
 private ProcedureAssembler() {}
 /** The assembler line grammar, shared by capability checks before persistence. */
 public static String[] sourceLines(String text) {
  return text.split("\\R", -1);
 }
 public record Image(RegisterProgram program, boolean waits, boolean loops, int authoredInstructions) {}
 private record Line(int number, String[] tokens) {}
 public static Image assemble(AssemblySource source, Map<String,DataType> inputs, Map<String,DataType> outputs,
   Map<String,String> returns, int maxBytes, long maxWords) {return assemble(source,inputs,outputs,returns,maxBytes,maxWords,Map.of());}
 /** Explicit installed cell link context; no names are resolved without this validated schema. */
 public static Image assemble(AssemblySource source,Map<String,DataType> inputs,Map<String,DataType> outputs,
   Map<String,String> returns,int maxBytes,long maxWords,Map<String,ProgramDefinition.Cell> cells) {
  var linkedCells=Collections.unmodifiableMap(new TreeMap<>(cells));
  if(source.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)throw invalid(1,"Assembly source exceeds document limit");
  var types=new ArrayList<DataType>();var locals=new TreeMap<String,Integer>();var args=new TreeMap<String,Integer>();
  var authoredTypes=new TreeMap<String,DataType>();
  inputs.forEach((name,type)->authoredTypes.put("input "+name,type));
  outputs.forEach((name,type)->authoredTypes.put("output "+name,type));
  var constants=new ArrayList<DataValue>(); var software=new SoftwareAnyLocals(types,constants);
  Optional<String> advancedTypeLocation = new TreeMap<>(inputs).entrySet().stream()
   .filter(e -> AssemblyTypeRequirements.dynamic(e.getValue())).map(e -> "input " + e.getKey()).findFirst();
  if (advancedTypeLocation.isEmpty()) advancedTypeLocation = new TreeMap<>(outputs).entrySet().stream()
   .filter(e -> AssemblyTypeRequirements.dynamic(e.getValue())).map(e -> "output " + e.getKey()).findFirst();
  new TreeMap<>(inputs).forEach((name,type)->{symbol(name,1);unreserved(name,1);args.put(name,types.size());locals.put(name,types.size());types.add(type);});
  outputs.keySet().forEach(name->unreserved(name,1));
  var aliases=new HashMap<String,DataType>();
  for(var scalar:DataType.Scalar.values())aliases.put(scalar.name(),scalar);
  aliases.put("TEXT_NEXT",RegisterProgram.TextNext.TYPE);
  aliases.put("SERIAL_SEND",com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.SEND);
  aliases.put("SERIAL_RECEIVE",com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.RECEIVE);
  aliases.put("SERIAL_STATUS",com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.STATUS);
  var lines=new ArrayList<Line>();var labels=new HashMap<String,Integer>(); int pc=0;
  var raw=sourceLines(source.text());
  for(int n=0;n<raw.length;n++){
   String text=uncomment(raw[n]); if(text.isEmpty())continue;
   var t=text.matches("CONST\\s.*")?text.split("\\s+",3):text.split("\\s+");int line=n+1;
   if(t[0].equals("TYPE")) {
    if(t.length<3)throw invalid(line,"TYPE requires name and shape");symbol(t[1],line);unreserved(t[1],line);
    DataType type;
    switch(t[2]) {
     case "REF" -> {arity(t,5,line);if(!t[4].equals("RW")&&!t[4].equals("RO"))throw invalid(line,"Reference permission must be RO or RW");var target=alias(aliases,t[3],line,"reference target");if(!(target instanceof DataType.Scalar scalar))throw invalid(line,"Reference target must be concrete");type=new DataType.RefType(scalar,t[4].equals("RW"));}
     case "FORMAT" -> {arity(t,5,line);type=format(t[3],t[4],line);}
     case "RECORD" -> {if((t.length-3)%2!=0)throw invalid(line,"TYPE RECORD requires field/type pairs");var fields=new LinkedHashMap<String,DataType>();for(int i=3;i<t.length;i+=2)if(fields.putIfAbsent(t[i],alias(aliases,t[i+1],line,"local"))!=null)throw invalid(line,"Duplicate field");type=new DataType.RecordType(fields);}
     case "LIST" -> {arity(t,4,line);type=new DataType.ListType(alias(aliases,t[3],line,"local"));}
     case "MAP" -> {arity(t,4,line);type=new DataType.MapType(alias(aliases,t[3],line,"local"));}
     case "OPTIONAL" -> {arity(t,4,line);type=new DataType.OptionalType(alias(aliases,t[3],line,"local"));}
     default -> throw invalid(line,"Unknown type shape");
    }
    if(aliases.putIfAbsent(t[1],type)!=null)throw invalid(line,"Duplicate type alias");
    authoredTypes.put("line "+line+" type "+t[1],type);continue;
   }
   if(t[0].equals("REG")){symbol(t.length>1?t[1]:null,line);DataType type;
    {
     unreserved(t[1],line);
     if(software.contains(t[1]))throw invalid(line,"Duplicate register: "+t[1]);
     if(t.length>=4 && t[2].equals("ANY")){
      if(locals.containsKey(t[1]))throw invalid(line,"Duplicate register: "+t[1]);
      if(inputs.values().stream().anyMatch(AssemblyTypeRequirements::dynamic) || outputs.values().stream().anyMatch(AssemblyTypeRequirements::dynamic))
       throw invalid(line,"Software ANY requires concrete input and output types");
      try{software.declare(t,line);}catch(IllegalArgumentException e){throw invalid(line,e.getMessage());}
      continue;
     }
    }
    if(t.length==4){
     type=switch(t[2]) {
      case "INPUT" -> alias(inputs,t[3],line,"input");
      case "OUTPUT" -> alias(outputs,t[3],line,"output");
      case "ELEMENT" -> {var list=types.get(reg(locals,t[3],line));if(!(list instanceof DataType.ListType l))throw invalid(line,"ELEMENT requires a previously declared list register");yield l.element();}
      default -> throw invalid(line,"Unknown register type alias: "+t[2]);
     };
    }else{arity(t,3,line);try{type=alias(aliases,t[2],line,"local");}catch(IllegalArgumentException e){throw invalid(line,"Unknown type: "+t[2]);}}
    if(locals.putIfAbsent(t[1],types.size())!=null)throw invalid(line,"Duplicate register: "+t[1]);types.add(type);
    // Only the fixed intrinsic envelope can carry an opaque payload. A user-defined lookalike,
    // a signature alias, or extracting its payload into ANY does not inherit this exemption.
    boolean opaqueEnvelope = t.length == 3 && t[2].equals("SERIAL_RECEIVE");
    boolean intrinsicEnvelope = t.length == 3 && Set.of("SERIAL_RECEIVE","SERIAL_SEND","SERIAL_STATUS","TEXT_NEXT").contains(t[2]);
    if(!intrinsicEnvelope) authoredTypes.put("line "+line+" register "+t[1],type);
    if (advancedTypeLocation.isEmpty() && !opaqueEnvelope && AssemblyTypeRequirements.dynamic(type)) advancedTypeLocation = Optional.of("line " + line + " register " + t[1]);
   }else if(t.length==1&&text.endsWith(":")){String name=text.substring(0,text.length()-1);symbol(name,line);unreserved(name,line);if(labels.putIfAbsent(name,pc)!=null)throw invalid(line,"Duplicate label: "+name);
   }else{lines.add(new Line(line,t));pc+=switch(t[0]) {
     case "EDGE.RISING","EDGE.FALLING" -> 4;
     case "EDGE.SAMPLE.RISING","EDGE.SAMPLE.FALLING" -> 6;
     default -> SoftwareAnyLocals.width(t[0]);
    };}
  }
  int prelude=software.prologue().size();
  if(prelude!=0)labels.replaceAll((name, target)->target+prelude);
  int epilogue=pc+prelude;boolean waits=false,loops=false;var code=new ArrayList<RegisterProgram.Code>();
  long words=0;
  for(var init:software.prologue()){
   words=charge(words,init.instruction(),maxWords,init.location());
   code.add(init);
  }
  for(var line:lines){var t=line.tokens();int n=line.number();RegisterProgram.Instruction op;
   if(SoftwareAnyLocals.width(t[0])!=1){
    List<RegisterProgram.Instruction> expanded;
    try{expanded=software.lower(t,locals,labels);}catch(IllegalArgumentException e){throw invalid(n,e.getMessage());}
    for(var instruction:expanded){
     if(instruction instanceof RegisterProgram.JumpFalse branch)loops|=branch.target()<=code.size();
     words=charge(words,instruction,maxWords,"line "+n);
     code.add(new RegisterProgram.Code(instruction,0,"line "+n));
    }
    continue;
   }
   try{switch(t[0]){
    case "CONST"->{arity(t,3,n);int to=reg(locals,t[1],n);DataValue value;
     value=com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.parse(com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.Kind.JSON,t[2]);
     int pool=constants.indexOf(value);if(pool<0){pool=constants.size();constants.add(value);}op=new RegisterProgram.Constant(to,pool);}
    case "VALUE.EQUALS"->{arity(t,4,n);op=new RegisterProgram.ValueEquals(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "FMT.CHECK"->{arity(t,5,n);op=new RegisterProgram.FormatCheck(reg(locals,t[1],n),reg(locals,t[2],n),t[3],format(t[3],t[4],n).version());}
    case "FMT.PROJECT"->{arity(t,5,n);op=new RegisterProgram.FormatProject(reg(locals,t[1],n),reg(locals,t[2],n),t[3],format(t[3],t[4],n).version());}
    case "VALUE.CHECK"->{arity(t,3,n);op=new RegisterProgram.ValueCheck(reg(locals,t[1],n),reg(locals,t[2],n));}
    case "HAS.FIELD"->{arity(t,4,n);op=new RegisterProgram.HasField(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "CORRELATION"->{arity(t,2,n);op=new RegisterProgram.Correlation(reg(locals,t[1],n));}
    case "SERIAL.SEND"->{arity(t,4,n);op=new RegisterSerial.Instruction(RegisterSerial.Kind.SEND,t[1],reg(locals,t[2],n),reg(locals,t[3],n),false);}
    case "SERIAL.RECEIVE"->{arity(t,3,n);op=new RegisterSerial.Instruction(RegisterSerial.Kind.RECEIVE,t[2],-1,reg(locals,t[1],n),false);}
    case "SERIAL.STATUS"->{arity(t,3,n);op=new RegisterSerial.Instruction(RegisterSerial.Kind.STATUS,t[2],-1,reg(locals,t[1],n),false);}
    case "XBUS.READ"->{arity(t,3,n);op=new RegisterXBus.Instruction(RegisterXBus.Kind.READ,t[2],-1,reg(locals,t[1],n));}
    case "XBUS.WRITE"->{arity(t,3,n);op=new RegisterXBus.Instruction(RegisterXBus.Kind.WRITE,t[1],reg(locals,t[2],n),-1);}
    case "FIELD"->{arity(t,4,n);op=new RegisterProgram.Field(reg(locals,t[1],n),reg(locals,t[2],n),t[3]);}
    case "DROP"->{
     arity(t,2,n);op=new RegisterProgram.Drop(reg(locals,t[1],n));
    }
    case "BYTES.JOIN"->{
     arity(t,4,n);op=new RegisterNbt.Instruction(RegisterNbt.Kind.JOIN_BYTES,reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));
    }
    case "VALUE.DECODE"->{
     arity(t,4,n);op=new RegisterNbt.Instruction(RegisterNbt.Kind.DECODE_UNITS,reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));
    }
    case "VALUE.ENCODE"->{
     arity(t,4,n);op=new RegisterNbt.Instruction(RegisterNbt.Kind.ENCODE_VALUE,reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));
    }
    case "NBT.TAG","NBT.LENGTH","NBT.CHILD","NBT.KEY","NBT.READ.TEXT","NBT.READ.INTEGER","NBT.PACK"->{
     var kind=RegisterNbt.Kind.valueOf(t[0].substring(4).replace('.','_'));boolean pack=kind==RegisterNbt.Kind.PACK;boolean indexed=kind==RegisterNbt.Kind.CHILD||kind==RegisterNbt.Kind.KEY;
     arity(t,pack||indexed?4:3,n);op=new RegisterNbt.Instruction(kind,reg(locals,t[1],n),reg(locals,t[pack?3:2],n),pack?Integer.parseInt(t[2]):indexed?reg(locals,t[3],n):-1);
    }
    case "TEXT.LETTER"->{arity(t,4,n);op=new RegisterProgram.TextLetter(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "TEXT.CONTAINS.CI"->{arity(t,4,n);op=new RegisterProgram.TextContains(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "TEXT.NEXT"->{arity(t,4,n);op=new RegisterProgram.TextNext(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "TEXT.CONCAT"->{arity(t,4,n);op=new RegisterProgram.TextConcat(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "TEXT.LENGTH"->{arity(t,3,n);op=new RegisterProgram.TextLength(reg(locals,t[1],n),reg(locals,t[2],n));}
                    case "LIST.INSERT", "LIST.REMOVE", "LIST.REPLACE", "LIST.SLICE" -> {
                        var operation = ListOperation.valueOf(t[0].substring("LIST.".length()));
                        arity(t, 2 + operation.inputs().size(), n);
                        var operands = new ArrayList<Integer>();
                        for (int i = 2; i < t.length; i++) operands.add(reg(locals, t[i], n));
                        op = new RegisterListEdit(operation, reg(locals, t[1], n), operands);
                    }
                    case "LIST.APPEND" -> {
                        arity(t,4,n);op=new RegisterProgram.AppendList(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));
                    }
                    case "LIST.MAKE"-> {
                        if (t.length<2) throw invalid(n, "LIST.MAKE requires destination");
                        var from = new ArrayList<Integer>();
                        for (int i = 2;i<t.length;i++)from.add(reg(locals, t[i], n));
                        op = new RegisterProgram.MakeList(reg(locals, t[1], n), from);
                    }
                    case "RECORD.MAKE"-> {
                        if (t.length<2 || (t.length-2)%2 != 0) throw invalid(n, "RECORD.MAKE requires key/register pairs");
                        var fields = new ArrayList<RegisterProgram.NamedOperand>();
                        for (int i = 2;i<t.length;i += 2)fields.add(new RegisterProgram.NamedOperand(t[i], reg(locals, t[i + 1], n)));
                        op = new RegisterProgram.MakeRecord(reg(locals, t[1], n), fields);
                    }
    case "RAND.INTEGER", "RAND.DECIMAL" -> {
     arity(t,4,n);op=new RegisterProgram.RandomRange(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n),t[0].equals("RAND.DECIMAL"));
    }
    case "RAND.BELOW" -> {
     arity(t,3,n);op=new RegisterProgram.RandomBelow(reg(locals,t[1],n),reg(locals,t[2],n));
    }
    case "LIST.LENGTH"->{arity(t,3,n);op=new RegisterProgram.ListLength(reg(locals,t[1],n),reg(locals,t[2],n));}
    case "LIST.GET"->{arity(t,4,n);op=new RegisterProgram.DynamicIndex(reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n));}
    case "MOV"->{arity(t,3,n);op=new RegisterProgram.Move(reg(locals,t[1],n),reg(locals,t[2],n));}
    case "JMP"->{arity(t,2,n);int target=label(labels,t[1],n);loops|=target<=code.size();op=new RegisterProgram.Jump(target);}
    case "JFALSE"->{arity(t,3,n);int target=label(labels,t[2],n);loops|=target<=code.size();op=new RegisterProgram.JumpFalse(reg(locals,t[1],n),target);}
    case "SIO.READ", "SIO.WRITE", "XBUS.STATUS"->{
     arity(t,3,n);var kind=switch(t[0]){case "SIO.READ"->RegisterDevices.Kind.SIO_READ;case "SIO.WRITE"->RegisterDevices.Kind.SIO_WRITE;default->RegisterDevices.Kind.XBUS_STATUS;};
     op=new RegisterDevices.Instruction(kind,reg(locals,t[kind.writesRegister()?1:2],n),t[kind.writesRegister()?2:1]);}
    case "EVENT.POST", "EVENT.POST.WAIT" -> {
     arity(t,2,n);boolean await=t[0].endsWith(".WAIT");waits|=await;op=new RegisterEvent(t[1],await,List.of());
    }
    case "TIMER.READ", "TIMER.RESET" -> {
     boolean reset=t[0].equals("TIMER.RESET");arity(t,reset?1:2,n);
     op=reset?RegisterTimer.RESET:new RegisterTimer(reg(locals,t[1],n));
    }
    case "TERMINAL.ASK", "TERMINAL.ANSWER" -> {
     arity(t,2,n);boolean asks=t[0].equals("TERMINAL.ASK");waits|=asks;op=new RegisterPrompt(asks,reg(locals,t[1],n));
    }
    case "TERMINAL.KEY.DOWN" -> {
     arity(t,3,n);op=new RegisterKeyDown(reg(locals,t[1],n),t[2]);
    }
    case "EDGE.SAMPLE.RISING", "EDGE.SAMPLE.FALLING", "EDGE.RISING", "EDGE.FALLING" -> {
     boolean sample=t[0].startsWith("EDGE.SAMPLE.");
     arity(t,5,n);
     var edge=new RegisterEdge(t[0].endsWith("RISING") ? RegisterEdge.Direction.RISING : RegisterEdge.Direction.FALLING,
         reg(locals,t[1],n),reg(locals,t[2],n),reg(locals,t[3],n),reg(locals,t[4],n));
     edge.verify(types::get);
     int temp=types.size();types.add(DataType.Scalar.BOOLEAN);
     var numeric=types.get(edge.current());
     var expanded=sample ? new RegisterEdgeSample(edge).lower(temp,numeric) : edge.lower(temp,numeric);
     for(var micro:expanded){words=charge(words,micro,maxWords,"line "+n);code.add(new RegisterProgram.Code(micro,0,"line "+n));}
     continue;
    }
    case "SLEEP"->{arity(t,2,n);waits=true;op=new RegisterProgram.Sleep(reg(locals,t[1],n));}
    case "REF.ADDRESS","REF.READ","REF.WRITE","REF.READONLY","REF.SAME"->{
     var kind=RegisterReference.Kind.valueOf(t[0].substring(4));arity(t,kind==RegisterReference.Kind.SAME?4:3,n);
     if(kind==RegisterReference.Kind.ADDRESS){int cell=new ArrayList<>(linkedCells.keySet()).indexOf(t[2]);if(cell<0)throw invalid(n,"Unknown linked public cell: "+t[2]);op=new RegisterReference(kind,cell,-1,reg(locals,t[1],n));}
     else if(kind==RegisterReference.Kind.WRITE)op=new RegisterReference(kind,reg(locals,t[1],n),reg(locals,t[2],n),-1);
     else op=new RegisterReference(kind,reg(locals,t[2],n),kind==RegisterReference.Kind.SAME?reg(locals,t[3],n):-1,reg(locals,t[1],n));
    }
    case "RET"->{arity(t,1,n);op=new RegisterProgram.Jump(epilogue);}
    default->{ScalarOperation operation;
     try{operation=ScalarOperation.valueOf(t[0]);}catch(IllegalArgumentException e){throw invalid(n,"Unknown opcode: "+t[0]);}
     
     if(operation.numericEdge())throw invalid(n,"Use the EDGE software macro");
     // Only operations whose declared operands are fixed-size scalars enter this dialect.
     if(!RegisterProgram.tagPredicate(operation) && operation.inputs().values().stream().anyMatch(type->!RegisterProgram.scalar(type)))throw invalid(n,"Unsupported variable-cost opcode: "+t[0]);
     arity(t,operation.inputs().size()+2,n);var from=new ArrayList<Integer>();for(int i=2;i<t.length;i++)from.add(reg(locals,t[i],n));
     op=new RegisterProgram.Alu(operation,reg(locals,t[1],n),from);}
   }}catch(NumberFormatException e){throw invalid(n,"Invalid scalar literal");}catch(IllegalArgumentException e){throw invalid(n,String.valueOf(e.getMessage()));}
   words=charge(words,op,maxWords,"line "+n);
   code.add(new RegisterProgram.Code(op,0,"line "+n));
  }
  int bodyInstructions=code.size();
  var results=new TreeMap<String,Integer>();
  new TreeMap<>(outputs).forEach((name,type)->{int from=reg(locals,returns.get(name),raw.length);int to=types.size();types.add(type);results.put(name,to);code.add(new RegisterProgram.Code(new RegisterProgram.Move(to,from),0,"line "+raw.length+" return "+name));});
  code.add(new RegisterProgram.Code(new RegisterProgram.Return(),0,"line "+raw.length+" return"));
  if(words+outputs.size()*3L+1>maxWords)throw new ComputationException(ComputationException.Code.PROGRAM_LIMIT,"assembly","Assembly return exceeds code capacity");
  try{return new Image(new RegisterProgram(code,types,constants,Map.of(),Map.of(),1,
   List.of(new RegisterProcedure(0,code.size()-1,"local",1,args,locals,results)), linkedCells, advancedTypeLocation,Set.of(),authoredTypes),waits,loops,bodyInstructions);
  }catch(IllegalArgumentException|ComputationException e){var match=java.util.regex.Pattern.compile("line (\\d+)").matcher(String.valueOf(e.getMessage()));
   int line=match.find()?Integer.parseInt(match.group(1)):raw.length;throw invalid(line,"ISA verification: "+e.getMessage());}
 }
 private static long charge(long words, RegisterProgram.Instruction instruction, long limit, String location) {
  long total=Math.addExact(words,instruction.words());
  if(total>limit)throw new ComputationException(ComputationException.Code.PROGRAM_LIMIT,"assembly/"+location,location+": Assembly instructions exceed code capacity");
  return total;
 }
 private static void unreserved(String name,int line){if(name.startsWith(SoftwareAnyLocals.PREFIX))throw invalid(line,"Reserved software ANY symbol: "+name);}
 private static DataType alias(Map<String,DataType> types,String name,int line,String kind){var type=types.get(name);if(type==null)throw invalid(line,"Undefined "+kind+" type alias: "+name);return type;}
 /** v4 treats comments only outside JSON strings, preserving literal whitespace and semicolons. */
 public static String uncomment(String line) {boolean quoted=false,escape=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='"')quoted=!quoted;else if(c==';'&&!quoted)return line.substring(0,i).trim();}return line.trim();}
 private static DataType.Semantic format(String id,String version,int line){
     try { return DataType.Semantic.recipe(id,version.length()==1 ? version.charAt(0)-'0' : -1); }
     catch(IllegalArgumentException bad){ throw invalid(line,"Unknown semantic format/version: "+id+"@"+version); }
 }
 private static void symbol(String name,int line){if(name==null||!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw invalid(line,"Invalid local symbol: "+name);}
 private static void arity(String[] t,int expected,int line){if(t.length!=expected)throw invalid(line,"Wrong operand count for "+t[0]);}
 private static int reg(Map<String,Integer> locals,String name,int line){var value=locals.get(name);if(value==null)throw invalid(line,"Undefined register: "+name);return value;}
 private static int label(Map<String,Integer> labels,String name,int line){var value=labels.get(name);if(value==null)throw invalid(line,"Undefined local label: "+name);return value;}
 private static ComputationException invalid(int line,String message){return new ComputationException(ComputationException.Code.INVALID_GRAPH,"assembly/line "+line,"line "+line+": "+message);}
}
