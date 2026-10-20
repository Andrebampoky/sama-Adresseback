package sn.samaadresse.photo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;
import sn.samaadresse.common.FieldValidationException;

/**
 * Vérifie qu'un fichier envoyé est vraiment une image JPEG ou PNG (signature binaire, pas
 * l'extension ni le type déclaré), puis la réencode en JPEG : les métadonnées (position GPS EXIF,
 * modèle d'appareil…) sont ainsi supprimées et l'image est limitée à 1600 px de côté.
 */
@Component
public class PhotoProcessor {
    public static final int MAX_SIDE = 1600;
    /** Protection contre les « bombes de décompression » : dimensions lues avant le décodage. */
    static final int MAX_SOURCE_PIXELS = 40_000_000;

    public byte[] sanitize(byte[] bytes) {
        if (!isJpeg(bytes) && !isPng(bytes)) {
            throw invalid("Format non pris en charge : envoyez une photo JPEG ou PNG.");
        }
        BufferedImage source = decode(bytes);
        BufferedImage target = resize(source);
        return encodeJpeg(target);
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (b.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (b[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("Image illisible ou endommagée.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_SOURCE_PIXELS) {
                    throw invalid("Image trop grande (40 mégapixels au maximum).");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof FieldValidationException validation) {
                throw validation;
            }
            throw invalid("Image illisible ou endommagée.");
        }
    }

    private static BufferedImage resize(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        // Copie RVB sans canal alpha (le JPEG n'en a pas) ; un fond blanc remplace la transparence.
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Échec d'encodage de la photo.", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static FieldValidationException invalid(String message) {
        return new FieldValidationException(Map.of("photo", message));
    }
}
