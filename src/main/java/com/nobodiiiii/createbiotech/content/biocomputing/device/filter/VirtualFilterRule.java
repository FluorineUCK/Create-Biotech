package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** A bounded immutable rule snapshot. No item stacks, world addresses, or program callbacks are stored. */
final class VirtualFilterRule {
    static final int MAX_MATCHERS = 128;
    private static final int MAX_ID_CHARS = 128;
    private static final Set<String> RULE_FIELDS = Set.of("domain", "mode", "matchers");
    private static final Set<String> MATCHER_FIELDS = Set.of("kind", "name");

    enum Domain { ITEM, FLUID }
    enum Kind { ID, TAG }
    @FunctionalInterface interface Registry {
        boolean contains(Domain domain, Kind kind, ResourceLocation name);
    }

    private final Domain domain;
    private final boolean allow;
    private final Set<ResourceLocation> ids;
    private final Set<ResourceLocation> tags;

    private VirtualFilterRule(Domain domain, boolean allow, Set<ResourceLocation> ids, Set<ResourceLocation> tags) {
        this.domain = domain;
        this.allow = allow;
        this.ids = Set.copyOf(ids);
        this.tags = Set.copyOf(tags);
    }

    static VirtualFilterRule compile(DataValue.RecordValue value, int limit, Registry registry) {
        if (limit < 1 || limit > MAX_MATCHERS) throw new IllegalArgumentException("invalid_filter_limit");
        if (!value.fields().keySet().equals(RULE_FIELDS)
            || !(value.field("matchers") instanceof DataValue.ListValue matchers))
            throw invalid();
        var domain = switch (text(value.field("domain"))) {
            case "item" -> Domain.ITEM;
            case "fluid" -> Domain.FLUID;
            default -> throw invalid();
        };
        boolean allow = switch (text(value.field("mode"))) {
            case "allow" -> true;
            case "deny" -> false;
            default -> throw invalid();
        };
        if (matchers.values().isEmpty()) throw invalid();
        if (matchers.values().size() > limit) throw new IllegalArgumentException("filter_capacity");
        var ids = new HashSet<ResourceLocation>();
        var tags = new HashSet<ResourceLocation>();
        for (var entry : matchers.values()) {
            if (!(entry instanceof DataValue.RecordValue matcher) || !matcher.fields().keySet().equals(MATCHER_FIELDS))
                throw invalid();
            var kind = switch (text(matcher.field("kind"))) {
                case "id" -> Kind.ID;
                case "tag" -> Kind.TAG;
                default -> throw invalid();
            };
            String name = text(matcher.field("name"));
            var id = name.length() <= MAX_ID_CHARS ? ResourceLocation.tryParse(name) : null;
            if (id == null || !id.toString().equals(name)) throw invalid();
            if (!registry.contains(domain, kind, id)) throw new IllegalArgumentException("unknown_filter_matcher");
            if (!(kind == Kind.ID ? ids : tags).add(id)) throw invalid();
        }
        return new VirtualFilterRule(domain, allow, ids, tags);
    }

    Domain domain() { return domain; }

    boolean referencesAvailable(Registry registry) {
        for (var id : ids) if (!registry.contains(domain, Kind.ID, id)) return false;
        for (var tag : tags) if (!registry.contains(domain, Kind.TAG, tag)) return false;
        return true;
    }

    boolean matches(Domain candidateDomain, ResourceLocation candidate, Predicate<ResourceLocation> hasTag) {
        if (candidate == null || domain != candidateDomain) return false;
        boolean listed = ids.contains(candidate);
        if (!listed) {
            for (var tag : tags) {
                if (hasTag.test(tag)) { listed = true; break; }
            }
        }
        return allow == listed;
    }

    private static String text(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_filter_rule"); }
}
