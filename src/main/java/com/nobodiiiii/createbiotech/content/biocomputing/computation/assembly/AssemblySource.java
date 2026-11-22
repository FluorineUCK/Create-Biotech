package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
/** Structural protocol version, independent of hardware/balance. */
public final class AssemblySource {
 public static final int VERSION=1;
 private final String text;
 // Derived host metadata only: never serialized, charged as ISA work, or used instead of validation.
 private volatile Set<String> opcodeHeads;
 public static boolean supported(long version) { return version == VERSION; }
 public AssemblySource(int version, String text) {
  this.text=Objects.requireNonNull(text);
  if(!supported(version))throw new IllegalArgumentException("Unsupported assembler version: "+version);
 }

 public AssemblySource(String text){this(VERSION,text);}
 public int version(){return VERSION;}
 public String text(){return text;}

 /** Instruction heads, not operands, comments, labels or substrings of opcodes. */
 public boolean hasOpcode(String opcode) {
  var heads=opcodeHeads;
  if(heads==null) {
   synchronized(this) {
    heads=opcodeHeads;
    if(heads==null) opcodeHeads=heads=scanOpcodeHeads();
   }
  }
  return heads.contains(opcode);
 }
 private Set<String> scanOpcodeHeads() {
  var heads=new HashSet<String>();
  for(var raw:ProcedureAssembler.sourceLines(text)) {
   var line=ProcedureAssembler.uncomment(raw);
   if(line.isEmpty())continue;
   int end=0;
   while(end<line.length() && !separator(line.charAt(end)))end++;
   heads.add(line.substring(0,end));
  }
  return Set.copyOf(heads);
 }
 // The assembler's ASCII \\s grammar, not Character.isWhitespace's wider Unicode set.
 private static boolean separator(char c){return c==' ' || c=='\t' || c=='\n' || c=='\r' || c=='\f' || c=='\u000b';}
 @Override public boolean equals(Object other){return other instanceof AssemblySource a && text.equals(a.text);}
 @Override public int hashCode(){return 31*Integer.hashCode(VERSION)+text.hashCode();}
 @Override public String toString(){return "AssemblySource[version="+VERSION+", text="+text+"]";}
}
