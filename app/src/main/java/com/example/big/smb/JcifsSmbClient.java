package com.example.big.smb;

import com.example.big.playback.SeekWindowCache;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.SmbConstants;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.CIFSContextWrapper;
import jcifs.smb.Handler;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.NtStatus;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;
import jcifs.util.transport.TransportException;

public final class JcifsSmbClient implements SmbClient {
    private static final Comparator<SmbEntry> ENTRY_ORDER = SmbEntry.BROWSER_ORDER;

    private CIFSContext context;

    public JcifsSmbClient(String username, String password, String domain, boolean guest) throws IOException {
        context = createContext(username, password, domain, guest);
    }

    static CIFSContext createContext(String username, String password, String domain, boolean guest) throws IOException {
        if (!guest && (username == null || username.trim().isEmpty())) {
            throw new SmbException(SmbException.Kind.AUTHENTICATION);
        }
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB210");
        properties.setProperty("jcifs.smb.client.useSMB2Negotiation", "true");
        properties.setProperty("jcifs.smb.client.port139.enabled", "false");
        properties.setProperty("jcifs.smb.client.allowGuestFallback", "false");
        properties.setProperty("jcifs.smb.client.disablePlainTextPasswords", "true");
        properties.setProperty("jcifs.smb.client.connTimeout", "10000");
        properties.setProperty("jcifs.smb.client.soTimeout", "10000");
        properties.setProperty("jcifs.smb.client.responseTimeout", "10000");
        properties.setProperty("jcifs.smb.client.sessionTimeout", "10000");
        properties.setProperty("jcifs.smb.client.maxRequestRetries", "1");
        properties.setProperty("jcifs.smb.client.dfs.disabled", "true");
        properties.setProperty("jcifs.resolveOrder", "DNS");
        BaseContext base = null;
        try {
            base = new BaseContext(new PropertyConfiguration(properties));
            CIFSContext authenticated = guest ? base.withGuestCrendentials()
                    : base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password,
                            NtlmPasswordAuthenticator.AuthenticationType.USER));
            return new CIFSContextWrapper(authenticated) {
                @Override
                public boolean renewCredentials(String locationHint, Throwable error) {
                    return false;
                }
            };
        } catch (IOException | RuntimeException exception) {
            if (base != null) {
                try {
                    base.close();
                } catch (IOException | RuntimeException ignored) {
                }
            }
            throw mapFailure(exception);
        }
    }

    @Override
    public synchronized List<SmbEntry> list(SmbLocation location) throws IOException {
        if (context == null || location == null || !location.isDirectory()) {
            throw new SmbException(SmbException.Kind.OTHER);
        }
        try (SmbFile directory = createFile(location, context)) {
            return readEntries(location, directory.listFiles());
        } catch (IOException | RuntimeException exception) {
            throw mapFailure(exception);
        }
    }

    static SmbFile createFile(SmbLocation location, CIFSContext context) throws MalformedURLException {
        CIFSContext locationContext = new PortContext(context, location.getPort());
        return new SmbFile(new LiteralPathHandler(locationContext).createUrl(location), locationContext);
    }

    /** The returned reader owns this client; closing it also closes the authenticated context. */
    public synchronized SeekWindowCache.Source openSource(SmbLocation location) throws IOException {
        if (context == null || location == null || location.isDirectory()) {
            throw new SmbException(SmbException.Kind.OTHER);
        }
        SmbFile file = createFile(location, context);
        SmbRandomAccessFile input = null;
        try {
            input = file.openRandomAccess("r");
            long size = input.length();
            SmbRandomAccessFile reader = input;
            return new SeekWindowCache.Source() {
                private boolean closed;

                @Override
                public long length() {
                    return size;
                }

                @Override
                public synchronized int read(long offset, byte[] buffer, int offsetInBuffer, int count)
                        throws IOException {
                    if (closed) {
                        throw new IOException("Reader closed");
                    }
                    try {
                        reader.seek(offset);
                        return reader.read(buffer, offsetInBuffer, count);
                    } catch (IOException | RuntimeException exception) {
                        throw mapFailure(exception);
                    }
                }

                @Override
                public synchronized void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        try {
                            reader.close();
                        } finally {
                            try {
                                file.close();
                            } finally {
                                JcifsSmbClient.this.close();
                            }
                        }
                    }
                }
            };
        } catch (IOException | RuntimeException exception) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            file.close();
            throw mapFailure(exception);
        }
    }

    static List<SmbEntry> readEntries(SmbLocation location, SmbFile[] files) throws IOException {
        List<SmbEntry> entries = new ArrayList<>();
        try {
            for (SmbFile file : files) {
                if (!isBrowsableType(location, file.getLocator().getType())) {
                    continue;
                }
                String name = file.getName();
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                    continue;
                }
                boolean directory = location.isServerRoot() || file.isDirectory();
                SmbLocation child;
                try {
                    child = location.child(name, directory);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                entries.add(new SmbEntry(name, directory, child, directory ? 0 : file.length()));
            }
            Collections.sort(entries, ENTRY_ORDER);
            return entries;
        } finally {
            RuntimeException failure = null;
            for (SmbFile file : files) {
                try {
                    file.close();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static boolean isBrowsableType(SmbLocation location, int type) {
        return type == (location.isServerRoot() ? SmbConstants.TYPE_SHARE : SmbConstants.TYPE_FILESYSTEM);
    }

    @Override
    public synchronized void close() throws IOException {
        CIFSContext closing = context;
        context = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (IOException | RuntimeException exception) {
                throw mapFailure(exception);
            }
        }
    }

    static SmbException mapFailure(Throwable failure) {
        SmbException.Kind kind = SmbException.Kind.OTHER;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SmbException) {
                return new SmbException(((SmbException) cause).kind);
            }
            if (cause instanceof jcifs.smb.SmbException) {
                int status = ((jcifs.smb.SmbException) cause).getNtStatus();
                switch (status) {
                    case NtStatus.NT_STATUS_ACCESS_DENIED:
                    case NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED:
                    case 5:
                        return new SmbException(SmbException.Kind.ACCESS_DENIED);
                    case NtStatus.NT_STATUS_NO_SUCH_USER:
                    case NtStatus.NT_STATUS_WRONG_PASSWORD:
                    case NtStatus.NT_STATUS_LOGON_FAILURE:
                    case NtStatus.NT_STATUS_ACCOUNT_RESTRICTION:
                    case NtStatus.NT_STATUS_INVALID_LOGON_HOURS:
                    case NtStatus.NT_STATUS_INVALID_WORKSTATION:
                    case NtStatus.NT_STATUS_PASSWORD_EXPIRED:
                    case NtStatus.NT_STATUS_PASSWORD_MUST_CHANGE:
                    case NtStatus.NT_STATUS_ACCOUNT_DISABLED:
                    case NtStatus.NT_STATUS_ACCOUNT_LOCKED_OUT:
                    case NtStatus.NT_STATUS_LOGON_TYPE_NOT_GRANTED:
                        return new SmbException(SmbException.Kind.AUTHENTICATION);
                    case NtStatus.NT_STATUS_NO_SUCH_FILE:
                    case NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND:
                    case NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND:
                    case NtStatus.NT_STATUS_BAD_NETWORK_NAME:
                    case NtStatus.NT_STATUS_NOT_FOUND:
                    case NtStatus.NT_STATUS_NOT_A_DIRECTORY:
                    case 2:
                    case 3:
                        return new SmbException(SmbException.Kind.NOT_FOUND);
                    case NtStatus.NT_STATUS_CONNECTION_REFUSED:
                    case NtStatus.NT_STATUS_NETWORK_NAME_DELETED:
                    case NtStatus.NT_STATUS_PORT_DISCONNECTED:
                    case 0xC00000B5:
                    case 0xC000023C:
                    case 0xC000023D:
                        kind = SmbException.Kind.NETWORK;
                        break;
                    default:
                        break;
                }
            }
            if (cause instanceof SmbAuthException) {
                return new SmbException(SmbException.Kind.AUTHENTICATION);
            }
            if (cause instanceof SocketException || cause instanceof UnknownHostException
                    || cause instanceof InterruptedIOException || cause instanceof EOFException
                    || cause instanceof TransportException) {
                kind = SmbException.Kind.NETWORK;
            }
        }
        return new SmbException(kind);
    }

    private static final class PortContext extends CIFSContextWrapper {
        private final int port;
        private final Handler handler;

        PortContext(CIFSContext context, int port) {
            super(context);
            this.port = port;
            // SRVSVC builds its own pipe URL without the server's explicit port.
            handler = new Handler(this) {
                @Override
                protected int getDefaultPort() {
                    return port;
                }
            };
        }

        @Override
        public URLStreamHandler getUrlHandler() {
            return handler;
        }

        @Override
        protected CIFSContext wrap(CIFSContext newContext) {
            return new PortContext(newContext, port);
        }

        @Override
        public boolean renewCredentials(String locationHint, Throwable error) {
            return false;
        }
    }

    private static final class LiteralPathHandler extends Handler {
        LiteralPathHandler(CIFSContext context) {
            super(context);
        }

        URL createUrl(SmbLocation location) throws MalformedURLException {
            URL url = new URL(null, location.getUrl(), this);
            // jcifs-ng 2.1.10 uses URL.getPath() verbatim, without percent-decoding.
            setURL(url, "smb", url.getHost(), url.getPort(), url.getAuthority(), null,
                    location.getDecodedPath(), null, null);
            return url;
        }
    }
}