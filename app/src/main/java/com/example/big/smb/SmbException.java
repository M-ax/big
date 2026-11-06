package com.example.big.smb;

import java.io.IOException;

public final class SmbException extends IOException {
    public enum Kind { AUTHENTICATION, ACCESS_DENIED, NOT_FOUND, NETWORK, OTHER }

    public final Kind kind;

    public SmbException(Kind kind) {
        super(message(kind));
        this.kind = kind;
    }

    private static String message(Kind kind) {
        switch (kind) {
            case AUTHENTICATION:
                return "SMB authentication failed.";
            case ACCESS_DENIED:
                return "Access to this SMB location was denied.";
            case NOT_FOUND:
                return "The SMB location was not found.";
            case NETWORK:
                return "The SMB server could not be reached or timed out.";
            default:
                return "The SMB operation failed.";
        }
    }
}