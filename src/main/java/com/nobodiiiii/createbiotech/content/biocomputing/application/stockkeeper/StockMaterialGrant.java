package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import java.util.*;
import net.minecraft.nbt.*;

/** Explicit permission to extract and package, separate from catalogue publication. */
record StockMaterialGrant(long revision, String address, GameProfile grantor, SerialControlAccess.Binding source,
                          UUID installation, StockLinkTarget target) {
    StockMaterialGrant {
        if (revision <= 0 || !route(address).equals(address) || grantor.getName().isBlank() || grantor.getName().length() > 16)
            throw new IllegalArgumentException("invalid_material_grant");
    }
    static String route(String address) {
        String route = RecipeAddressRules.requireNormalized(address);
        if (route.chars().anyMatch(c -> "*?[]{}\\".indexOf(c) >= 0)
            || route.codePoints().anyMatch(c -> c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE))
            throw new IllegalArgumentException("invalid_address");
        return route;
    }
    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putLong("Revision", revision); tag.putString("Address", address);
        tag.putUUID("Grantor", grantor.getId()); tag.putString("Name", grantor.getName());
        tag.put("Source", source.save()); tag.putUUID("Installation", installation); tag.put("Target", target.save());
        return tag;
    }
    static StockMaterialGrant load(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Revision", "Address", "Grantor", "Name", "Source", "Installation", "Target"))
            || !tag.contains("Revision", Tag.TAG_LONG) || !tag.contains("Address", Tag.TAG_STRING)
            || !tag.hasUUID("Installation") || !tag.hasUUID("Grantor") || !tag.contains("Name", Tag.TAG_STRING)) throw new IllegalArgumentException("invalid_material_grant");
        return new StockMaterialGrant(tag.getLong("Revision"), tag.getString("Address"), new GameProfile(tag.getUUID("Grantor"), tag.getString("Name")),
            SerialControlAccess.Binding.load(tag.get("Source")), tag.getUUID("Installation"), StockLinkTarget.load(tag.get("Target")));
    }
}
