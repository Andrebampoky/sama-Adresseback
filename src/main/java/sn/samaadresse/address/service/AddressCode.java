package sn.samaadresse.address.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AddressCode {
    private static final Pattern FORMAT =
            Pattern.compile("[A-Z0-9]{2,6}(?:-[A-Z0-9]{2,6})?-[0-9]{4,8}");

    private AddressCode() {
    }

    public static String normalize(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Format de code d'adresse invalide.");
        }
        return code;
    }
}
