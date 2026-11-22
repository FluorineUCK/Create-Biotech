package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.FoundationRecipeTest;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutedPolicyCompatibilityTest {
 static byte[] resource(String path)throws Exception{try(var in=RoutedPolicyCompatibilityTest.class.getResourceAsStream(path)){assertNotNull(in,path);return in.readAllBytes();}}
 static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 @Test void sixtyOneOldArchivesStayExactAndExactE1RemainsDecodableWithoutMigration()throws Exception{
  var hashes=com.google.gson.JsonParser.parseString(new String(resource("/assets/create_biotech/biocomputing/text_next_original_bcp_sha256.json"),StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(62,hashes.size());int unchanged=0;
  for(var entry:hashes.entrySet()){String path=entry.getKey().substring("src/main/resources".length());if(path.endsWith("/routed_production_e.bcp"))continue;assertEquals(entry.getValue().getAsString(),hash(resource(path)),path);unchanged++;}assertEquals(61,unchanged);
  var e1=resource("/assets/create_biotech/biocomputing/history/routed_production_e_v1.bcp");assertEquals("5f7aa947f977050302433e97f28c7d98a08ff2561b9121aadbb594c18f57f529",hash(e1));var source=resource("/assets/create_biotech/biocomputing/history/routed_production_e_v1.asm");assertEquals("1ea222ae0ad29a248bb5c949fe62f9914cbcbdc0134e6bece0e6b83c60206f27",hash(source));
  var old=ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(e1,StandardCharsets.UTF_8).strip()),FoundationRecipeTest.LIMITS);var definition=FlowProcedureTools.definitions(old.flow().orElseThrow()).getFirst();assertEquals(1,definition.key().revision());assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,definition.assembly().orElseThrow().version());assertEquals(new String(source,StandardCharsets.UTF_8),definition.assembly().orElseThrow().text());
  var current=RoutedProductionDocuments.published("e");var e2=FlowProcedureTools.definitions(current.flow().orElseThrow()).getFirst();assertEquals(2,e2.key().revision());assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,e2.assembly().orElseThrow().version());assertNotEquals(old,current);assertEquals(1,FlowProcedureTools.definitions(old.flow().orElseThrow()).getFirst().key().revision(),"Current catalog does not mutate historical/installed E1");
  var f=FlowProcedureTools.definitions(RoutedProductionDocuments.published("f").flow().orElseThrow()).getFirst();assertEquals(1,f.key().revision());assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,f.assembly().orElseThrow().version());
 }
}
