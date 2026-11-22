package com.nobodiiiii.createbiotech.content.biocomputing;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRetirementTest {
    @Test void publicDevicesShipWithoutTheOldProductionDispatcherOrSearch() throws Exception {
        // The historical writers used to exercise migrations may exist in tests, not in the mod.
        Path content = Path.of(ComputerBlockEntity.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).resolve("com/nobodiiiii/createbiotech/content");
        for (String live : List.of("biocomputing/computer/ComputerBlockEntity",
                "biocomputing/library/LibraryBlockEntity", "biocomputing/application/production/port/MaterialRequestPortBlockEntity"))
            assertTrue(Files.isRegularFile(content.resolve(live + ".class")), live);
        for (String retired : List.of("factorycluster/orchestration/radio/RadioRouter",
                "factorycluster/agent/AgentCoordinator", "factorycluster/createbridge/CreateLogisticsGateway",
                "factorycluster/runtime/FactoryOrchestrationServer",
                "biocomputing/application/production/search/LibrarySearchCoordinator",
                "biocomputing/application/production/ProductionComputerTick"))
            assertFalse(Files.exists(content.resolve(retired + ".class")), retired);
        var legacy = content.resolve("factorycluster");
        if (Files.isDirectory(legacy)) try (var files = Files.walk(legacy)) {
            assertEquals(List.of(), files.filter(path -> path.toString().endsWith(".class")).toList(),
                "No part of the retired module belongs to the mod classpath");
        }
    }
}
