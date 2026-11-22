package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

/** Client session layout only. Each edge owns one ingredient occurrence, never deduplicated by selector. */
public final class PatternRecipeGraph {
    public static final int NODE_WIDTH=100, NODE_HEIGHT=38;
    public enum Kind { UNASSIGNED, MATERIAL, OUTPUT, STAGE, COMMON }
    public static final class Node {
        public final long id;
        public Kind kind;
        public int index;
        public double x, y;
        private PatternIngredient material;
        private Node(long id, Kind kind, int index, double x, double y, PatternIngredient material) {
            this.id=id; this.kind=kind; this.index=index; this.x=x; this.y=y; this.material=material;
        }
        public PatternIngredient material() { return material; }
    }
    public record Edge(long materialId, int stage) {}
    private final PatternRecipeDraft draft;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private long nextId;
    public PatternRecipeGraph(PatternRecipeDraft draft) {
        this.draft=draft;
        node(Kind.COMMON,-1,210,50,null);
        for(int stage=-1;stage<draft.stages().size();stage++) {
            if(stage>=0) node(Kind.STAGE,stage,210+stage*140,170,null);
            List<PatternIngredient> inputs=draft.inputs(stage);
            for(int i=0;i<inputs.size();i++) {
                long id=addMaterial(inputs.get(i),30+Math.max(0,stage)*140,30+i*60+(stage<0?0:230));
                edges.add(new Edge(id,stage));
            }
        }
        syncOutputs();
    }
    private Node node(Kind kind,int index,double x,double y,PatternIngredient material) {
        Node node=new Node(++nextId,kind,index,x,y,material); nodes.add(node); return node;
    }
    public List<Node> nodes() { return List.copyOf(nodes); }
    public List<Edge> edges() { return List.copyOf(edges); }
    public Node get(long id) { return nodes.stream().filter(n->n.id==id).findFirst().orElse(null); }
    public Node sink(int stage) { return nodes.stream().filter(n->stage<0?n.kind==Kind.COMMON:n.kind==Kind.STAGE&&n.index==stage).findFirst().orElse(null); }
    public long addNode(double x,double y) { return node(Kind.UNASSIGNED,-1,x,y,null).id; }
    private Node configurable(long id,Kind kind) {
        Node node=Objects.requireNonNull(get(id));
        if(node.kind!=Kind.UNASSIGNED&&node.kind!=kind)throw new IllegalArgumentException("Cannot change a configured node's role");
        return node;
    }
    public void configureMaterial(long id,PatternIngredient value) {
        Node node=configurable(id,Kind.MATERIAL);Objects.requireNonNull(value);
        // Each connection owns its amount; changing the source selector must not flatten them.
        for(Edge edge:edges)if(edge.materialId==id) {
            int index=inputIndex(edge),count=draft.inputs(edge.stage).get(index).count();
            draft.setInput(edge.stage,index,new PatternIngredient(value.itemId(),value.tagId(),count,value.components()));
        }
        node.material=value;node.kind=Kind.MATERIAL;
    }
    public void configureOutput(long id,PatternOutput value) {
        Node node=configurable(id,Kind.OUTPUT);Objects.requireNonNull(value);
        int index=node.kind==Kind.UNASSIGNED?draft.outputs().size():node.index;
        draft.setOutput(index,value);node.index=index;node.kind=Kind.OUTPUT;
    }
    public void configureStage(long id,String address) {
        Node node=configurable(id,Kind.STAGE);
        if(node.kind==Kind.UNASSIGNED) {
            draft.addStage(address);node.index=draft.stages().size()-1;node.kind=Kind.STAGE;
        } else draft.setAddress(node.index,address);
    }
    public void removeNode(long id) {
        Node node=get(id);if(node==null)return;
        switch(node.kind) {
            case UNASSIGNED -> nodes.remove(node);
            case MATERIAL -> removeMaterial(id);
            case OUTPUT -> removeOutput(node.index);
            case STAGE -> removeStage(node.index);
            case COMMON -> { }
        }
    }
    public long addMaterial(PatternIngredient ingredient,double x,double y) { return node(Kind.MATERIAL,-1,x,y,Objects.requireNonNull(ingredient)).id; }
    public void move(long id,double x,double y) {
        if(!Double.isFinite(x)||!Double.isFinite(y)) throw new IllegalArgumentException("Non-finite node position");
        Node node=Objects.requireNonNull(get(id)); node.x=x; node.y=y;
    }
    public boolean connect(long materialId,int stage) {
        Node material=get(materialId);
        if(material==null||material.kind!=Kind.MATERIAL||sink(stage)==null||edges.contains(new Edge(materialId,stage))) return false;
        try { draft.setInput(stage,draft.inputs(stage).size(),material.material); }
        catch (IllegalArgumentException rejected) { return false; }
        edges.add(new Edge(materialId,stage)); return true;
    }
    public int inputIndex(Edge edge) {
        int index=0; for(Edge current:edges) { if(current.equals(edge)) return index; if(current.stage==edge.stage) index++; }
        return -1;
    }
    public void disconnect(long materialId,int stage) {
        Edge edge=new Edge(materialId,stage); int index=inputIndex(edge);
        if(index<0) return;
        draft.removeInput(stage,index); edges.remove(edge);
    }
    public void removeMaterial(long id) {
        for(Edge edge:List.copyOf(edges)) if(edge.materialId==id) disconnect(id,edge.stage);
        nodes.removeIf(n->n.id==id&&n.kind==Kind.MATERIAL);
    }
    public void setInputCount(Edge edge,int amount) {
        int index=inputIndex(edge); if(index>=0) draft.setInputCount(edge.stage,index,amount);
    }
    public Node nodeAt(double x,double y) {
        for(int i=nodes.size()-1;i>=0;i--) {
            Node node=nodes.get(i);
            if(x>=node.x&&x<node.x+NODE_WIDTH&&y>=node.y&&y<node.y+NODE_HEIGHT)return node;
        }
        return null;
    }
    public Edge edgeAt(double x,double y,double tolerance) {
        for(Edge edge:edges) {
            Node source=get(edge.materialId),sink=sink(edge.stage);
            double ax=source.x+NODE_WIDTH,ay=source.y+NODE_HEIGHT/2d,bx=sink.x,by=sink.y+NODE_HEIGHT/2d,mid=(ax+bx)/2;
            if(nearLine(x,y,ax,ay,mid,ay,tolerance)||nearLine(x,y,mid,ay,mid,by,tolerance)||nearLine(x,y,mid,by,bx,by,tolerance))return edge;
        }
        return null;
    }
    private static boolean nearLine(double x,double y,double ax,double ay,double bx,double by,double tolerance) {
        return x>=Math.min(ax,bx)-tolerance&&x<=Math.max(ax,bx)+tolerance&&y>=Math.min(ay,by)-tolerance&&y<=Math.max(ay,by)+tolerance;
    }
    public void syncOutputs() {
        nodes.removeIf(n->n.kind==Kind.OUTPUT&&n.index>=draft.outputs().size());
        for(int i=0;i<draft.outputs().size();i++) {
            int index=i;
            if(nodes.stream().noneMatch(n->n.kind==Kind.OUTPUT&&n.index==index)) node(Kind.OUTPUT,i,390,30+i*64,null);
        }
    }
    public void removeOutput(int index) {
        draft.removeOutput(index); nodes.removeIf(n->n.kind==Kind.OUTPUT&&n.index==index);
        nodes.stream().filter(n->n.kind==Kind.OUTPUT&&n.index>index).forEach(n->n.index--);
    }
    public void addStage() { draft.addStage(); node(Kind.STAGE,draft.stages().size()-1,210+(draft.stages().size()-1)*140,170,null); }
    public void moveStage(int from,int to) {
        draft.moveStage(from,to);
        for(Node node:nodes) if(node.kind==Kind.STAGE) { if(node.index==from)node.index=to;else if(node.index==to)node.index=from; }
        for(int i=0;i<edges.size();i++) { Edge edge=edges.get(i); if(edge.stage==from||edge.stage==to) edges.set(i,new Edge(edge.materialId,edge.stage==from?to:from)); }
    }
    public void removeStage(int stage) {
        if(draft.stages().size()==1)draft.setAddress(-1,draft.address(stage));
        draft.removeStage(stage);
        nodes.removeIf(n->n.kind==Kind.STAGE&&n.index==stage);
        nodes.stream().filter(n->n.kind==Kind.STAGE&&n.index>stage).forEach(n->n.index--);
        edges.removeIf(e->e.stage==stage);
        for(int i=0;i<edges.size();i++) { Edge edge=edges.get(i); if(edge.stage>stage) edges.set(i,new Edge(edge.materialId,edge.stage-1)); }
        if(draft.stages().isEmpty()) draft.setSequenced(false);
    }
}

