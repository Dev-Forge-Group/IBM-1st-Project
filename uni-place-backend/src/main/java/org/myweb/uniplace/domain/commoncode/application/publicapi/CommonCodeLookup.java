package org.myweb.uniplace.domain.commoncode.application.publicapi;

/** Read-only code existence for other bounded contexts; no entities or admin response DTOs cross this boundary. */
public interface CommonCodeLookup {
    /**
     * Whether the caller's canonical code is stored, including inactive codes and groups.
     * Callers retain their own normalization and fallback rules. Database errors propagate.
     */
    boolean exists(String canonicalCode);
}
