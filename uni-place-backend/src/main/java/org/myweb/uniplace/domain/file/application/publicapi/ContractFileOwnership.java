package org.myweb.uniplace.domain.file.application.publicapi;

public interface ContractFileOwnership {
    boolean isOwner(Integer contractId, String userId);
}
