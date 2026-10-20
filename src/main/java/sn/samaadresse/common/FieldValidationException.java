package sn.samaadresse.common;

import java.util.Map;

/** Erreur de validation métier rattachée à un ou plusieurs champs du formulaire. */
public class FieldValidationException extends RuntimeException {
    private final Map<String, String> errors;

    public FieldValidationException(Map<String, String> errors) {
        super("Certains champs sont invalides.");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
