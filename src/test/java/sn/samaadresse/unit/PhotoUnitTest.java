package sn.samaadresse.unit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sn.samaadresse.common.FieldValidationException;
import sn.samaadresse.photo.LocalPhotoStorage;
import sn.samaadresse.photo.PhotoProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Traitement et stockage des photos, sans Spring. */
class PhotoUnitTest {
    private final PhotoProcessor processor = new PhotoProcessor();

    private static byte[] image(String format, int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void reencodesJpegAndPngAndKeepsSmallImagesSize() throws Exception {
        BufferedImage fromPng = ImageIO.read(new ByteArrayInputStream(processor.sanitize(image("png", 800, 600))));
        assertThat(fromPng.getWidth()).isEqualTo(800);
        BufferedImage fromJpeg = ImageIO.read(new ByteArrayInputStream(processor.sanitize(image("jpg", 1000, 2400))));
        assertThat(fromJpeg.getHeight()).isEqualTo(PhotoProcessor.MAX_SIDE);
        assertThat(fromJpeg.getWidth()).isEqualTo(667);
    }

    @Test
    void refusesOtherFormatsAndCorruptedFiles() throws Exception {
        assertThat(assertThrows(FieldValidationException.class, () -> processor.sanitize(image("gif", 10, 10)))
                .getErrors()).containsKey("photo");
        assertThrows(FieldValidationException.class, () -> processor.sanitize(new byte[] {1, 2}));
        byte[] png = image("png", 10, 10);
        byte[] truncated = java.util.Arrays.copyOf(png, 20);
        assertThrows(FieldValidationException.class, () -> processor.sanitize(truncated));
    }

    @Test
    void localStorageStoresLoadsDeletesAndRejectsForeignKeys(@TempDir Path dir) throws Exception {
        LocalPhotoStorage storage = new LocalPhotoStorage(dir.resolve("photos").toString());
        String key = storage.store(new byte[] {1, 2, 3});
        assertThat(key).matches("[0-9a-f-]{36}\\.jpg");
        assertThat(storage.load(key)).contains(new byte[] {1, 2, 3});
        // Aucun chemin arbitraire : « ../ » ou une clé inconnue ne lisent rien.
        Files.writeString(dir.resolve("secret.txt"), "x");
        assertThat(storage.load("../secret.txt")).isEmpty();
        assertThat(storage.load(null)).isEmpty();
        assertThat(storage.load(UUID.randomUUID() + ".jpg")).isEmpty();
        storage.delete(key);
        storage.delete("../secret.txt");
        storage.delete(null);
        assertThat(storage.load(key)).isEmpty();
        assertThat(Files.exists(dir.resolve("secret.txt"))).isTrue();
    }
}
