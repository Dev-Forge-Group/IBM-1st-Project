package org.myweb.uniplace.domain.reservation.domain.policy;

import java.util.regex.Pattern;

/** Comparison keys for the domestic mobile presentations accepted by the tour UI; stored text stays unchanged. */
public final class TourTelephone {
    private static final Pattern PRESENTATION = Pattern.compile("[0-9-]+");
    private static final Pattern MOBILE = Pattern.compile("01[016789][0-9]{7,8}");
    private static final Pattern PIN = Pattern.compile("[0-9]{4}");

    private TourTelephone() {}

    /** Unsupported legacy values retain their original repository comparison instead of gaining new aliases. */
    public static String canonical(String telephone) {
        if (telephone == null || telephone.length() > 20 || !PRESENTATION.matcher(telephone).matches()) {
            return null;
        }
        String digits = telephone.replace("-", "");
        return MOBILE.matcher(digits).matches() ? digits : null;
    }

    /** Direct repository consumers, including AI, must not bypass the public DTO's ASCII PIN requirement. */
    public static String canonicalForCredentials(String telephone, String pin) {
        return pin != null && PIN.matcher(pin).matches() ? canonical(telephone) : null;
    }
}
