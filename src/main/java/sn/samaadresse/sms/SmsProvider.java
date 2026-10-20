package sn.samaadresse.sms;

/** Fournisseur d'envoi de SMS (Twilio, simulation…). */
public interface SmsProvider {
    String name();

    /** Envoie le message ; lève SmsException si le fournisseur refuse ou est injoignable. */
    SmsResult send(String to, String body);
}
