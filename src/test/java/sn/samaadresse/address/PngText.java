package sn.samaadresse.address;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Ajoute un bloc tEXt juste après l'en-tête IHDR d'un PNG (données de test). */
final class PngText {
    private PngText() {
    }

    static byte[] withTextChunk(byte[] png, String key, String value) {
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        byte[] data = (key + "\0" + value).getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(type);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }
}
