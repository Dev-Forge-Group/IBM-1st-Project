package org.myweb.uniplace.domain.file.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.file.application.ContractFileAccessPolicy;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.file.repository.UploadFileRepository;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.response.ApiResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Batch deletion validates the stored targets before any member of the batch changes. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/owner/files")
public class OwnerFileController {
    private final OwnershipAccess ownership;
    private final UploadFileRepository files;
    private final ContractFileAccessPolicy access;
    private final FileService service;

    @DeleteMapping
    @Transactional
    public ApiResponse<Void> delete(@RequestBody(required = false) List<Integer> fileIds) {
        ownership.requireOwner();
        if (fileIds == null || fileIds.isEmpty()) return ApiResponse.ok();
        for (var file : files.findAllById(fileIds)) {
            String type = file.getFileParentType() == null ? "" : file.getFileParentType().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("CONTRACT", "ROOM", "BUILDING", "SPACE").contains(type)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            if ("CONTRACT".equals(type)) ownership.requireCanManageContract(file.getFileParentId());
            else access.requireWrite(type, file.getFileParentId());
        }
        service.softDeleteFiles(fileIds);
        return ApiResponse.ok();
    }
}
