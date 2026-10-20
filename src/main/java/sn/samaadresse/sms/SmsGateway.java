package sn.samaadresse.sms;

import java.util.Optional;

/** Fournisseur SMS configuré, ou vide si l'envoi automatique est désactivé. */
public record SmsGateway(Optional<SmsProvider> provider) {
}
