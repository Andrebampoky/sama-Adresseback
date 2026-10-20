package sn.samaadresse.photo;

import java.util.Optional;

/**
 * Stockage des photos de façade. L'implémentation locale sert au développement ; en production,
 * fournir une implémentation persistante (stockage objet compatible S3, par exemple).
 */
public interface PhotoStorage {
    /** Enregistre l'image (JPEG déjà nettoyé) et renvoie sa clé de stockage, aléatoire. */
    String store(byte[] jpeg);

    Optional<byte[]> load(String key);

    void delete(String key);
}
