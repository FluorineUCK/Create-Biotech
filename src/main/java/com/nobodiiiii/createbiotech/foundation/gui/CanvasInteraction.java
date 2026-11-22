package com.nobodiiiii.createbiotech.foundation.gui;

import java.util.Objects;

/** Pointer gesture state shared by canvas screens; content and selection remain caller-owned. */
public final class CanvasInteraction<K> {
    private final CanvasViewport viewport;
    private K node;
    private boolean active;
    private boolean panning;
    private boolean moved;

    public CanvasInteraction(CanvasViewport viewport) {
        this.viewport = Objects.requireNonNull(viewport, "viewport");
    }

    public void beginNode(K node) {
        this.node = Objects.requireNonNull(node, "node");
        active = true;
        panning = false;
        moved = false;
    }

    public void beginPan() {
        node = null;
        active = true;
        panning = true;
        moved = false;
    }

    public boolean drag(double dx, double dy, NodeMover<K> mover) {
        if (!active)
            return false;
        if (dx == 0 && dy == 0)
            return true;
        if (panning) {
            viewport.pan(dx, dy);
        } else {
            Objects.requireNonNull(mover, "mover").moveBy(node, dx / viewport.scale(), dy / viewport.scale());
        }
        moved = true;
        return true;
    }

    public Release<K> release() {
        if (!active)
            return new Release<>(false, null);
        K clickedNode = !panning && !moved ? node : null;
        cancel();
        return new Release<>(true, clickedNode);
    }

    public void cancel() {
        node = null;
        active = false;
        panning = false;
        moved = false;
    }

    @FunctionalInterface
    public interface NodeMover<K> {
        void moveBy(K node, double dx, double dy);
    }

    public record Release<K>(boolean active, K clickedNode) {}
}
