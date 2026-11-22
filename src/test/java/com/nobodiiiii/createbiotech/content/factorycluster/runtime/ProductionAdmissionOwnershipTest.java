package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissions;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProductionAdmissionOwnershipTest {
    @Test void actualHostHasOneApplicationAdmissionOwnerInsteadOfAnIndependentLedger() throws Exception {
        var host = Class.forName(FactoryOrchestrationServer.class.getName() + "$Runtime");
        var owner = host.getDeclaredField("productionAdmissions");
        assertEquals(ProductionAdmissions.class, owner.getType());
        assertThrows(NoSuchFieldException.class, () -> host.getDeclaredField("pendingProductionAdmissions"));
    }
}
