package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Incremental publication of one captured epoch's checked panels, preserving services that existed before this call. */
public final class ProductionPanelPublication {
    private ProductionPanelPublication() {}
    /** Host-scoped to the captured binding and epoch; each operation still checks the actual physical panel. */
    public interface Access {
        boolean published(ProductionLayout.Panel panel);
        boolean publish(ProductionLayout.Panel panel);
        void withdraw(ProductionLayout.Panel panel);
    }
    public static boolean publish(List<ProductionLayout.Panel> panels, @Nullable ProductionBinding binding, Access access) {
        panels=List.copyOf(panels); Objects.requireNonNull(access);
        if (binding==null || binding.authority()==null) return panels.isEmpty();
        var added=new ArrayList<ProductionLayout.Panel>();
        for (var panel : panels) {
            if (access.published(panel)) continue;
            if (access.publish(panel)) added.add(panel);
            else {
                added.forEach(access::withdraw);
                return false;
            }
        }
        return true;
    }
}
