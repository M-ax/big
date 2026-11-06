package com.example.big.smb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class SmbDiscoveryProbe {
    private static final int MIN_RESPONSE_BYTES = 128;
    private static final int MAX_RESPONSE_BYTES = 65536;

    private SmbDiscoveryProbe() {
    }

    /**
     * Returns private/link-local IPv4 hosts in numeric order, excluding network, broadcast and self.
     * Caps larger subnets to the local containing /24; invalid inputs and /31-/32 yield no hosts.
     */
    public static List<InetAddress> candidates(InetAddress localAddress, int prefixLength) {
        if (!(localAddress instanceof Inet4Address) || prefixLength < 0 || prefixLength >= 31) {
            return Collections.emptyList();
        }
        byte[] bytes = localAddress.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        boolean allowed = first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168) || (first == 169 && second == 254);
        if (!allowed) {
            return Collections.emptyList();
        }
        int local = ByteBuffer.wrap(bytes).getInt();
        int hostMask = (1 << (32 - Math.max(prefixLength, 24))) - 1;
        int network = local & ~hostMask;
        List<InetAddress> result = new ArrayList<>(hostMask - 1);
        for (int host = 1; host < hostMask; host++) {
            int candidate = network | host;
            if (candidate != local) {
                byte[] address = new byte[]{(byte) (candidate >>> 24), (byte) (candidate >>> 16),
                        (byte) (candidate >>> 8), (byte) candidate};
                try {
                    result.add(InetAddress.getByAddress(address));
                } catch (UnknownHostException exception) {
                    return Collections.emptyList();
                }
            }
        }
        return result;
    }

    /**
     * Owns and always closes the supplied unconnected socket; callers may bind it before probing
     * or close it to cancel. Uses a positive connect/read time budget, caps responses at 64 KiB,
     * and never authenticates. Invalid arguments and I/O failures return false.
     */
    public static boolean isSmbServer(Socket socket, InetAddress address, int port, int timeoutMs) {
        if (socket == null) {
            return false;
        }
        try (Socket connection = socket) {
            if (address == null || port < 1 || port > 65535 || timeoutMs <= 0
                    || connection.isClosed() || connection.isConnected()) {
                return false;
            }
            byte[] request = negotiateRequest();
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            connection.connect(new InetSocketAddress(address, port), timeoutMs);
            connection.setSoTimeout(remainingTimeout(deadline));
            OutputStream output = connection.getOutputStream();
            output.write(request);
            output.flush();
            InputStream input = connection.getInputStream();
            byte[] frame = new byte[4];
            readFully(connection, input, frame, deadline);
            int length = ((frame[1] & 0xff) << 16) | ((frame[2] & 0xff) << 8) | (frame[3] & 0xff);
            if (frame[0] != 0 || length < MIN_RESPONSE_BYTES || length > MAX_RESPONSE_BYTES) {
                return false;
            }
            byte[] response = new byte[length];
            readFully(connection, input, response, deadline);
            return isNegotiateResponse(response);
        } catch (IOException exception) {
            return false;
        }
    }

    private static byte[] negotiateRequest() {
        // These offsets include the four-byte direct TCP frame header.
        ByteBuffer request = ByteBuffer.allocate(108).order(ByteOrder.LITTLE_ENDIAN);
        request.put(3, (byte) 104);
        request.put(4, (byte) 0xfe);
        request.put(5, (byte) 'S');
        request.put(6, (byte) 'M');
        request.put(7, (byte) 'B');
        request.putShort(8, (short) 64);
        request.putShort(18, (short) 1);
        request.putShort(68, (short) 36);
        request.putShort(70, (short) 2);
        request.putShort(72, (short) 1);
        UUID clientGuid = UUID.randomUUID();
        request.putLong(80, clientGuid.getMostSignificantBits());
        request.putLong(88, clientGuid.getLeastSignificantBits());
        request.putShort(104, (short) 0x0202);
        request.putShort(106, (short) 0x0210);
        return request.array();
    }

    private static void readFully(Socket socket, InputStream input, byte[] target, long deadline)
            throws IOException {
        int offset = 0;
        while (offset < target.length) {
            socket.setSoTimeout(remainingTimeout(deadline));
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                throw new EOFException();
            }
            if (count == 0) {
                throw new IOException();
            }
            offset += count;
        }
        remainingTimeout(deadline);
    }

    private static int remainingTimeout(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException();
        }
        return (int) ((remaining + 999_999L) / 1_000_000L);
    }

    private static boolean isNegotiateResponse(byte[] response) {
        ByteBuffer packet = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        // Reserved fields are ignored, including the SMB 3.1.1 context fields for these dialects.
        if (packet.getInt(0) != 0x424d53fe || packet.getShort(4) != 64
                || packet.getInt(8) != 0 || packet.getShort(12) != 0
                || packet.getInt(16) != 1 || packet.getInt(20) != 0
                || packet.getLong(24) != 0 || packet.getInt(36) != 0
                || packet.getLong(40) != 0 || packet.getShort(64) != 65) {
            return false;
        }
        int securityMode = packet.getShort(66) & 0xffff;
        int dialect = packet.getShort(68) & 0xffff;
        if ((securityMode != 1 && securityMode != 3) || (dialect != 0x0202 && dialect != 0x0210)
                || packet.getInt(92) == 0 || packet.getInt(96) == 0 || packet.getInt(100) == 0) {
            return false;
        }
        int securityOffset = packet.getShort(120) & 0xffff;
        int securityLength = packet.getShort(122) & 0xffff;
        if (securityLength == 0 && securityOffset == 0) {
            return true;
        }
        return securityOffset >= MIN_RESPONSE_BYTES
                && securityOffset <= response.length
                && securityLength <= response.length - securityOffset;
    }
}