package com.portfinder.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Builds a minimal LLDP-MED "endpoint" announcement. Optional: many switches (Cisco, Aruba, Juniper)
 * use LLDP-MED fast start, so once they hear an endpoint they answer within a second or two instead
 * of waiting for the next 30-second LLDP interval.
 */
public final class LldpAnnouncer {

    private final byte[] mac = new byte[6];

    public LldpAnnouncer() {
        new SecureRandom().nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // locally administered, unicast
    }

    /** Our own source MAC, so the app can ignore its own announcements if they echo back. */
    public String macString() {
        return DiscoveryParser.mac(mac, 0);
    }

    public byte[] buildFrame() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{0x01, (byte) 0x80, (byte) 0xC2, 0x00, 0x00, 0x0E}, 0, 6); // nearest bridge
        o.write(mac, 0, 6);
        o.write(0x88);
        o.write(0xCC);
        tlv(o, 1, concat(new byte[]{4}, mac));                  // chassis ID: MAC
        tlv(o, 2, concat(new byte[]{3}, mac));                  // port ID: MAC
        tlv(o, 3, new byte[]{0, 120});                          // TTL
        tlv(o, 5, "PortPerson".getBytes(StandardCharsets.US_ASCII));
        tlv(o, 6, "PortPerson Android tester".getBytes(StandardCharsets.US_ASCII));
        tlv(o, 7, new byte[]{0, (byte) 0x80, 0, (byte) 0x80}); // capabilities: station
        // LLDP-MED capabilities: supports "LLDP-MED capabilities", device class = Endpoint Class I
        tlv(o, 127, new byte[]{0x00, 0x12, (byte) 0xBB, 1, 0x00, 0x01, 0x01});
        tlv(o, 0, new byte[0]);
        return o.toByteArray();
    }

    private static void tlv(ByteArrayOutputStream o, int type, byte[] v) {
        int hdr = (type << 9) | v.length;
        o.write(hdr >> 8);
        o.write(hdr);
        o.write(v, 0, v.length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
