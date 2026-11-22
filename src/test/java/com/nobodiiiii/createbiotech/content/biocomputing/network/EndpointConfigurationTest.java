package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class EndpointConfigurationTest {
	@Test void literalNameAndExactTypedValueRoundTrip() {
		var config = new EndpointConfiguration(" A§c\t* ", true);
		assertEquals(Map.of("name", new DataValue.TextValue(" A§c\t* "), "enabled", new DataValue.BooleanValue(true)), config.value().fields());
		assertEquals(config, EndpointConfiguration.decode(config.value()));
	}
	@Test void namesEnforceLiteralInvariant() {
		assertThrows(NullPointerException.class, () -> new EndpointConfiguration(null, true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration("", true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration(" ".repeat(65), true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration("\ud800", true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration("\udc00", true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration("x".repeat(65), true));
		assertThrows(IllegalArgumentException.class, () -> new EndpointConfiguration("  ", true));
		assertEquals("😀".repeat(32), new EndpointConfiguration("😀".repeat(32), true).name());
	}
	@Test void decodeRequiresExactKeysAndTypes() {
		var good = new EndpointConfiguration("x", false).value();
		assertThrows(IllegalArgumentException.class, () -> EndpointConfiguration.decode(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("x")))));
		var extra = new LinkedHashMap<>(good.fields()); extra.put("extra", DataValue.NullValue.INSTANCE);
		assertThrows(IllegalArgumentException.class, () -> EndpointConfiguration.decode(new DataValue.RecordValue(extra)));
		assertThrows(IllegalArgumentException.class, () -> EndpointConfiguration.decode(new DataValue.RecordValue(Map.of("name", new DataValue.IntegerValue(1), "enabled", new DataValue.BooleanValue(true)))));
	}
}
