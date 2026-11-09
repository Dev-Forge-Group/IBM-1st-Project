package org.myweb.uniplace.domain.commoncode.application;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.commoncode.application.publicapi.CommonCodeLookup;
import org.myweb.uniplace.domain.commoncode.repository.CommonCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonCodeLookupImpl implements CommonCodeLookup {
    private final CommonCodeRepository commonCodeRepository;

    @Override
    public boolean exists(String canonicalCode) {
        return commonCodeRepository.existsByCode(canonicalCode);
    }
}
