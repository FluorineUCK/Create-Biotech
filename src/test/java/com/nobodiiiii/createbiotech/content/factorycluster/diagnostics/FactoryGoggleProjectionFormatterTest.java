package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class FactoryGoggleProjectionFormatterTest {
 @Test void normalProjectionShowsAllRequiredReadableFields() {
  var computer=FactoryGoggleProjectionFormatter.normal(FactoryGoggleProjectionTest.examples().getFirst()).toString();
  for(var expected : new String[]{"AGENT", "RUN", "2/4", "Factory-A", "FARMER"}) assertTrue(computer.contains(expected), computer);
  var radio=FactoryGoggleProjectionFormatter.normal(FactoryGoggleProjectionTest.examples().getLast()).toString();
  assertTrue(radio.contains("20")); assertTrue(radio.contains("64.0")); assertTrue(radio.contains("ORDINARY"));
 }
 @Test void sneakingDoesNotInjectTechnicalIdsIntoNormalFormatter() {
  var projection=new FactoryGoggleProjection.Endpoint("INPUT", "00000000-0000-0000-0000-000000000001", "Warehouse", 1, 0, "IDLE");
  assertFalse(FactoryGoggleProjectionFormatter.normal(projection).toString().contains("00000000-0000-0000-0000-000000000001"));
 }

 @Test void everyNormalRowHasEnglishAndChineseLocalization() throws Exception {
  for(String locale: new String[]{"en_us","zh_cn"}) {
   var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/"+locale+".json"))).getAsJsonObject();
   for(var projection:FactoryGoggleProjectionTest.examples()) for(var row:FactoryGoggleProjectionFormatter.project(projection).rows()) assertTrue(json.has(FactoryGoggleProjectionFormatter.PREFIX+row.key()),row.key());
   assertEquals("最近链路：%s",locale.equals("zh_cn")?json.get(FactoryGoggleProjectionFormatter.PREFIX+"link").getAsString():"最近链路：%s");
  }
 }
 @Test void nativeGaugeWithoutAgentDisplaysInapplicableQueueRatherThanInventingZero() {
  var result=FactoryGoggleProjectionFormatter.normal(new FactoryGoggleProjection.Endpoint("FACTORY","A","Main",0,-1,"NOT_APPLICABLE")).toString();
  assertTrue(result.contains("NOT_APPLICABLE"));
 }

 @Test void literalFrequenciesAndAddressesAreNotTreatedAsTranslationTokens() {
  var projection=new FactoryGoggleProjection.Computer("abcd1234","AGENT","FORMED","FARMER",4,2,4,"@RUN",java.util.List.of("@ON"),"RUN");
  String normal=FactoryGoggleProjectionFormatter.normal(projection).toString();
  assertTrue(normal.contains("@RUN")); assertTrue(normal.contains("@ON"));
 }
}
