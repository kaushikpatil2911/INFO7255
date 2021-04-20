package com.info7255.demo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
public class ETagParseException extends RuntimeException{
    public ETagParseException( String message ) {
        super(message);
    }
    public ETagParseException() {
    }
}
