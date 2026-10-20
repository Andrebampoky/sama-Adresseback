package sn.samaadresse.sms;

/**
 * Résultat d'un envoi. SIMULATED signifie qu'AUCUN SMS n'a été envoyé (mode test) : l'interface
 * doit l'afficher comme tel et ne jamais le présenter comme un envoi réel.
 */
public record SmsResult(Status status, String provider, String providerMessageId) {
    public enum Status { SENT, SIMULATED }
}
