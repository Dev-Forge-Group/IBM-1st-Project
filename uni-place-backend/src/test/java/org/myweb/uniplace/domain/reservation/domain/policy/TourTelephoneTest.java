package org.myweb.uniplace.domain.reservation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TourTelephoneTest {
    @Test
    void supportedPresentationsShareOnlyADomesticMobileKey() {
        for (String value : List.of("01012345678", "010-1234-5678", "-0-1012--345678-")) {
            assertThat(TourTelephone.canonical(value)).isEqualTo("01012345678");
        }
        assertThat(TourTelephone.canonical("011-123-4567")).isEqualTo("0111234567");
        assertThat(TourTelephone.canonical("019-1234-5678")).isEqualTo("01912345678");
    }

    @Test
    void unsupportedLegacyTextDoesNotGainACanonicalAlias() {
        assertThat(TourTelephone.canonical(null)).isNull();
        for (String value : List.of("", "---", "legacy-id", "+821012345678", " 01012345678", "01012345678 ",
                "010 1234 5678", "010\t1234\t5678", "０１０１２３４５６７８", "010–1234–5678", "0212345678",
                "010123456789", "----------01012345678")) {
            assertThat(TourTelephone.canonical(value)).isNull();
        }
    }

    @Test
    void directCredentialConsumersRequireAnUnmodifiedAsciiFourDigitPinForNewAliases() {
        assertThat(TourTelephone.canonicalForCredentials("010-1234-5678", "0012")).isEqualTo("01012345678");
        assertThat(TourTelephone.canonicalForCredentials("010-1234-5678", null)).isNull();
        for (String pin : List.of("", "123", "12345", "１２３４", "1234 ", " 1234", "12-4", "1２34")) {
            assertThat(TourTelephone.canonicalForCredentials("010-1234-5678", pin)).isNull();
        }
    }
}
