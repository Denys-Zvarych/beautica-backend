package com.beautica.auth;

public interface TokenGenerator {

    String generateToken();

    String hash(String rawToken);
}
