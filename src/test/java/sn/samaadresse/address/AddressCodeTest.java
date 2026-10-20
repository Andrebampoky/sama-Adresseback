package sn.samaadresse.address;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import sn.samaadresse.address.service.AddressCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddressCodeTest {
    @Test
    void trimsAndNormalizesPublicCodeWithoutDependingOnUserIdentity() {
        assertEquals("ZIG-KBG-4271", AddressCode.normalize(" zig-kbg-4271 "));
    }

    @Test
    void rejectsCodeOutsideDocumentedShape() {
        assertThrows(ResponseStatusException.class, () -> AddressCode.normalize("not-a-code"));
    }
}
