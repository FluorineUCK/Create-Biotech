package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionPanelPublicationTest {
    @Test void failureWithdrawsOnlyNewPublicationsAndRetryDoesNotDuplicateExistingOnes() {
        var a=panel(1); var b=panel(2); var c=panel(3); var access=new Access();
        access.published.add(a); access.rejected=c;
        assertFalse(ProductionPanelPublication.publish(List.of(a,b,c),binding(true),access));
        assertEquals(Set.of(a),access.published); assertEquals(List.of(b),access.withdrawn);
        assertEquals(List.of(b,c),access.attempts);
        access.rejected=null;
        assertTrue(ProductionPanelPublication.publish(List.of(a,b,c),binding(true),access));
        assertEquals(Set.of(a,b,c),access.published); assertEquals(List.of(b,c,b,c),access.attempts);
        assertTrue(ProductionPanelPublication.publish(List.of(a,b,c),binding(true),access));
        assertEquals(4,access.attempts.size());
    }

    @Test void missingAuthorityNeverCallsTheWorldPortForNonEmptyPanels() {
        var access=new Access(); var panels=List.of(panel(1));
        assertFalse(ProductionPanelPublication.publish(panels,null,access));
        assertFalse(ProductionPanelPublication.publish(panels,binding(false),access));
        assertTrue(ProductionPanelPublication.publish(List.of(),null,access));
        assertEquals(0,access.lookups); assertTrue(access.attempts.isEmpty()); assertTrue(access.withdrawn.isEmpty());
    }

    private static ProductionLayout.Panel panel(int id) {
        return new ProductionLayout.Panel(new UUID(0,id),new ProductionLayout.Address(Level.OVERWORLD,null,new BlockPos(id,0,0)),
            Direction.NORTH,ComputerShellMaterial.OPAQUE);
    }
    private static ProductionBinding binding(boolean authority) {
        return new ProductionBinding(new UUID(1,1),0,authority
            ? new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR,new UUID(2,2)) : null,List.of());
    }
    private static final class Access implements ProductionPanelPublication.Access {
        final Set<ProductionLayout.Panel> published=new HashSet<>();
        final List<ProductionLayout.Panel> attempts=new ArrayList<>(), withdrawn=new ArrayList<>();
        ProductionLayout.Panel rejected; int lookups;
        public boolean published(ProductionLayout.Panel panel) { lookups++; return published.contains(panel); }
        public boolean publish(ProductionLayout.Panel panel) { attempts.add(panel); return !panel.equals(rejected) && published.add(panel); }
        public void withdraw(ProductionLayout.Panel panel) { withdrawn.add(panel); published.remove(panel); }
    }
}
