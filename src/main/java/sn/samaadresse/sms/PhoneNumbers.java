package sn.samaadresse.sms;

import java.util.Optional;

/** Normalisation des numéros au format international E.164 (+221 par défaut pour le Sénégal). */
public final class PhoneNumbers {
    private PhoneNumbers() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[\\s.()-]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        // Indicatif sénégalais saisi sans « + » : 221 suivi des 9 chiffres nationaux.
        if (digits.matches("221[0-9]{9}")) {
            digits = "+" + digits;
        }
        // Numéro mobile sénégalais saisi sans indicatif : 9 chiffres commençant par 7.
        if (digits.matches("7[0-9]{8}")) {
            digits = "+221" + digits;
        }
        // Au Sénégal, un numéro compte exactement 9 chiffres ; seuls les mobiles (7x) reçoivent des SMS.
        if (digits.startsWith("+221")) {
            return digits.matches("\\+2217[0-9]{8}") ? Optional.of(digits) : Optional.empty();
        }
        return digits.matches("\\+[1-9][0-9]{7,14}") ? Optional.of(digits) : Optional.empty();
    }

    /** Numéro masqué pour les journaux et les réponses : +22177•••••67. */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 7) {
            return "•••";
        }
        return e164.substring(0, 6) + "•".repeat(e164.length() - 8) + e164.substring(e164.length() - 2);
    }
}
