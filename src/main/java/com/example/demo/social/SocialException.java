package com.example.demo.social;

public class SocialException extends RuntimeException {
    private final String code;
    public SocialException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
