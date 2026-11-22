package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReadComponentRegistry<C> {
    private final Map<String, ReadComponentProvider<C>> providers;
    private final Map<String, ReadComponentDescriptor> descriptors;

    private ReadComponentRegistry(Map<String, ReadComponentProvider<C>> providers,
        Map<String, ReadComponentDescriptor> descriptors) {
        this.providers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.descriptors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(descriptors));
    }

    public static <C> Builder<C> builder() { return new Builder<>(); }

    public Set<String> ids() { return providers.keySet(); }

    public DataValue.RecordValue describe() {
        var descriptions = new LinkedHashMap<String, DataValue>();
        descriptors.forEach((id, descriptor) -> descriptions.put(id, descriptor.describe()));
        return new DataValue.RecordValue(descriptions);
    }

    public Session<C> session(C context, ReadLimits limits) {
        return new Session<>(providers, descriptors, context, Objects.requireNonNull(limits));
    }

    public static final class Builder<C> {
        private final Map<String, ReadComponentProvider<C>> providers = new LinkedHashMap<>();
        private final Map<String, ReadComponentDescriptor> descriptors = new LinkedHashMap<>();
        private boolean built;

        public Builder<C> register(ReadComponentProvider<C> provider) {
            if (built) throw new IllegalStateException("Registry builder is frozen");
            Objects.requireNonNull(provider);
            ReadComponentDescriptor descriptor = Objects.requireNonNull(provider.descriptor());
            if (providers.putIfAbsent(descriptor.id(), provider) != null)
                throw new IllegalArgumentException("Duplicate component id: " + descriptor.id());
            descriptors.put(descriptor.id(), descriptor);
            return this;
        }

        public ReadComponentRegistry<C> build() {
            built = true;
            return new ReadComponentRegistry<>(providers, descriptors);
        }
    }

    public static final class Session<C> {
        private final Map<String, ReadComponentProvider<C>> providers;
        private final Map<String, ReadComponentDescriptor> descriptors;
        private final C context;
        private final ReadLimits limits;
        private final Map<String, DataValue> cache = new LinkedHashMap<>();

        private Session(Map<String, ReadComponentProvider<C>> providers,
            Map<String, ReadComponentDescriptor> descriptors, C context, ReadLimits limits) {
            this.providers = providers;
            this.descriptors = descriptors;
            this.context = context;
            this.limits = limits;
        }

        public DataValue read(String id) {
            Objects.requireNonNull(id);
            DataValue cached = cache.get(id);
            if (cached != null) return cached;
            DataValue value = evaluate(id);
            cache.put(id, value);
            return value;
        }

        public DataValue.RecordValue read(Collection<String> ids) {
            Objects.requireNonNull(ids);
            var values = new LinkedHashMap<String, DataValue>();
            for (String id : ids) {
                Objects.requireNonNull(id);
                DataValue value = read(id);
                values.put(id, value);
                SnapshotLimits.validate(new DataValue.RecordValue(values), limits);
            }
            return new DataValue.RecordValue(values);
        }

        /** Separate request path: an ID-only full-read cache must not answer a range. */
        public DataValue readRange(String id, ReadRange range) {
            Objects.requireNonNull(id); Objects.requireNonNull(range);
            if (!range.fits(limits)) return error("snapshot_limit");
            var provider = providers.get(id);
            if (provider == null) return error("unknown_component");
            if (!(descriptors.get(id).valueType() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.ListType type))
                return error("unsupported_range");
            try {
                if (!provider.supports(context)) return error("unsupported");
                var value = provider.readRange(context, range);
                if (value == null) return error("provider_failure");
                SnapshotLimits.validate(value, limits);
                if (value instanceof DataValue.ErrorValue) return value;
                if (!(value instanceof DataValue.RecordValue page) || !range.accepts(page) || !type.accepts(page.field("items")))
                    return error("invalid_component_type");
                return page;
            } catch (SnapshotLimits.LimitExceeded exceeded) {
                return error("snapshot_limit");
            } catch (RuntimeException | StackOverflowError failure) {
                return error("provider_failure");
            }
        }

        private DataValue evaluate(String id) {
            ReadComponentProvider<C> provider = providers.get(id);
            if (provider == null) return error("unknown_component");
            final boolean supported;
            try {
                supported = provider.supports(context);
            } catch (RuntimeException | StackOverflowError failure) {
                return error("provider_failure");
            }
            if (!supported) return error("unsupported");
            final DataValue result;
            try {
                result = provider.read(context);
            } catch (SnapshotLimits.LimitExceeded exceeded) {
                return error("snapshot_limit");
            } catch (RuntimeException | StackOverflowError failure) {
                return error("provider_failure");
            }
            if (result == null) return error("provider_failure");
            try {
                SnapshotLimits.validate(result, limits);
            } catch (SnapshotLimits.LimitExceeded exceeded) {
                return error("snapshot_limit");
            }
            if (!(result instanceof DataValue.ErrorValue) && !descriptors.get(id).valueType().accepts(result))
                return error("invalid_component_type");
            return result;
        }

        private static DataValue.ErrorValue error(String code) { return new DataValue.ErrorValue(code, ""); }
    }
}
