package sn.samaadresse.photo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Photos enregistrées sur le disque du serveur (dossier ignoré par Git). Limite assumée : ce
 * stockage n'est ni répliqué ni sauvegardé ; il disparaît avec le conteneur s'il n'est pas monté
 * sur un volume persistant.
 */
@Component
public class LocalPhotoStorage implements PhotoStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalPhotoStorage.class);
    /** Les clés sont générées par le serveur : tout autre format est refusé (pas de chemin arbitraire). */
    private static final Pattern KEY = Pattern.compile("[0-9a-f-]{36}\\.jpg");

    private final Path directory;

    public LocalPhotoStorage(@Value("${app.photos.directory:./data/photos}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Dossier des photos inaccessible : " + this.directory, e);
        }
        log.info("Photos stockées localement dans {}", this.directory);
    }

    @Override
    public String store(byte[] jpeg) {
        String key = UUID.randomUUID() + ".jpg";
        try {
            Files.write(directory.resolve(key), jpeg);
        } catch (IOException e) {
            throw new UncheckedIOException("Échec d'écriture de la photo.", e);
        }
        return key;
    }

    @Override
    public Optional<byte[]> load(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        try {
            Path file = directory.resolve(key);
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de lecture de la photo.", e);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(key));
        } catch (IOException e) {
            // Fichier orphelin sans conséquence pour l'utilisateur : on journalise seulement.
            log.warn("Photo {} non supprimée : {}", key, e.getMessage());
        }
    }
}
