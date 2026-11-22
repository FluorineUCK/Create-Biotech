package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Objects;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

/** A random opening identity prevents container-id reuse from reviving old requests. */
public record BrowserFence(UUID sessionId, RecipeSourceLocation coreAddress, UUID libraryId, long generation) {
    public BrowserFence {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(coreAddress); Objects.requireNonNull(libraryId);
        if (generation < 0) throw new IllegalArgumentException("negative browser generation");
    }
    public BrowserFence withGeneration(long generation) { return new BrowserFence(sessionId, coreAddress, libraryId, generation); }
    public boolean sameSession(BrowserFence other) {
        return sessionId.equals(other.sessionId) && coreAddress.equals(other.coreAddress) && libraryId.equals(other.libraryId);
    }
}
