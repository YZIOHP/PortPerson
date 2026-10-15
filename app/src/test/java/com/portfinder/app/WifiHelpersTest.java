package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WifiHelpersTest {

    static WifiPanel.PingStats parse(String out) {
        WifiPanel.PingStats s = new WifiPanel.PingStats();
        List<Double> times = new ArrayList<>();
        for (String l : out.split("\n")) WifiPanel.parsePingLine(l, s, times);
        if (s.avg < 0 && !times.isEmpty()) s.avg = times.get(0);
        return s;
    }

    @Test
    public void parsesSuccessfulPing() {
        WifiPanel.PingStats s = parse(
                "PING 142.250.72.14 (142.250.72.14) 56(84) bytes of data.\n"
                + "64 bytes from 142.250.72.14: icmp_seq=1 ttl=117 time=14.2 ms\n"
                + "64 bytes from 142.250.72.14: icmp_seq=2 ttl=117 time=13.8 ms\n"
                + "\n--- 142.250.72.14 ping statistics ---\n"
                + "4 packets transmitted, 4 received, 0% packet loss, time 3004ms\n"
                + "rtt min/avg/max/mdev = 13.801/14.050/14.390/0.231 ms\n");
        assertEquals(4, s.sent);
        assertEquals(4, s.received);
        assertEquals(13.801, s.min, 1e-9);
        assertEquals(14.05, s.avg, 1e-9);
        assertNull(s.from);
    }

    @Test
    public void parsesTtlExceededHop() {
        WifiPanel.PingStats s = parse(
                "PING 142.250.72.14 (142.250.72.14) 56(84) bytes of data.\n"
                + "From 192.168.1.1 icmp_seq=1 Time to live exceeded\n"
                + "\n--- 142.250.72.14 ping statistics ---\n"
                + "1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms\n");
        assertEquals(0, s.received);
        assertEquals("192.168.1.1", s.from);
        // older iputils prints a colon after the address
        assertEquals("10.0.0.1", parse("From 10.0.0.1: icmp_seq=1 Time to live exceeded\n").from);
    }

    @Test
    public void parsesSingleReplyWithoutSummaryLine() {
        WifiPanel.PingStats s = parse("64 bytes from 8.8.8.8: icmp_seq=1 ttl=118 time=9.87 ms\n"
                + "1 packets transmitted, 1 received, 0% packet loss, time 0ms\n");
        assertEquals(1, s.received);
        assertEquals(9.87, s.avg, 1e-9);
    }

    @Test
    public void channelsAndBands() {
        assertEquals(1, WifiPanel.channel(2412));
        assertEquals(6, WifiPanel.channel(2437));
        assertEquals(14, WifiPanel.channel(2484));
        assertEquals(36, WifiPanel.channel(5180));
        assertEquals(149, WifiPanel.channel(5745));
        assertEquals(1, WifiPanel.channel(5955));
        assertEquals(2, WifiPanel.channel(5935));
        assertEquals("6 GHz", WifiPanel.band(5955));
        assertEquals("5 GHz", WifiPanel.band(5180));
        assertEquals("ch 36 (5 GHz) · 80 MHz", WifiPanel.channelText(5180, 2));
    }

    @Test
    public void security() {
        assertEquals("WPA2", WifiPanel.securityFromCaps("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"));
        assertEquals("WPA2/WPA3", WifiPanel.securityFromCaps("[RSN-PSK+SAE-CCMP][ESS][MFPC]"));
        assertEquals("WPA3", WifiPanel.securityFromCaps("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"));
        assertEquals("WPA2-Ent", WifiPanel.securityFromCaps("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"));
        assertEquals("Open", WifiPanel.securityFromCaps("[ESS]"));
        assertEquals("OWE", WifiPanel.securityFromCaps("[RSN-OWE-CCMP][ESS][MFPR][MFPC]"));
        assertEquals("Corp", WifiPanel.cleanSsid("\"Corp\""));
        assertEquals("", WifiPanel.cleanSsid("<unknown ssid>"));
    }
}
