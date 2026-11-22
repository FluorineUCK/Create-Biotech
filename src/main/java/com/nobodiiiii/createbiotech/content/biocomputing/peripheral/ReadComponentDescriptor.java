package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.TypeArchiveCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public record ReadComponentDescriptor(String id, String translationKey, Set<String> targetKinds,
    DataType valueType, UpdateHint updateHint, boolean additional) {
    public enum UpdateHint { EXPLICIT, BLOCK_UPDATE, TICK }

    public ReadComponentDescriptor {
        Objects.requireNonNull(id);
        Objects.requireNonNull(translationKey);
        Objects.requireNonNull(targetKinds);
        Objects.requireNonNull(valueType);
        Objects.requireNonNull(updateHint);
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (!id.contains(":") || parsed == null || !parsed.toString().equals(id))
            throw new IllegalArgumentException("Component id must be a namespaced resource location");
        if (translationKey.isBlank()) throw new IllegalArgumentException("Component label must not be blank");
        if (targetKinds.isEmpty() || targetKinds.stream().anyMatch(kind -> kind == null || kind.isBlank()))
            throw new IllegalArgumentException("Component target kinds must be nonblank and nonempty");
        targetKinds = Set.copyOf(targetKinds);
    }

    public DataValue.RecordValue describe() {
        List<DataValue> targets = targetKinds.stream().sorted().map(DataValue.TextValue::new)
            .map(DataValue.class::cast).toList();
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("id", new DataValue.TextValue(id));
        fields.put("label", new DataValue.TextValue(translationKey));
        fields.put("target_kinds", new DataValue.ListValue(targets));
        fields.put("type", TypeArchiveCodec.encode(valueType));
        fields.put("update_hint", new DataValue.TextValue(updateHint.name().toLowerCase(Locale.ROOT)));
        fields.put("additional", new DataValue.BooleanValue(additional));
        return new DataValue.RecordValue(fields);
    }
}
