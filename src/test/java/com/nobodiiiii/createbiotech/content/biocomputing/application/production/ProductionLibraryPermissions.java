package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

/** Additional local-edit restriction of the production application, not generic Cluster membership. */
public final class ProductionLibraryPermissions {
    private ProductionLibraryPermissions() {}

    public static boolean mayEdit(boolean bindingValid, Collection<UUID> networks, Predicate<UUID> administrator) {
        return bindingValid && networks.stream().allMatch(administrator);
    }
}
