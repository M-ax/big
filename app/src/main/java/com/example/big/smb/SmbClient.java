package com.example.big.smb;

import java.io.IOException;
import java.util.List;

public interface SmbClient extends AutoCloseable {
    List<SmbEntry> list(SmbLocation location) throws IOException;

    @Override
    void close() throws IOException;
}