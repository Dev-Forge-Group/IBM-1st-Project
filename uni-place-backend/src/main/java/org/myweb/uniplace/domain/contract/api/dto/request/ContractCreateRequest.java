package org.myweb.uniplace.domain.contract.api.dto.request;

import java.time.LocalDate;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractCreateRequest {

    @NotNull(message = "roomId는 필수입니다.")
    private Integer roomId;

    @NotNull(message = "contractStart는 필수입니다.")
    private LocalDate contractStart;

    @NotNull(message = "contractEnd는 필수입니다.")
    private LocalDate contractEnd;

    @NotNull(message = "paymentDay는 필수입니다.")
    @Min(value = 1, message = "paymentDay는 1~31 사이의 숫자여야 합니다.")
    @Max(value = 31, message = "paymentDay는 1~31 사이의 숫자여야 합니다.")
    private Integer paymentDay;

    // ✅ 회원 입력(임대인 영역)
    @NotBlank(message = "lessorAddr는 필수입니다.")
    private String lessorAddr;

    @NotBlank(message = "lessorRrn는 필수입니다.")
    private String lessorRrn;

    @NotBlank(message = "lessorTel은 필수입니다.")
    private String lessorTel;

    @NotBlank(message = "lessorNm은 필수입니다.")
    private String lessorNm;

    // ✅ 서명/날인(이미지)
    @NotNull(message = "signFile은 필수입니다.")
    private MultipartFile signFile;
}
