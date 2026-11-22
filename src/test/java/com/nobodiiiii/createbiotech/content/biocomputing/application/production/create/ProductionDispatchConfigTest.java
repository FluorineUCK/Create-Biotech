package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import java.nio.file.Path;
import net.neoforged.fml.config.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionDispatchConfigTest {
    @Test void nativeCapacityReloadDoesNotSupplyComputerCpuRamOrSerialBandwidth() throws ReflectiveOperationException {
        var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
        var values = CommentedConfig.inMemory(); spec.correct(values);
        values.set("biocomputing.production.nativeRequestEntries", 7);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
            .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(values, null, null));
        try {
            assertEquals(7, config.productionNativeRequestEntries.get());
            var computer = config.current(true); var serial = config.serialMedium(true);
            values.set("biocomputing.production.nativeRequestEntries", 3); spec.afterReload();
            assertEquals(3, config.productionNativeRequestEntries.get());
            assertEquals(computer, config.current(true)); assertEquals(serial, config.serialMedium(true));
            values.set("biocomputing.production.orders", 7);
            values.set("biocomputing.production.valueUnits", 8192L);
            values.set("biocomputing.production.activityTicks", 11);
            values.set("biocomputing.production.serial.frameBytes", 1024);
            values.set("biocomputing.production.serial.receiveBytes", 512L); spec.afterReload();
            assertEquals(7, config.productionOrders(true).orders());
            assertEquals(8192, config.productionOrders(true).valueUnits());
            assertEquals(11, config.productionInterval(true));
            assertEquals(512, config.productionSerial(true).ports().get("s0").maxFrameBytes());
            assertEquals(computer, config.current(true)); assertEquals(serial, config.serialMedium(true));
        } finally { spec.acceptConfig(null); }
    }
}
