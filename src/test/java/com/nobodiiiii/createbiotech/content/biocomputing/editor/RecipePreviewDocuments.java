package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Authoring fixture only. Runtime uses full public documents and ISA, never this class. */
public class RecipePreviewDocuments {
 static final DataType.RecordType CHECK=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(DataType.Semantic.RECIPE)));
 static String source(String role)throws Exception{try(var in=RecipePreviewDocuments.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/recipe_preview_"+role+".asm")){return new String(in.readAllBytes());}}
 public static ProgramDocument build(String role)throws Exception{
  var inputs=new LinkedHashMap<String,DataType>();inputs.put("poll_ticks",DataType.Scalar.INTEGER);if(role.equals("v"))inputs.put("batches",DataType.Scalar.INTEGER);
  // Types are decoded from authored TYPE declarations through the real assembler, not sample values.
  var scale=FlowProcedureTemplates.all().stream().filter(t->t.key().id().equals("scale_materials")).findFirst().orElseThrow();var materials=scale.inputs().get("materials");var maybeRecipe=new DataType.OptionalType(DataType.Semantic.RECIPE);
  var stage=new DataType.RecordType(Map.of("i",DataType.Scalar.INTEGER,"a",DataType.Scalar.ANY,"m",materials));var preview=new DataType.RecordType(Map.of("r",DataType.Semantic.RECIPE,"n",DataType.Scalar.INTEGER,"a",DataType.Scalar.ANY,"c",materials,"s",new DataType.ListType(stage)));
  DataType output=role.equals("v")?new DataType.RecordType(Map.of("o",DataType.Scalar.BOOLEAN,"r",maybeRecipe,"n",DataType.Scalar.INTEGER,"w",new DataType.ListType(new DataType.RecordType(Map.of("i",DataType.Scalar.INTEGER,"a",DataType.Scalar.ANY,"m",DataType.Scalar.ANY))))):role.equals("s")?scale.outputs().get("result"):new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(preview)));
  var key=new ModuleDefinition.Key("recipe_preview_"+role,1);var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));
  var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("preview",key,new FlowCallBindings(args,Map.of("result","result"))),new FlowProgram.Procedure("implementation",key,inputs,Map.of("result",output),Map.of("result","result"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source(role)))),new FlowProgram.EndProcedure("end"))).withAssembly();
  var initial=new LinkedHashMap<String,DataValue>();initial.put("poll_ticks",new DataValue.IntegerValue(20));if(role.equals("v"))initial.put("batches",new DataValue.IntegerValue(3));
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(initial))));
  var base=NativeLibraryResultLayouts.fields("result");var nodes=new LinkedHashMap<>(base.nodes());nodes.put("preview_label",new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("PREVIEW ONLY | pending: no result; rejected: ok=false; faults: execution diagnostic"))));nodes.put("preview_root",new TerminalDefinition.Column(List.of("preview_label",base.root())));workspace.setTerminal(Optional.of(new TerminalDefinition("preview_root",nodes)));
  return workspace.snapshot();
 }
 @Test void authoredCopiesMatchAndFit()throws Exception{for(String role:List.of("v","p","s")){var d=build(role);d.validate(new ResourceLimits(256,65536,1));byte[] bytes=ProgramDocumentWire.encode(d,FoundationRecipeTest.LIMITS);var p=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/recipe_preview_"+role+".bcp");if("1".equals(System.getenv("RECIPE_PREVIEW_WRITE")))Files.writeString(p,Base64.getEncoder().encodeToString(bytes)+"\n");else assertEquals(d,RecipePreviewDocumentsTest.published(role));System.out.println("PREVIEW_AUTHOR role="+role+" words="+d.flow().orElseThrow().program(d.archive()).usage(new ResourceLimits(256,65536,1)).programUnits());}}
}
