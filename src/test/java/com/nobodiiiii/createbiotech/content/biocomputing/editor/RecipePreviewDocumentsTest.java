package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class RecipePreviewDocumentsTest {
 public static ProgramDocument published(String role)throws Exception{try(var in=RecipePreviewDocumentsTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/recipe_preview_"+role+".bcp")){assertNotNull(in,"Complete public preview role "+role);return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()),FoundationRecipeTest.LIMITS);}}
 @Test void completePublicEditablePreviewRolesFitDefaultHardware()throws Exception{
  for(String role:List.of("v","p","s")){var d=published(role);var p=d.flow().orElseThrow().program(d.archive());d.validate(new ResourceLimits(256,65536,1));assertTrue(d.terminal().isPresent());assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/recipe_preview_"+role+".bcp")),java.nio.file.Files.readAllBytes(java.nio.file.Path.of("examples/biocomputing/recipe_preview_"+role+".bcp")));System.out.println("PREVIEW role="+role+" words="+p.usage(new ResourceLimits(256,65536,1)).programUnits());}
 }
}
