package sn.samaadresse.sms;

import org.springframework.http.HttpStatus;

public class SmsException extends RuntimeException {
    private final HttpStatus status;

    public SmsException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
