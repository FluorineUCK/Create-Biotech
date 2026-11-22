package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookVolumes;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;

/** Device-owned explicit grants. A book name is not an inherited permission. */
public record LibraryServiceConfiguration(Optional<EndpointConfiguration> endpoint, Map<String, Access> grants) {
    public enum Access { CLOSED, READ_ONLY, READ_WRITE }
    public LibraryServiceConfiguration {
        Objects.requireNonNull(endpoint);
        var copy = new TreeMap<String, Access>();
        grants.forEach((name, access) -> {
            LibraryBookVolumes.requireName(name); Objects.requireNonNull(access);
            if (access != Access.CLOSED) copy.put(name, access);
        });
        grants = Collections.unmodifiableMap(copy);
    }
    public static LibraryServiceConfiguration closed() { return new LibraryServiceConfiguration(Optional.empty(), Map.of()); }
    public boolean enabled() { return endpoint.filter(EndpointConfiguration::enabled).isPresent(); }
    public Access access(String name) { return grants.getOrDefault(name, Access.CLOSED); }
    public DataValue.RecordValue value() {
        var encoded = new LinkedHashMap<String, DataValue>();
        grants.forEach((name, access) -> encoded.put(name, new DataValue.TextValue(access.name())));
        return obj("endpoint", endpoint.<DataValue>map(EndpointConfiguration::value).orElse(DataValue.NullValue.INSTANCE),
            "grants", new DataValue.RecordValue(encoded));
    }
    public static LibraryServiceConfiguration decode(DataValue value) {
        var root = fields(value, "endpoint", "grants");
        var endpoint = root.field("endpoint");
        if (!(root.field("grants") instanceof DataValue.RecordValue grants)) throw new IllegalArgumentException("Expected grants");
        var result = new TreeMap<String, Access>();
        grants.fields().forEach((name, access) -> result.put(name, Access.valueOf(text(access))));
        return new LibraryServiceConfiguration(endpoint == DataValue.NullValue.INSTANCE ? Optional.empty()
            : Optional.of(EndpointConfiguration.decode(fields(endpoint, "name", "enabled"))), result);
    }
}

