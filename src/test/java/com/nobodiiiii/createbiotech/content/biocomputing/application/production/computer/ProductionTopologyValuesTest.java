package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner;
import java.lang.reflect.ParameterizedType;
import org.junit.jupiter.api.Test;

/** Architecture checks are deliberately separate from archive and topology behavior regressions. */
class ProductionTopologyValuesTest {
    @Test
    void actualAttachmentScannerAndArchiveExposeAuthoritativeProductionValues() throws Exception {
        var epoch = (ParameterizedType) ProductionComputer.class.getMethod("epoch").getGenericReturnType();
        var structure = (ParameterizedType) ProductionComputer.class.getMethod("currentStructureSnapshot").getGenericReturnType();
        var revision = (ParameterizedType) ProductionComputer.class.getDeclaredMethod("currentStructureRecord").getGenericReturnType();
        var archive = (ParameterizedType) ProductionComputer.class.getMethod("archiveValues").getGenericReturnType();
        assertAll(
            () -> assertEquals(ProductionEpoch.class, epoch.getActualTypeArguments()[0]),
            () -> assertEquals(ProductionStructure.class, structure.getActualTypeArguments()[0]),
            () -> assertEquals(ProductionStructure.Revision.class, revision.getActualTypeArguments()[0]),
            () -> assertEquals(ProductionStructure.class, ComputerStructureScanner.ScanResult.class.getMethod("structure").getReturnType()),
            () -> assertEquals(ProductionStructure.Revision.class, archive.getActualTypeArguments()[0]),
            () -> assertEquals(ProductionEpoch.class, archive.getActualTypeArguments()[2]));
    }
}
