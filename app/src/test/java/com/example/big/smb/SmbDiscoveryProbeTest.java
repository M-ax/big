package com.example.big.smb;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmbDiscoveryProbeTest {
    @Test
    public void candidatesAreAscendingAndExcludeNetworkBroadcastAndSelf() throws Exception {
        InetAddress local = ipv4(192, 168, 7, 129);
        List<InetAddress> result = SmbDiscoveryProbe.candidates(local, 24);
        assertEquals(253, result.size());
        int index = 0;
        for (int host = 1; host < 255; host++) {
            if (host != 129) {
                assertEquals(ipv4(192, 168, 7, host), result.get(index++));
            }
        }
        assertEquals(result, SmbDiscoveryProbe.candidates(local, 24));
    }

    @Test
    public void largeSubnetsStayInsideLocalContaining24() throws Exception {
        InetAddress local = ipv4(10, 234, 129, 42);
        List<InetAddress> expected = SmbDiscoveryProbe.candidates(local, 24);
        for (int prefix = 0; prefix < 24; prefix++) {
            assertEquals("prefix " + prefix, expected,
                    SmbDiscoveryProbe.candidates(local, prefix));
        }
    }

    @Test
    public void smallerSubnetsUseTheirActualNetworkAndBroadcast() throws Exception {
        assertEquals(Arrays.asList(ipv4(172, 16, 5, 130)),
                SmbDiscoveryProbe.candidates(ipv4(172, 16, 5, 129), 30));
        List<InetAddress> result = SmbDiscoveryProbe.candidates(ipv4(192, 168, 1, 200), 25);
        assertEquals(125, result.size());
        assertEquals(ipv4(192, 168, 1, 129), result.get(0));
        assertEquals(ipv4(192, 168, 1, 254), result.get(result.size() - 1));
    }

    @Test
    public void allUsablePrefixesRespectHostCounts() throws Exception {
        for (int prefix = 24; prefix <= 30; prefix++) {
            List<InetAddress> result = SmbDiscoveryProbe.candidates(ipv4(10, 0, 0, 1), prefix);
            assertEquals((1 << (32 - prefix)) - 3, result.size());
            assertEquals(ipv4(10, 0, 0, 2), result.get(0));
            assertEquals(ipv4(10, 0, 0, (1 << (32 - prefix)) - 2),
                    result.get(result.size() - 1));
        }
    }

    @Test
    public void networkOrBroadcastLocalAddressNeverAddsReservedHosts() throws Exception {
        for (int host : new int[]{0, 255}) {
            List<InetAddress> result = SmbDiscoveryProbe.candidates(ipv4(192, 168, 0, host), 24);
            assertEquals(254, result.size());
            assertEquals(ipv4(192, 168, 0, 1), result.get(0));
            assertEquals(ipv4(192, 168, 0, 254), result.get(253));
        }
    }

    @Test
    public void acceptsOnlyPrivateAndLinkLocalIpv4Ranges() throws Exception {
        for (int[] address : new int[][]{{10, 0, 0, 1}, {10, 255, 255, 254},
                {172, 16, 0, 1}, {172, 31, 255, 254}, {192, 168, 0, 1},
                {192, 168, 255, 254}, {169, 254, 0, 1}, {169, 254, 255, 254}}) {
            assertEquals(253, SmbDiscoveryProbe.candidates(
                    ipv4(address[0], address[1], address[2], address[3]), 24).size());
        }
        for (int[] address : new int[][]{{9, 255, 255, 1}, {11, 0, 0, 1},
                {172, 15, 255, 1}, {172, 32, 0, 1}, {192, 167, 0, 1}, {192, 169, 0, 1},
                {169, 253, 0, 1}, {169, 255, 0, 1}, {8, 8, 8, 8}, {127, 0, 0, 1},
                {0, 0, 0, 0}, {100, 64, 0, 1}, {192, 0, 2, 1}, {198, 18, 0, 1},
                {224, 0, 0, 1}, {255, 255, 255, 255}}) {
            assertTrue(Arrays.toString(address), SmbDiscoveryProbe.candidates(
                    ipv4(address[0], address[1], address[2], address[3]), 24).isEmpty());
        }
    }

    @Test
    public void rejectsInvalidPrefixesNullAndIpv6() throws Exception {
        for (int prefix : new int[]{Integer.MIN_VALUE, -1, 31, 32, 33, Integer.MAX_VALUE}) {
            assertTrue(SmbDiscoveryProbe.candidates(ipv4(10, 0, 0, 1), prefix).isEmpty());
        }
        assertTrue(SmbDiscoveryProbe.candidates(null, 24).isEmpty());
        byte[] ipv6 = new byte[16];
        ipv6[0] = (byte) 0xfe;
        ipv6[1] = (byte) 0x80;
        ipv6[15] = 1;
        assertTrue(SmbDiscoveryProbe.candidates(InetAddress.getByAddress(ipv6), 64).isEmpty());
        assertTrue(SmbDiscoveryProbe.candidates(InetAddress.getByAddress(ipv6), 24).isEmpty());
        Arrays.fill(ipv6, (byte) 0);
        ipv6[10] = (byte) 0xff;
        ipv6[11] = (byte) 0xff;
        ipv6[12] = 10;
        ipv6[15] = 1;
        assertTrue(SmbDiscoveryProbe.candidates(
                Inet6Address.getByAddress(null, ipv6, -1), 24).isEmpty());
    }

    @Test
    public void acceptsSuccessfulNegotiateForBothOfferedDialectsAndSigningModes() throws Exception {
        for (int dialect : new int[]{0x0202, 0x0210}) {
            for (int securityMode : new int[]{1, 3}) {
                byte[] response = validResponse();
                putShort(response, 68, dialect);
                putShort(response, 66, securityMode);
                putInt(response, 88, dialect == 0x0202 ? 1 : 7);
                assertProbe(response, true);
            }
        }
    }

    @Test
    public void ignoresResponseFieldsReservedForOlderDialects() throws Exception {
        byte[] response = validResponse();
        putInt(response, 32, 0xfeff);
        putShort(response, 70, 1);
        putInt(response, 124, 128);
        assertProbe(response, true);
    }

    @Test
    public void requestIsOnlyAnUnauthenticatedSmb2Negotiate() throws Exception {
        FakeSocket socket = new FakeSocket(validResponse());
        InetAddress address = ipv4(192, 168, 1, 2);
        assertTrue(SmbDiscoveryProbe.isSmbServer(socket, address, 1445, 750));
        assertEquals(new InetSocketAddress(address, 1445), socket.endpoint);
        assertEquals(750, socket.connectTimeout);
        assertTrue(socket.readTimeout > 0 && socket.readTimeout <= 750);
        assertEquals(1, socket.closeCalls);
        byte[] request = socket.output.toByteArray();
        assertEquals(108, request.length);
        assertArrayEquals(new byte[]{0, 0, 0, 104}, Arrays.copyOf(request, 4));
        assertArrayEquals(new byte[]{(byte) 0xfe, 'S', 'M', 'B'},
                Arrays.copyOfRange(request, 4, 8));
        ByteBuffer packet = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, packet.getShort(8));
        assertEquals(0, packet.getShort(16));
        assertTrue(packet.getShort(18) > 0);
        assertArrayEquals(new byte[48], Arrays.copyOfRange(request, 20, 68));
        assertEquals(36, packet.getShort(68));
        assertEquals(2, packet.getShort(70));
        assertEquals(1, packet.getShort(72));
        assertEquals(0, packet.getShort(74));
        assertEquals(0, packet.getInt(76));
        assertFalse(Arrays.equals(new byte[16], Arrays.copyOfRange(request, 80, 96)));
        assertEquals(0, packet.getLong(96));
        assertEquals(0x0202, packet.getShort(104));
        assertEquals(0x0210, packet.getShort(106));
    }

    @Test
    public void acceptsFragmentedResponse() throws Exception {
        FakeSocket socket = new FakeSocket(validResponse());
        socket.chunkSize = 1;
        assertTrue(SmbDiscoveryProbe.isSmbServer(socket, ipv4(10, 0, 0, 2), 445, 1000));
        assertEquals(validResponse().length, socket.bytesRead);
        assertEquals(1, socket.closeCalls);
    }

    @Test
    public void acceptsEmptyOrPaddedSecurityBufferWithinFrame() throws Exception {
        byte[] empty = Arrays.copyOf(validResponse(), 132);
        setFrameLength(empty, 128);
        putShort(empty, 120, 0);
        putShort(empty, 122, 0);
        assertProbe(empty, true);
        putShort(empty, 120, 128);
        assertProbe(empty, true);
        byte[] padded = Arrays.copyOf(validResponse(), validResponse().length + 8);
        setFrameLength(padded, padded.length - 4);
        System.arraycopy(padded, 132, padded, 140, padded.length - 140);
        Arrays.fill(padded, 132, 140, (byte) 0);
        putShort(padded, 120, 136);
        assertProbe(padded, true);
    }

    @Test
    public void rejectsNonSmbTcpAndSmb1() throws Exception {
        assertProbe(new byte[]{'H', 'T', 'T', 'P', '/', '1', '.', '1'}, false);
        byte[] smb1 = validResponse();
        smb1[4] = (byte) 0xff;
        assertProbe(smb1, false);
        byte[] random = new byte[132];
        setFrameLength(random, 128);
        assertProbe(random, false);
    }

    @Test
    public void rejectsMalformedHeaderAndUnrelatedResponses() throws Exception {
        byte[] response = validResponse();
        for (int offset : new int[]{0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 16,
                20, 24, 31, 36, 40, 47, 64, 65}) {
            byte[] malformed = response.clone();
            malformed[4 + offset] ^= 0x40;
            assertProbe(malformed, false);
        }
        for (int flags : new int[]{0, 2, 3, 5, 9, 0x10000001}) {
            byte[] malformed = response.clone();
            putInt(malformed, 16, flags);
            assertProbe(malformed, false);
        }
    }

    @Test
    public void rejectsErrorStatusEvenWithPlausibleNegotiateBody() throws Exception {
        for (int status : new int[]{0xc0000022, 0xc00000bb, 0x00000103, 0x80000005}) {
            byte[] response = validResponse();
            putInt(response, 8, status);
            assertProbe(response, false);
        }
    }

    @Test
    public void rejectsUnofferedDialectsAndInvalidSecurityModes() throws Exception {
        for (int dialect : new int[]{0, 0x0200, 0x02ff, 0x0300, 0x0311, 0xffff}) {
            byte[] response = validResponse();
            putShort(response, 68, dialect);
            assertProbe(response, false);
        }
        for (int mode : new int[]{0, 2, 4, 5, 0xffff}) {
            byte[] response = validResponse();
            putShort(response, 66, mode);
            assertProbe(response, false);
        }
    }

    @Test
    public void rejectsInvalidSecurityBufferRangesAndZeroTransferSizes() throws Exception {
        for (int[] range : new int[][]{{0, 30}, {64, 30}, {127, 30}, {129, 30},
                {65535, 1}, {128, 65535}, {65535, 0}, {64, 0}}) {
            byte[] response = validResponse();
            putShort(response, 120, range[0]);
            putShort(response, 122, range[1]);
            assertProbe(response, false);
        }
        for (int offset : new int[]{92, 96, 100}) {
            byte[] response = validResponse();
            putInt(response, offset, 0);
            assertProbe(response, false);
        }
    }

    @Test
    public void rejectsEveryTruncatedResponseAndClosesSocket() throws Exception {
        byte[] response = validResponse();
        for (int length = 0; length < response.length; length++) {
            assertProbe(Arrays.copyOf(response, length), false);
        }
    }

    @Test
    public void acceptsFrameAtResponseSizeLimit() throws Exception {
        byte[] response = Arrays.copyOf(validResponse(), 65540);
        setFrameLength(response, 65536);
        putShort(response, 122, 65536 - 128);
        assertProbe(response, true);
    }

    @Test
    public void rejectsInvalidFrameLengthsBeforeReadingPayload() throws Exception {
        for (int length : new int[]{0, 64, 127, 65537, 0x01000000 - 1}) {
            byte[] response = validResponse();
            setFrameLength(response, length);
            FakeSocket socket = new FakeSocket(response);
            assertFalse(SmbDiscoveryProbe.isSmbServer(socket, ipv4(10, 0, 0, 2), 445, 500));
            assertEquals(4, socket.bytesRead);
            assertEquals(1, socket.closeCalls);
        }
        for (int type : new int[]{0x81, 0x82, 0x83, 0x85, 0xff}) {
            byte[] response = validResponse();
            response[0] = (byte) type;
            assertProbe(response, false);
        }
    }

    @Test
    public void ioFailuresAndTimeoutsAlwaysCloseSocket() throws Exception {
        for (String failure : new String[]{"connect", "timeout", "input", "output",
                "write", "read", "close"}) {
            FakeSocket socket = new FakeSocket(validResponse());
            socket.failure = failure;
            assertFalse(failure,
                    SmbDiscoveryProbe.isSmbServer(socket, ipv4(10, 0, 0, 2), 445, 100));
            assertEquals(failure, 1, socket.closeCalls);
        }
    }

    @Test(timeout = 2000)
    public void sharedReadDeadlineRejectsDribblingResponse() throws Exception {
        FakeSocket socket = new FakeSocket(validResponse());
        socket.chunkSize = 1;
        socket.readDelayMs = 15;
        assertFalse(SmbDiscoveryProbe.isSmbServer(socket, ipv4(10, 0, 0, 2), 445, 50));
        assertTrue(socket.bytesRead < validResponse().length);
        assertEquals(1, socket.closeCalls);
    }

    @Test
    public void invalidArgumentsNeverConnectAndStillCloseSuppliedSocket() throws Exception {
        assertFalse(SmbDiscoveryProbe.isSmbServer(null, ipv4(10, 0, 0, 2), 445, 100));
        FakeSocket missingAddress = new FakeSocket(validResponse());
        assertFalse(SmbDiscoveryProbe.isSmbServer(missingAddress, null, 445, 100));
        assertEquals(0, missingAddress.connectCalls);
        assertEquals(1, missingAddress.closeCalls);
        for (int[] arguments : new int[][]{{0, 100}, {-1, 100}, {65536, 100},
                {445, 0}, {445, -1}}) {
            FakeSocket socket = new FakeSocket(validResponse());
            assertFalse(SmbDiscoveryProbe.isSmbServer(socket, ipv4(10, 0, 0, 2),
                    arguments[0], arguments[1]));
            assertEquals(0, socket.connectCalls);
            assertEquals(1, socket.closeCalls);
        }
    }

    @Test
    public void rejectsAlreadyConnectedOrCancelledSockets() throws Exception {
        FakeSocket connected = new FakeSocket(validResponse());
        connected.connected = true;
        assertFalse(SmbDiscoveryProbe.isSmbServer(connected, ipv4(10, 0, 0, 2), 445, 100));
        assertEquals(0, connected.connectCalls);
        assertEquals(1, connected.closeCalls);
        FakeSocket cancelled = new FakeSocket(validResponse());
        cancelled.close();
        assertFalse(SmbDiscoveryProbe.isSmbServer(cancelled, ipv4(10, 0, 0, 2), 445, 100));
        assertEquals(0, cancelled.connectCalls);
        assertTrue(cancelled.isClosed());
    }

    @Test(timeout = 5000)
    public void negotiatesOverRealPreBoundSocketAndClosesWithoutAuthenticating() throws Exception {
        InetAddress loopback = ipv4(127, 0, 0, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             Socket client = new Socket()) {
            server.setSoTimeout(2000);
            client.bind(new InetSocketAddress(loopback, 0));
            Future<Boolean> result = executor.submit(() ->
                    SmbDiscoveryProbe.isSmbServer(client, loopback, server.getLocalPort(), 2000));
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2000);
                byte[] request = new byte[108];
                new DataInputStream(peer.getInputStream()).readFully(request);
                assertArrayEquals(new byte[]{0, 0, 0, 104}, Arrays.copyOf(request, 4));
                peer.getOutputStream().write(validResponse());
                peer.getOutputStream().flush();
                assertTrue(result.get(2, TimeUnit.SECONDS));
                assertTrue(client.isClosed());
                assertEquals(-1, peer.getInputStream().read());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000)
    public void realSocketReadTimesOutAndCloses() throws Exception {
        InetAddress loopback = ipv4(127, 0, 0, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             Socket client = new Socket()) {
            Future<Boolean> result = executor.submit(() ->
                    SmbDiscoveryProbe.isSmbServer(client, loopback, server.getLocalPort(), 100));
            assertFalse(result.get(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000)
    public void closingSuppliedSocketCancelsBlockedRead() throws Exception {
        InetAddress loopback = ipv4(127, 0, 0, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Socket client = new Socket();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            server.setSoTimeout(2000);
            Future<Boolean> result = executor.submit(() ->
                    SmbDiscoveryProbe.isSmbServer(client, loopback, server.getLocalPort(), 10000));
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2000);
                new DataInputStream(peer.getInputStream()).readFully(new byte[108]);
                client.close();
                assertFalse(result.get(2, TimeUnit.SECONDS));
                assertTrue(client.isClosed());
                assertEquals(-1, peer.getInputStream().read());
            }
        } finally {
            client.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static InetAddress ipv4(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static void assertProbe(byte[] response, boolean expected) throws Exception {
        FakeSocket socket = new FakeSocket(response);
        assertEquals(expected,
                SmbDiscoveryProbe.isSmbServer(socket, ipv4(192, 168, 1, 2), 445, 1000));
        assertEquals(1, socket.closeCalls);
    }

    private static byte[] validResponse() {
        byte[] token = new byte[]{0x60, 0x1c, 0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02,
                (byte) 0xa0, 0x12, 0x30, 0x10, (byte) 0xa0, 0x0e, 0x30, 0x0c, 0x06, 0x0a,
                0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a};
        byte[] response = new byte[132 + token.length];
        setFrameLength(response, response.length - 4);
        response[4] = (byte) 0xfe;
        response[5] = 'S';
        response[6] = 'M';
        response[7] = 'B';
        putShort(response, 4, 64);
        putShort(response, 14, 1);
        putInt(response, 16, 1);
        putShort(response, 64, 65);
        putShort(response, 66, 1);
        putShort(response, 68, 0x0210);
        for (int index = 0; index < 16; index++) {
            response[4 + 72 + index] = (byte) (index + 1);
        }
        putInt(response, 88, 7);
        putInt(response, 92, 65536);
        putInt(response, 96, 65536);
        putInt(response, 100, 65536);
        putShort(response, 120, 128);
        putShort(response, 122, token.length);
        System.arraycopy(token, 0, response, 132, token.length);
        return response;
    }

    private static void setFrameLength(byte[] response, int length) {
        response[1] = (byte) (length >>> 16);
        response[2] = (byte) (length >>> 8);
        response[3] = (byte) length;
    }

    private static void putShort(byte[] response, int offset, int value) {
        ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).putShort(4 + offset, (short) value);
    }

    private static void putInt(byte[] response, int offset, int value) {
        ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + offset, value);
    }

    private static final class FakeSocket extends Socket {
        private final ByteArrayInputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private SocketAddress endpoint;
        private int connectTimeout;
        private int readTimeout;
        private int connectCalls;
        private int closeCalls;
        private int bytesRead;
        private int chunkSize = Integer.MAX_VALUE;
        private int readDelayMs;
        private boolean connected;
        private String failure = "";

        private FakeSocket(byte[] response) {
            input = new ByteArrayInputStream(response);
        }

        @Override
        public void connect(SocketAddress address, int timeout) throws IOException {
            connectCalls++;
            endpoint = address;
            connectTimeout = timeout;
            if ("connect".equals(failure)) {
                throw new SocketTimeoutException();
            }
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean isClosed() {
            return closeCalls > 0;
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            if ("timeout".equals(failure)) {
                throw new SocketException();
            }
            assertTrue(timeout > 0);
            readTimeout = timeout;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if ("input".equals(failure)) {
                throw new IOException();
            }
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] value = new byte[1];
                    return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
                }

                @Override
                public int read(byte[] target, int offset, int length) throws IOException {
                    if ("read".equals(failure)) {
                        throw new SocketTimeoutException();
                    }
                    if (readDelayMs > 0) {
                        try {
                            Thread.sleep(readDelayMs);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                    }
                    int count = input.read(target, offset, Math.min(length, chunkSize));
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if ("output".equals(failure)) {
                throw new IOException();
            }
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    if ("write".equals(failure)) {
                        throw new IOException();
                    }
                    output.write(value);
                }
            };
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if ("close".equals(failure)) {
                throw new IOException();
            }
        }
    }
}