package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;import java.util.*;import org.junit.jupiter.api.Test;
class ComputerValueDisplayTest {
 @Test void recursivelyFormatsMapsErrorsAndEveryWrappedCharacter(){var value=new DataValue.MapValue(Map.of("fault",new DataValue.ErrorValue("E","abcdefghij")));var lines=ComputerValueDisplay.lines(value,8);assertTrue(String.join("",lines).replace(" ","").contains("abcdefghij"));assertTrue(String.join(" ",lines).contains("E"));}
 @Test void deepCaptureIsBoundedAndNeverProducesPartialText(){
   DataValue value=new DataValue.IntegerValue(7);
   for(int i=0;i<1000;i++)value=new DataValue.ListValue(List.of(value));
   var text=com.nobodiiiii.createbiotech.content.biocomputing.computation.DiagnosticValueText.render(value,2001);
   assertEquals("[".repeat(1000)+"7"+"]".repeat(1000),text.orElseThrow());
   assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.computation.DiagnosticValueText.render(value,2000).isEmpty());
 }
 @Test void clampsPagesAndRetainsLastPartialPage(){var page=ComputerValueDisplay.page(List.of("a","b","c"),99,2);assertEquals(1,page.index());assertEquals(List.of("c"),page.lines());}
}
