package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutedProductionPublicPathTest {
 @Test void all59HistoricalArchivesRemainByteExactAndNewCatalogEntriesAreOrdinaryPublicDocuments()throws Exception{
  var loader=getClass().getClassLoader();try(var in=loader.getResourceAsStream("assets/create_biotech/biocomputing/routed_original_bcp_sha256.json")){var hashes=com.google.gson.JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();assertEquals(59,hashes.size());for(var e:hashes.entrySet())try(var archive=loader.getResourceAsStream("assets/create_biotech/biocomputing/examples/"+e.getKey())){assertNotNull(archive);assertEquals(e.getValue().getAsString(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive.readAllBytes())),e.getKey());}}
  java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> resources=id->loader.getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath());var catalog=ProgramExampleCatalog.load(resources);
  for(String role:List.of("a","e","d","f")){var e=catalog.stream().filter(x->x.id().equals("routed_production_"+role)).findFirst().orElseThrow();var published=ProgramExampleCatalog.preview(e,resources,FoundationRecipeTest.LIMITS);assertEquals(RoutedProductionDocuments.build(role),published);var body=published.flow().orElseThrow();var definition=FlowProcedureTools.definitions(body).getFirst();assertEquals(RoutedProductionDocuments.source(role),definition.assembly().orElseThrow().text());assertEquals(Set.of("e","f").contains(role)?7:6,definition.assembly().orElseThrow().version());assertEquals(role.equals("e")?2:1,definition.key().revision());assertTrue(published.archive().modules().isEmpty());assertTrue(published.archive().program().entries().isEmpty());}
 }
 @Test void resultTerminalUsesHumanLabelsAndOrdinaryBindingsRatherThanInventedCompletion()throws Exception{
  var doc=RoutedProductionDocuments.published("a");var terminal=doc.terminal().orElseThrow();assertTrue(terminal.readOnly());var expected=Map.of("c","本地检查","e","期望地址","a","当前地址（观察值）","o","订单原地址","r","原始回执");
  var raw=new DataValue.RecordValue(Map.of("code",new DataValue.TextValue("existing"),"address",new DataValue.TextValue("original")));var result=new DataValue.RecordValue(Map.of("c",new DataValue.TextValue("historical_route_mismatch"),"e",new DataValue.TextValue("new"),"a",new DataValue.TextValue("new"),"o",new DataValue.TextValue("original"),"r",raw));var roots=new DataValue.RecordValue(Map.of("output",new DataValue.RecordValue(Map.of("result",result))));
  for(var entry:expected.entrySet()){var label=(TerminalDefinition.Text)terminal.nodes().get("label_"+entry.getKey());assertEquals(new DataValue.TextValue(entry.getValue()),((TerminalBinding.Literal)label.value()).value());var value=(TerminalDefinition.Text)terminal.nodes().get("value_"+entry.getKey());assertInstanceOf(TerminalBinding.Path.class,value.value());assertEquals(result.field(entry.getKey()),value.value().resolve(roots));}
  assertEquals(DataValue.NullValue.INSTANCE,TerminalStatusView.ofFlowOwner(doc.terminal(),Optional.empty()).field("output"));
 }
}
