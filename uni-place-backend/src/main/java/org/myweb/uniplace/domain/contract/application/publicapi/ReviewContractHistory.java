package org.myweb.uniplace.domain.contract.application.publicapi;

/** Contract history used for review creation; existence is independent of contract dates. */
public interface ReviewContractHistory {
    boolean hasActiveOrEndedContract(String userId, Integer roomId);
}
