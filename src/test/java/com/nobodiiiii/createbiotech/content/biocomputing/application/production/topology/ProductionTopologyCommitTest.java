package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionTopologyCommitTest {
    @Test void successStagesTheWholeCohortBeforeNotifyingOrPublishing() {
        var f=new Fixture();
        assertEquals(ProductionTopologyCommit.Outcome.COMMITTED,ProductionTopologyCommit.commit(f.ports(),f));
        assertEquals(List.of(2,20),f.values()); assertEquals(List.of(List.of(2,20),List.of(2,20)),f.observations);
        assertFalse(f.a.published); assertTrue(f.b.published); assertEquals(1,f.publications);
        assertEquals(0,f.restorations); assertEquals(0,f.panelWithdrawals);
    }

    @Test void staleOrEmptyCohortHasNoStagingNotificationOrPublicationEffects() {
        var f=new Fixture(); f.b.matches=false;
        assertEquals(ProductionTopologyCommit.Outcome.STALE,ProductionTopologyCommit.commit(f.ports(),f));
        assertEquals(ProductionTopologyCommit.Outcome.STALE,ProductionTopologyCommit.commit(List.of(),f));
        assertEquals(List.of(1,10),f.values()); assertTrue(f.a.published); assertFalse(f.b.published);
        assertEquals(0,f.a.stages+f.b.stages+f.a.withdrawals+f.b.withdrawals+f.publications+f.restorations+f.panelWithdrawals);
        assertTrue(f.observations.isEmpty());
    }

    @Test void rejectedPublicationRestoresAllValuesBeforeNotificationsAndOnlyTheOldServices() {
        var f=new Fixture(); f.publicationAccepted=false;
        assertEquals(ProductionTopologyCommit.Outcome.ROLLED_BACK,ProductionTopologyCommit.commit(f.ports(),f));
        assertEquals(List.of(1,10),f.values()); assertTrue(f.a.published); assertFalse(f.b.published);
        assertFalse(f.a.unavailable || f.b.unavailable);
        assertEquals(1,f.a.republications); assertEquals(0,f.b.republications);
        assertEquals(List.of(List.of(2,20),List.of(2,20),List.of(1,10),List.of(1,10)),f.observations);
    }

    @Test void failedRestorationKeepsOldValuesButWithdrawsEveryService() {
        var f=new Fixture(); f.publicationAccepted=false; f.restorationAccepted=false;
        assertEquals(ProductionTopologyCommit.Outcome.RECOVERY_FAILED,ProductionTopologyCommit.commit(f.ports(),f));
        assertEquals(List.of(1,10),f.values());
        assertFalse(f.a.published); assertFalse(f.b.published);
        assertTrue(f.a.unavailable && f.b.unavailable); assertEquals(1,f.panelWithdrawals);
        assertEquals(1,f.a.republications); assertEquals(0,f.b.republications);
        assertEquals(List.of(List.of(2,20),List.of(2,20),List.of(1,10),List.of(1,10),List.of(1,10),List.of(1,10)),f.observations);
    }

    private static final class Fixture implements ProductionTopologyCommit.Publication {
        final Member a=new Member(this,1,2,true), b=new Member(this,10,20,false);
        final List<List<Integer>> observations=new ArrayList<>();
        boolean publicationAccepted=true, restorationAccepted=true;
        int publications, restorations, panelWithdrawals;
        List<ProductionTopologyCommit.Replica> ports() { return List.of(a,b); }
        List<Integer> values() { return List.of(a.value,b.value); }
        public boolean publish() { publications++; assertEquals(List.of(2,20),values()); b.published=true; return publicationAccepted; }
        public boolean restore() { restorations++; assertEquals(List.of(1,10),values()); return restorationAccepted; }
        public void withdraw() { panelWithdrawals++; }
    }
    private static final class Member implements ProductionTopologyCommit.Replica {
        final Fixture fixture; final int before, after;
        int value, stages, republications, withdrawals;
        boolean published, unavailable, matches=true;
        Member(Fixture fixture, int before, int after, boolean published) {
            this.fixture=fixture; this.before=before; this.after=after; this.value=before; this.published=published;
        }
        public boolean matchesExpected() { return matches; }
        public boolean published() { return published; }
        public boolean withdrawBeforeCommit() { return true; }
        public void withdraw() { published=false; withdrawals++; }
        public void stage() { stages++; value=after; }
        public void restore() { value=before; unavailable=false; }
        public void changed() { fixture.observations.add(fixture.values()); }
        public void republish() { republications++; published=true; }
        public void markUnavailable() { unavailable=true; }
    }
}
