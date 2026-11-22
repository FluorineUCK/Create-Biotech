package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.*;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.*;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.*;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.*;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
class PanelFinalRegressionTest {
    static UUID id(long id) { return new UUID(0,id); }
    static final FactoryPanelOpening OPENING = new FactoryPanelOpening(new SpaceAddress(Level.OVERWORLD,null,BlockPos.ZERO),id(1),id(2),id(3),id(4));
    @Test void currentReadViewRefreshesWithoutNavigationButCoalescesPendingAndNeverPollsDraft() {
        PanelReadRefresh refresh = new PanelReadRefresh();
        List<FactoryPanelView> requests = new ArrayList<>();
        for(int i=0;i<80;i++) refresh.tick(FactoryPanelView.TASKS, false, requests::add);
        assertFalse(requests.isEmpty(), "production status must not remain frozen while reading");
        assertTrue(requests.size() <= 4, "refresh must be paced");
        assertEquals(Set.of(FactoryPanelView.TASKS), Set.copyOf(requests));
        int before=requests.size();
        for(int i=0;i<80;i++) refresh.tick(FactoryPanelView.TASKS, true, requests::add);
        for(int i=0;i<80;i++) refresh.tick(FactoryPanelView.NEW_TASK, false, requests::add);
        assertEquals(before,requests.size());
    }
    @Test void tasksOneRowBudgetCanReachFifthGoal() {
        var state = new FactoryPanelViewState().forView(FactoryPanelView.TASKS);
        TasksView view = new TasksView(state);
        var projection = new TaskListProjection(id(3),id(4),1,0,TaskListProjection.Status.CURRENT,0,5,List.of(),Optional.empty(),1);
        assertTrue(view.accept(new TaskListResponsePacket(OPENING, projection,view.beginRequest()),OPENING));
        view.layoutPage(projection, 300,306,68,232);
        assertEquals(4,view.pageScrollbar().maximumOffset());
    }
    @Test void logisticsOneRowBudgetCanReachFourthComponent() {
        var state = new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_LOGISTICS);
        LogisticsNetworkView view = new LogisticsNetworkView(state);
        var projection = new LogisticsNetworkResponsePacket.Projection(id(3),id(4),0,
            LogisticsNetworkResponsePacket.Status.CURRENT,Optional.empty(),0,4,List.of(),List.of(),
            Set.of(com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.RemoteCapability.VIEW_LOGISTICS_DIRECTORY),1);
        assertTrue(view.accept(new LogisticsNetworkResponsePacket(OPENING,projection,view.beginRequest()),OPENING));
        view.layoutPage(projection,300,306,120,222);
        assertEquals(3,view.pageScrollbar().maximumOffset());
    }
    @Test void acceptedRequestCapacitySurvivesTailAndSupersededResponsesAndSupportsDragging() {
        var state=new FactoryPanelViewState().forView(FactoryPanelView.TASKS);
        TasksView view=new TasksView(state); view.prepareRequest(1);
        var first=new TaskListProjection(id(3),id(4),1,0,TaskListProjection.Status.CURRENT,0,5,List.of(),Optional.empty(),16);
        assertTrue(view.accept(new TaskListResponsePacket(OPENING,first,view.beginRequest()),OPENING));
        view.layoutPage(first,300,306,68,232);
        assertEquals(4,view.pageScrollbar().maximumOffset());
        view.pageScrollbar().mouseClicked(303,70,0,()->{});
        view.pageScrollbar().mouseDragged(232,0,()->{});
        view.pageScrollbar().mouseReleased(0);
        assertEquals(4,state.scrollOffset());
        var last=new TaskListProjection(id(3),id(4),1,0,TaskListProjection.Status.CURRENT,4,5,List.of(),Optional.empty(),16);
        long old=view.beginRequest(); view.prepareRequest(16); long newer=view.beginRequest();
        assertFalse(view.accept(new TaskListResponsePacket(OPENING,last,old),OPENING));
        view.layoutPage(first,300,306,68,232); assertEquals(4,view.pageScrollbar().maximumOffset());
        view.prepareRequest(1); long tail=view.beginRequest();
        assertTrue(view.accept(new TaskListResponsePacket(OPENING,last,tail),OPENING));
        view.layoutPage(last,300,306,68,232); assertEquals(4,view.pageScrollbar().maximumOffset());
        view.pageScrollbar().mouseClicked(303,69,0,()->{}); assertEquals(0,state.scrollOffset());
    }
    @Test void communicationBudgetSubtractsPinnedLocalFromRemoteGeometry() {
        var state = new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION);
        CommunicationNetworkView view = new CommunicationNetworkView(state);
        var local = ClusterCardProjection.cluster(id(3),ClusterServiceRole.STANDARD,ClusterCardProjection.FormationState.FORMED,
            ClusterCardProjection.OperationalState.ONLINE,0,1,0,0,ClusterCardProjection.LinkType.ADJACENT,
            CommunicationFrequency.UNCONFIGURED,List.of(),false,false);
        var projection = new CommunicationNetworkProjection(id(3),id(4),1,0,CommunicationNetworkProjection.Status.CURRENT,
            0,5,List.of(local),2,Optional.of(new LocalCommunicationEditProjection(0,List.of())));
        assertTrue(view.accept(new CommunicationNetworkResponsePacket(OPENING,projection,view.beginRequest()),OPENING));
        view.layoutPage(projection,300,306,68,178);
        assertEquals(3,view.pageScrollbar().maximumOffset());
    }
    @Test void staleListenerPageHasNoSelectableRowsWhileLatestReadIsOutstanding() {
        CommunicationNetworkView view = new CommunicationNetworkView(new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
        var stale = CommunicationNetworkProjection.stale(id(3), id(4), 1, 1, 1);
        assertTrue(view.accept(new CommunicationNetworkResponsePacket(OPENING, stale, view.beginRefreshRequest()), OPENING));
        assertTrue(view.selectableListeners().isEmpty());
    }
    @Test void treeBranchesAndExecutionLocationAreReadableAndContinuationKeepsParent() {
        var library=node(TreeNodeKind.LIBRARY); var handoff=node(TreeNodeKind.HANDOFF);
        assertNotEquals(PanelPresentation.tree(library),PanelPresentation.tree(handoff));
        String text=PanelPresentation.tree(library).toString();
        assertTrue(text.contains("Library West")); assertTrue(text.contains("01234567")); assertTrue(text.contains("slot"));
        var parent=new TreeNode(1,TreeNodeKind.PROCESS,"iron",2,"",Optional.empty(),ExecutionState.RUN,Optional.of("01234567"),1,Optional.empty(),Optional.of("kind=GOAL"));
        var tree=new GoalTreeProjection(id(8),"iron",2,3,5,Optional.of(parent),List.of(library));
        assertEquals(parent,PanelPresentation.treeRows(tree).getFirst());
    }
    static TreeNode node(TreeNodeKind kind) { return new TreeNode(2,kind,"iron",2,
        kind==TreeNodeKind.LIBRARY ? "Library West" : "", Optional.empty(),ExecutionState.RUN,
        Optional.of("01234567"),1,Optional.empty(),Optional.empty()); }
}
