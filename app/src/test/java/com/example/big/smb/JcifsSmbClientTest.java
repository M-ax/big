package com.example.big.smb;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jcifs.CIFSContext;
import jcifs.Configuration;
import jcifs.DialectVersion;
import jcifs.SmbConstants;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.NtStatus;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbNamedPipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JcifsSmbClientTest {
    @Test
    public void configRequiresSmb2HasBoundedTimeoutsAndNoFallback() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("alice", "secret", "WORKGROUP", false);
        try {
            Configuration config = context.getConfig();
            assertEquals(DialectVersion.SMB202, config.getMinimumVersion());
            assertEquals(DialectVersion.SMB210, config.getMaximumVersion());
            assertTrue(config.isUseSMB2OnlyNegotiation());
            assertFalse(config.isPort139FailoverEnabled());
            assertFalse(config.isAllowGuestFallback());
            assertTrue(config.isDisablePlainTextPasswords());
            assertTrue(config.isDfsDisabled());
            assertEquals(Arrays.asList(jcifs.ResolverType.RESOLVER_DNS), config.getResolveOrder());
            for (int timeout : new int[]{config.getConnTimeout(), config.getSoTimeout(),
                    config.getResponseTimeout(), config.getSessionTimeout()}) {
                assertTrue(timeout >= 8000 && timeout <= 12000);
            }
            assertNull(config.getDefaultUsername());
            assertNull(config.getDefaultPassword());
            assertFalse(context.getCredentials().isAnonymous());
            assertFalse(context.getCredentials().isGuest());
            assertFalse(context.renewCredentials("smb://nas/", authFailure(NtStatus.NT_STATUS_LOGON_FAILURE)));
        } finally {
            context.close();
        }
    }

    @Test
    public void guestIsExplicitAndContextsAreIndependent() throws Exception {
        CIFSContext guest = JcifsSmbClient.createContext("ignored", "ignored", "ignored", true);
        CIFSContext user = JcifsSmbClient.createContext("guest", "password", "DOMAIN", false);
        try {
            assertTrue(guest.getCredentials().isGuest());
            assertFalse(guest.getCredentials().isAnonymous());
            assertFalse(user.getCredentials().isGuest());
            assertFalse(user.getCredentials().isAnonymous());
            assertNotSame(user.getConfig(), guest.getConfig());
            assertNotSame(user.getTransportPool(), guest.getTransportPool());
            NtlmPasswordAuthenticator credentials = user.getCredentials().unwrap(NtlmPasswordAuthenticator.class);
            assertEquals("guest", credentials.getUsername());
            assertEquals("DOMAIN", credentials.getUserDomain());
        } finally {
            guest.close();
            user.close();
        }
        assertEquals(SmbException.Kind.AUTHENTICATION, assertThrows(SmbException.class,
                () -> new JcifsSmbClient("", "", "", false)).kind);
    }

    @Test
    public void jcifsRequiresLiteralPathsNotPlaybackEscapes() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        SmbLocation location = SmbLocation.parse("nas").child("Share % # + 日本", true)
                .child("Space % # + café", true).child("%2e%2e 🎬.mkv", false);
        try (SmbFile literal = JcifsSmbClient.createFile(location, context);
             SmbFile encoded = new SmbFile(location.getUrl(), context)) {
            assertEquals("Share % # + 日本", literal.getLocator().getShare());
            assertEquals("\\Space % # + café\\%2e%2e 🎬.mkv", literal.getLocator().getUNCPath());
            assertEquals("%2e%2e 🎬.mkv", literal.getName());
            assertNull(literal.getLocator().getURL().getUserInfo());
            assertNull(literal.getLocator().getURL().getQuery());
            assertNull(literal.getLocator().getURL().getRef());
            assertTrue(encoded.getLocator().getShare().contains("%25"));
        } finally {
            context.close();
        }
    }

    @Test
    public void shareEnumerationRpcKeepsTheExplicitServerPort() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        try (SmbFile root = JcifsSmbClient.createFile(SmbLocation.parse("nas:1445"), context);
             SmbNamedPipe pipe = new SmbNamedPipe("smb://nas/IPC$/srvsvc?address=127.0.0.1",
                     jcifs.SmbPipeResource.PIPE_TYPE_RDWR, root.getContext())) {
            assertEquals(1445, root.getLocator().getPort());
            assertEquals(1445, pipe.getLocator().getPort());
            assertTrue(pipe.getContext().getCredentials().isGuest());
        } finally {
            context.close();
        }
    }

    @Test
    public void sortsDirectoriesFirstWithCaseSensitiveTieBreakAndPreservesNames() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        TestFile[] files = new TestFile[]{new TestFile(context, "z.mp4", false),
                new TestFile(context, "beta", true), new TestFile(context, "a.MKV", false),
                new TestFile(context, "Alpha", true), new TestFile(context, "alpha", true),
                new TestFile(context, "A.mkv", false), new TestFile(context, "..", true),
                new TestFile(context, ".", true), new TestFile(context, "bad/name", true),
                new TestFile(context, "space % # + 日本.mkv", false),
                new TestFile(context, "0.txt", false), new TestFile(context, "z.wav", false),
                new TestFile(context, "Z.nfo", false)};
        try {
            List<SmbEntry> entries = JcifsSmbClient.readEntries(SmbLocation.parse("nas/share"), files);
            assertEquals(Arrays.asList("Alpha", "alpha", "beta", "A.mkv", "a.MKV",
                    "space % # + 日本.mkv", "z.mp4", "z.wav", "0.txt", "Z.nfo"),
                    entries.stream().map(entry -> entry.name).collect(Collectors.toList()));
            assertEquals(0, entries.get(0).size);
            assertEquals(123, entries.get(3).size);
            assertEquals("smb://nas/share/space%20%25%20%23%20%2B%20%E6%97%A5%E6%9C%AC.mkv",
                    entries.get(5).location.getUrl());
            for (TestFile file : files) {
                assertEquals(1, file.closeCount);
            }
        } finally {
            context.close();
        }
    }

    @Test
    public void naturallySortsShareAndFolderListings() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        try {
            try (SmbFile tenth = new SmbFile("smb://nas/Share10/", context);
                 SmbFile second = new SmbFile("smb://nas/Share2/", context)) {
                List<SmbEntry> shares = JcifsSmbClient.readEntries(SmbLocation.parse("nas"),
                        new SmbFile[]{tenth, second});
                assertEquals(Arrays.asList("Share2", "Share10"),
                        shares.stream().map(entry -> entry.name).collect(Collectors.toList()));
            }
            TestFile[] files = {new TestFile(context, "Folder10", true),
                    new TestFile(context, "Clip10.mp4", false), new TestFile(context, "notes10.txt", false),
                    new TestFile(context, "Folder2", true), new TestFile(context, "Clip2.mp4", false),
                    new TestFile(context, "notes2.txt", false)};
            List<SmbEntry> entries = JcifsSmbClient.readEntries(SmbLocation.parse("nas/share"), files);
            assertEquals(Arrays.asList("Folder2", "Folder10", "Clip2.mp4", "Clip10.mp4", "notes2.txt", "notes10.txt"),
                    entries.stream().map(entry -> entry.name).collect(Collectors.toList()));
            for (TestFile file : files) assertEquals(1, file.closeCount);
        } finally {
            context.close();
        }
    }

    @Test
    public void filtersNonDiskSharesAndNonFilesystemEntries() {
        SmbLocation root = SmbLocation.parse("nas");
        SmbLocation folder = root.child("share", true);
        assertTrue(JcifsSmbClient.isBrowsableType(root, SmbConstants.TYPE_SHARE));
        assertTrue(JcifsSmbClient.isBrowsableType(folder, SmbConstants.TYPE_FILESYSTEM));
        for (int type : new int[]{SmbConstants.TYPE_PRINTER, SmbConstants.TYPE_NAMED_PIPE,
                SmbConstants.TYPE_COMM, SmbConstants.TYPE_SERVER, SmbConstants.TYPE_WORKGROUP}) {
            assertFalse(JcifsSmbClient.isBrowsableType(root, type));
            assertFalse(JcifsSmbClient.isBrowsableType(folder, type));
        }
        assertFalse(JcifsSmbClient.isBrowsableType(root, SmbConstants.TYPE_FILESYSTEM));
    }

    @Test
    public void readsCachedShareTypesWithoutConnectingToEachShare() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        try {
            SmbFile[] shares = {new SmbFile("smb://nas/Media/", context),
                    new SmbFile("smb://nas/IPC$/", context), new SmbFile("smb://nas/Admin$/", context)};
            List<SmbEntry> entries = JcifsSmbClient.readEntries(SmbLocation.parse("nas"), shares);
            assertEquals(Arrays.asList("Admin$", "Media"),
                    entries.stream().map(entry -> entry.name).collect(Collectors.toList()));
            for (SmbEntry entry : entries) {
                assertTrue(entry.directory);
                assertEquals(0, entry.size);
                assertEquals(SmbLocation.parse("nas"), entry.location.parent());
            }
        } finally {
            context.close();
        }
    }

    @Test
    public void closesEveryEntryWhenMetadataFails() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        TestFile first = new TestFile(context, "first.mp4", false);
        TestFile broken = new TestFile(context, "broken.mp4", false);
        TestFile last = new TestFile(context, "last.mp4", false);
        broken.failMetadata = true;
        try {
            assertThrows(IOException.class, () -> JcifsSmbClient.readEntries(
                    SmbLocation.parse("nas/share"), new SmbFile[]{first, broken, last}));
            assertEquals(1, first.closeCount);
            assertEquals(1, broken.closeCount);
            assertEquals(1, last.closeCount);
        } finally {
            context.close();
        }
    }

    @Test
    public void continuesClosingRemainingEntriesWhenCloseThrows() throws Exception {
        CIFSContext context = JcifsSmbClient.createContext("", "", "", true);
        TestFile broken = new TestFile(context, "first.mp4", false);
        TestFile last = new TestFile(context, "last.mp4", false);
        broken.failClose = true;
        try {
            assertThrows(IllegalStateException.class, () -> JcifsSmbClient.readEntries(
                    SmbLocation.parse("nas/share"), new SmbFile[]{broken, last}));
            assertEquals(1, broken.closeCount);
            assertEquals(1, last.closeCount);
        } finally {
            context.close();
        }
    }

    @Test
    public void mapsFailuresWithoutKeepingSensitiveExceptionText() throws Exception {
        assertKind(SmbException.Kind.AUTHENTICATION, authFailure(NtStatus.NT_STATUS_LOGON_FAILURE));
        assertKind(SmbException.Kind.AUTHENTICATION, new jcifs.smb.SmbException(NtStatus.NT_STATUS_PASSWORD_EXPIRED, false));
        assertKind(SmbException.Kind.ACCESS_DENIED, authFailure(NtStatus.NT_STATUS_ACCESS_DENIED));
        assertKind(SmbException.Kind.ACCESS_DENIED, new jcifs.smb.SmbException(5, true));
        assertKind(SmbException.Kind.NOT_FOUND, new jcifs.smb.SmbException(NtStatus.NT_STATUS_BAD_NETWORK_NAME, false));
        assertKind(SmbException.Kind.NOT_FOUND, new jcifs.smb.SmbException(NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND, false));
        assertKind(SmbException.Kind.NETWORK, new SocketTimeoutException("secret"));
        assertKind(SmbException.Kind.NETWORK, new UnknownHostException("secret"));
        assertKind(SmbException.Kind.NETWORK, new jcifs.smb.SmbException("secret", new SocketTimeoutException("secret")));
        assertKind(SmbException.Kind.OTHER, new IOException("secret"));
    }

    @Test
    public void closeIsIdempotentAndListingAfterCloseFailsWithoutNetwork() throws Exception {
        JcifsSmbClient client = new JcifsSmbClient("", "", "", true);
        client.close();
        client.close();
        assertThrows(IOException.class, () -> client.list(SmbLocation.parse("nas/share")));
    }

    private static SmbAuthException authFailure(int status) throws Exception {
        Constructor<SmbAuthException> constructor = SmbAuthException.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(status);
    }

    private static void assertKind(SmbException.Kind kind, Throwable failure) {
        SmbException mapped = JcifsSmbClient.mapFailure(failure);
        assertEquals(kind, mapped.kind);
        assertFalse(mapped.toString().contains("secret"));
        assertNull(mapped.getCause());
        assertEquals(0, mapped.getSuppressed().length);
    }

    private static final class TestFile extends SmbFile {
        private final String name;
        private final boolean directory;
        private boolean failMetadata;
        private boolean failClose;
        private int closeCount;

        TestFile(CIFSContext context, String name, boolean directory) throws IOException {
            super("smb://nas/share/placeholder", context);
            this.name = name;
            this.directory = directory;
        }

        @Override
        public String getName() {
            return name + (directory ? "/" : "");
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public long length() throws jcifs.smb.SmbException {
            if (failMetadata) {
                throw new jcifs.smb.SmbException(NtStatus.NT_STATUS_ACCESS_DENIED, false);
            }
            return 123;
        }

        @Override
        public void close() {
            closeCount++;
            super.close();
            if (failClose) {
                throw new IllegalStateException("secret");
            }
        }
    }
}