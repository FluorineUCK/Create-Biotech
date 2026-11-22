package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import static org.junit.jupiter.api.Assertions.*;
/** Test measurement only: use the SAME incremental codec as public SERIAL.SEND, not the alternative compact full codec. */
public final class RoutedProductionFrameProbe {
 private RoutedProductionFrameProbe() {}
 public static int incrementalBytes(DataValue.RecordValue value,SerialRecordCodec.Limits codec){var state=SerialEncoding.begin(value,codec);for(int n=0;!state.done();n++){assertTrue(n<1000000);state=state.advance();}assertNull(state.encoded().error());return state.encoded().bytes().length;}
}
