package sn.samaadresse.sms;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mode test (APP_SMS_PROVIDER=simulation) : n'envoie rien, journalise un numéro masqué et garde
 * les derniers messages en mémoire pour les tests. Le résultat est toujours SIMULATED.
 */
public class SimulatedSmsProvider implements SmsProvider {
    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsProvider.class);
    private final List<String[]> outbox = new ArrayList<>();

    @Override
    public String name() {
        return "simulation";
    }

    @Override
    public synchronized SmsResult send(String to, String body) {
        outbox.add(new String[] {to, body});
        if (outbox.size() > 50) {
            outbox.remove(0);
        }
        log.info("SMS simulé (non envoyé) vers {} : {} caractères", PhoneNumbers.mask(to), body.length());
        return new SmsResult(SmsResult.Status.SIMULATED, name(), null);
    }

    public synchronized List<String[]> outbox() {
        return List.copyOf(outbox);
    }
}
