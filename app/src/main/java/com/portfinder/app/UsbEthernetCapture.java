package com.portfinder.app;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;

/**
 * A tiny userspace Ethernet driver for USB adapters that speak the standard USB CDC-ECM or CDC-NCM
 * class protocols. It talks to the adapter directly through Android's USB host API, which lets an
 * ordinary (non-rooted) app receive raw Ethernet frames, including the link-local multicast frames
 * that carry CDP and LLDP. Android's own networking never sees them, so this is the only way to
 * read them without root.
 *
 * Realtek RTL8152/RTL8153 adapters (the most common chip) ship a vendor-mode configuration that
 * Android's kernel driver normally uses, plus a standard ECM configuration; this class detaches the
 * kernel driver and switches the adapter to the standard configuration.
 *
 * ASIX AX88179 / AX88179A / AX88178A adapters that don't offer a standard mode are driven in their
 * native (vendor) mode instead, following the register sequence of Linux's ax88179_178a driver.
 */
public final class UsbEthernetCapture {

    public enum Mode { ECM, NCM, ASIX }

    public interface Listener {
        /** Called on the capture thread. The buffer is reused after this returns. */
        void onFrame(byte[] buf, int off, int len);
        void onLinkChange(boolean up);
        void onLinkSpeed(long downBitsPerSec, long upBitsPerSec);
        void onCaptureStatus(String message);
    }

    /** Which configuration/interfaces/endpoints to use for a given adapter. */
    public static final class Plan {
        public final Mode mode;
        final UsbConfiguration config;
        final UsbInterface control;
        final UsbInterface dataIdle;   // alternate setting 0 (no endpoints)
        final UsbInterface dataActive; // alternate setting with the two bulk endpoints
        final UsbEndpoint in, out, notify;

        Plan(Mode mode, UsbConfiguration config, UsbInterface control, UsbInterface dataIdle,
             UsbInterface dataActive, UsbEndpoint in, UsbEndpoint out, UsbEndpoint notify) {
            this.mode = mode;
            this.config = config;
            this.control = control;
            this.dataIdle = dataIdle;
            this.dataActive = dataActive;
            this.in = in;
            this.out = out;
            this.notify = notify;
        }
    }

    // CDC class requests / notifications
    private static final int REQ_OUT_CLASS_IFACE = 0x21; // host-to-device | class | interface
    private static final int REQ_IN_CLASS_IFACE = 0xA1;
    private static final int SET_ETHERNET_PACKET_FILTER = 0x43;
    private static final int GET_NTB_PARAMETERS = 0x80;
    private static final int SET_NTB_FORMAT = 0x84;
    private static final int PACKET_FILTER_ALL = 0x1F; // promiscuous|all-multicast|directed|broadcast|multicast
    private static final int NOTIFY_NETWORK_CONNECTION = 0x00;
    private static final int NOTIFY_SPEED_CHANGE = 0x2A;

    private final UsbManager usbManager;
    private final UsbDevice device;
    private final Plan plan;
    private final Listener listener;

    private UsbDeviceConnection conn;
    private volatile boolean running;
    private Thread rxThread, notifyThread, linkThread;
    private int rxBufferSize = 4096;
    private int ntbSequence;
    private volatile byte[] macAddress;
    private int originalConfigValue = -1;

    public UsbEthernetCapture(UsbManager usbManager, UsbDevice device, Plan plan, Listener listener) {
        this.usbManager = usbManager;
        this.device = device;
        this.plan = plan;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ discovery

    /** Finds a CDC-ECM (preferred) or CDC-NCM function in any of the device's configurations. */
    public static Plan findPlan(UsbDevice d) {
        Plan best = null;
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface ctl = cfg.getInterface(i);
                if (ctl.getInterfaceClass() != UsbConstants.USB_CLASS_COMM) continue;
                Mode mode;
                if (ctl.getInterfaceSubclass() == 0x06) mode = Mode.ECM;
                else if (ctl.getInterfaceSubclass() == 0x0D) mode = Mode.NCM;
                else continue;

                UsbEndpoint notify = null;
                for (int e = 0; e < ctl.getEndpointCount(); e++) {
                    UsbEndpoint ep = ctl.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) notify = ep;
                }
                Plan p = findData(mode, cfg, ctl, notify, true);
                if (p == null) p = findData(mode, cfg, ctl, notify, false);
                if (p == null) continue;
                if (best == null || (best.mode == Mode.NCM && mode == Mode.ECM)) best = p;
            }
        }
        if (best == null && isAsix(d)) best = findVendorPlan(d);
        return best;
    }

    /** USB IDs handled by Linux's ax88179_178a driver (AX88179, AX88179A, AX88178A and OEM versions). */
    private static final int[][] ASIX_IDS = {
            {0x0b95, 0x1790}, {0x0b95, 0x178a}, {0x0b95, 0x0179}, // ASIX
            {0x2001, 0x4a00},                                     // D-Link DUB-1312
            {0x0df6, 0x0072},                                     // Sitecom
            {0x04e8, 0xa100},                                     // Samsung
            {0x17ef, 0x304b},                                     // Lenovo OneLinkDock
            {0x0930, 0x0a13},                                     // Toshiba
            {0x0711, 0x0179},                                     // Magic Control Technology
            {0x050d, 0x0128},                                     // Belkin B2B128
    };

    public static boolean isAsix(UsbDevice d) {
        for (int[] id : ASIX_IDS) if (d.getVendorId() == id[0] && d.getProductId() == id[1]) return true;
        return false;
    }

    /** The ASIX vendor interface: class FF with bulk IN + bulk OUT (+ interrupt IN for link events). */
    private static Plan findVendorPlan(UsbDevice d) {
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_VENDOR_SPEC) continue;
                UsbEndpoint in = null, out = null, notify = null;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
                    } else if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        notify = ep;
                    }
                }
                if (in != null && out != null) return new Plan(Mode.ASIX, cfg, intf, intf, intf, in, out, notify);
            }
        }
        return null;
    }

    /** One line per configuration listing interface class/subclass/protocol and endpoints, for diagnostics. */
    public static String usbLayout(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            if (sb.length() > 0) sb.append('\n');
            sb.append("cfg").append(c + 1).append(':');
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                sb.append(String.format(Locale.US, " if%d.%d=%02x/%02x/%02x", intf.getId(), intf.getAlternateSetting(),
                        intf.getInterfaceClass(), intf.getInterfaceSubclass(), intf.getInterfaceProtocol()));
                if (intf.getEndpointCount() > 0) {
                    sb.append('[');
                    for (int e = 0; e < intf.getEndpointCount(); e++) {
                        UsbEndpoint ep = intf.getEndpoint(e);
                        if (e > 0) sb.append(' ');
                        sb.append(ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "B" : ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ? "I" : "?")
                          .append(ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
                    }
                    sb.append(']');
                }
            }
        }
        return sb.length() == 0 ? "no configurations reported" : sb.toString();
    }

    private static Plan findData(Mode mode, UsbConfiguration cfg, UsbInterface ctl, UsbEndpoint notify,
                                 boolean requireAdjacentId) {
        UsbInterface idle = null, active = null;
        UsbEndpoint in = null, out = null;
        int dataId = -1;
        for (int j = 0; j < cfg.getInterfaceCount(); j++) {
            UsbInterface di = cfg.getInterface(j);
            if (di.getInterfaceClass() != UsbConstants.USB_CLASS_CDC_DATA) continue;
            if (requireAdjacentId && di.getId() != ctl.getId() + 1) continue;
            if (dataId != -1 && di.getId() != dataId) continue;
            UsbEndpoint bi = null, bo = null;
            for (int e = 0; e < di.getEndpointCount(); e++) {
                UsbEndpoint ep = di.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) bi = ep; else bo = ep;
            }
            if (di.getAlternateSetting() == 0 && idle == null) idle = di;
            if (bi != null && bo != null && active == null) {
                active = di;
                in = bi;
                out = bo;
                dataId = di.getId();
            }
        }
        if (active == null) return null;
        if (idle == null || idle.getId() != active.getId()) idle = active;
        return new Plan(mode, cfg, ctl, idle, active, in, out, notify);
    }

    /** Short human description of a USB device for status/error messages. */
    public static String describe(UsbDevice d) {
        String name = d.getProductName();
        String maker = d.getManufacturerName();
        String label = ((maker != null ? maker + " " : "") + (name != null ? name : "USB device")).trim();
        return String.format(Locale.US, "%s (%04x:%04x)", label, d.getVendorId(), d.getProductId());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens the adapter and starts capturing. Blocking (does USB control transfers); call off the UI thread. */
    public synchronized void start() throws IOException {
        try {
            conn = usbManager.openDevice(device);
        } catch (RuntimeException e) {
            conn = null;
        }
        if (conn == null) throw new IOException("Android could not open the adapter");
        originalConfigValue = activeConfigValue();

        // Detach whatever kernel driver (r8152, cdc_ether, ...) currently owns the adapter.
        status("Taking the adapter over from Android's driver…");
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration cfg = device.getConfiguration(c);
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                if (conn.claimInterface(intf, true)) conn.releaseInterface(intf);
            }
        }

        if (plan.mode == Mode.ASIX) {
            startAsix();
            return;
        }

        if (device.getConfigurationCount() > 1) {
            boolean switched = conn.setConfiguration(plan.config);
            status(switched ? "Switched adapter to standard " + plan.mode + " mode"
                            : "Adapter configuration unchanged (it may already be in " + plan.mode + " mode)");
            sleep(300); // give the adapter a moment to re-enumerate its interfaces
        }

        macAddress = readMacAddress();

        if (!conn.claimInterface(plan.control, true)) {
            close();
            throw new IOException("Could not claim the adapter's control interface");
        }
        if (plan.dataIdle.getId() != plan.control.getId() && !conn.claimInterface(plan.dataIdle, true)) {
            close();
            throw new IOException("Could not claim the adapter's data interface");
        }

        int ctlId = plan.control.getId();
        if (plan.mode == Mode.NCM) {
            if (plan.dataIdle != plan.dataActive) conn.setInterface(plan.dataIdle); // NCM params must be set at alt 0
            byte[] params = new byte[28];
            int n = conn.controlTransfer(REQ_IN_CLASS_IFACE, GET_NTB_PARAMETERS, 0, ctlId, params, params.length, 1000);
            long ntbInMax = n >= 8 ? le32(params, 4) : 0;
            rxBufferSize = (int) Math.max(16384, Math.min(ntbInMax, 1 << 20));
            conn.controlTransfer(REQ_OUT_CLASS_IFACE, SET_NTB_FORMAT, 0, ctlId, null, 0, 1000); // NTB16
        } else {
            rxBufferSize = 4096;
        }

        setPacketFilter(ctlId);
        if (!conn.setInterface(plan.dataActive)) {
            close();
            throw new IOException("Could not activate the adapter's data interface");
        }
        setPacketFilter(ctlId); // some adapters reset the filter when the data interface is activated

        running = true;
        rxThread = new Thread(this::rxLoop, "usb-eth-rx");
        rxThread.start();
        if (plan.notify != null) {
            notifyThread = new Thread(this::notifyLoop, "usb-eth-notify");
            notifyThread.start();
        }
        status("Listening on " + describe(device) + " [" + plan.mode + "]");
    }

    public void stop() {
        running = false;
        joinQuietly(rxThread);
        joinQuietly(notifyThread);
        joinQuietly(linkThread);
        rxThread = null;
        notifyThread = null;
        linkThread = null;
        synchronized (this) {
            close();
        }
    }

    public boolean isRunning() { return running; }

    /** The adapter's burned-in MAC address (null until started, or if the adapter doesn't report one). */
    public byte[] getMacAddress() { return macAddress == null ? null : macAddress.clone(); }

    /**
     * Reads the MAC from the CDC "Ethernet Networking" functional descriptor, which points at a USB
     * string descriptor holding 12 hex digits. Using the real MAC keeps DHCP reservations, port
     * security and 802.1X MAB behaving the same as for a normal client.
     */
    private byte[] readMacAddress() {
        try {
            byte[] raw = conn.getRawDescriptors();
            if (raw == null) return null;
            for (int p = 0; p + 2 <= raw.length; ) {
                int len = raw[p] & 0xFF;
                if (len < 2 || p + len > raw.length) break;
                if (len >= 13 && (raw[p + 1] & 0xFF) == 0x24 && (raw[p + 2] & 0xFF) == 0x0F) {
                    int idx = raw[p + 3] & 0xFF;
                    if (idx != 0) {
                        byte[] buf = new byte[64];
                        int n = conn.controlTransfer(0x80, 0x06, (0x03 << 8) | idx, 0x0409, buf, buf.length, 1000);
                        if (n >= 2 + 24) {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 2; i + 1 < n && hex.length() < 12; i += 2) hex.append((char) ((buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8)));
                            String h = hex.toString();
                            if (h.matches("[0-9A-Fa-f]{12}")) {
                                byte[] mac = new byte[6];
                                for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
                                if ((mac[0] & 1) == 0 && !h.equals("000000000000")) return mac;
                            }
                        }
                    }
                }
                p += len;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    public UsbDevice getDevice() { return device; }

    private void close() {
        if (conn == null) return;
        try {
            conn.releaseInterface(plan.dataIdle);
            conn.releaseInterface(plan.control);
        } catch (RuntimeException ignored) {
        }
        restoreConfiguration();
        conn.close();
        conn = null;
    }

    /** USB GET_CONFIGURATION: the adapter's active configuration value, or -1. */
    private int activeConfigValue() {
        byte[] b = new byte[1];
        try {
            return conn.controlTransfer(0x80, 0x08, 0, 0, b, 1, 1000) == 1 ? b[0] & 0xFF : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Puts the adapter back in the configuration it had before the test (e.g. Realtek's vendor mode),
     * so Android's own driver picks it up again and the phone can use it as a normal Ethernet
     * connection (needed for the LAN speed test).
     */
    private void restoreConfiguration() {
        if (originalConfigValue <= 0 || plan.config == null || plan.config.getId() == originalConfigValue) return;
        if (activeConfigValue() == originalConfigValue) return;
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration cfg = device.getConfiguration(c);
            if (cfg.getId() == originalConfigValue) {
                try {
                    conn.setConfiguration(cfg);
                } catch (RuntimeException ignored) {
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ I/O

    private void setPacketFilter(int ctlId) {
        // Promiscuous + all multicast so link-local 01:00:0c:cc:cc:cc and 01:80:c2:00:00:0e get through.
        conn.controlTransfer(REQ_OUT_CLASS_IFACE, SET_ETHERNET_PACKET_FILTER, PACKET_FILTER_ALL, ctlId, null, 0, 1000);
    }

    private void rxLoop() {
        byte[] buf = new byte[rxBufferSize];
        while (running) {
            UsbDeviceConnection c = conn;
            if (c == null) break;
            int n = c.bulkTransfer(plan.in, buf, buf.length, 500);
            if (n <= 0) continue; // timeout or transient error
            if (plan.mode == Mode.ECM) {
                listener.onFrame(buf, 0, n);
            } else if (plan.mode == Mode.ASIX) {
                parseAsixRx(buf, n);
            } else {
                parseNtb16(buf, n);
            }
        }
    }

    /** Splits an NCM transfer block (NTB16) into the Ethernet frames it carries. */
    private void parseNtb16(byte[] b, int n) {
        if (n < 12 || b[0] != 'N' || b[1] != 'C' || b[2] != 'M' || b[3] != 'H') return;
        int blockLen = le16(b, 8);
        int limit = blockLen == 0 ? n : Math.min(n, blockLen);
        int ndp = le16(b, 10);
        for (int guard = 0; ndp >= 12 && ndp + 8 <= limit && guard < 32; guard++) {
            if (b[ndp] != 'N' || b[ndp + 1] != 'C' || b[ndp + 2] != 'M') return;
            int ndpLen = le16(b, ndp + 4);
            int next = le16(b, ndp + 6);
            for (int q = ndp + 8; q + 4 <= ndp + ndpLen && q + 4 <= limit; q += 4) {
                int idx = le16(b, q), len = le16(b, q + 2);
                if (idx == 0 || len == 0) break;
                if (idx + len <= limit) listener.onFrame(b, idx, len);
            }
            if (next == ndp) return;
            ndp = next;
        }
    }

    private void notifyLoop() {
        byte[] buf = new byte[64];
        while (running) {
            UsbDeviceConnection c = conn;
            if (c == null) break;
            int n = c.bulkTransfer(plan.notify, buf, buf.length, 1000);
            if (n < 8 || (buf[0] & 0xFF) != REQ_IN_CLASS_IFACE) continue;
            int code = buf[1] & 0xFF;
            if (code == NOTIFY_NETWORK_CONNECTION) {
                listener.onLinkChange(le16(buf, 2) != 0);
            } else if (code == NOTIFY_SPEED_CHANGE && n >= 16) {
                listener.onLinkSpeed(le32(buf, 8), le32(buf, 12));
            }
        }
    }

    /** Transmits one Ethernet frame (used only for the optional LLDP announcement). */
    public synchronized boolean send(byte[] frame) {
        if (!running || conn == null) return false;
        byte[] eth = frame;
        if (eth.length < 60) {
            eth = new byte[60];
            System.arraycopy(frame, 0, eth, 0, frame.length);
        }
        byte[] out;
        if (plan.mode == Mode.ECM) {
            out = eth;
        } else if (plan.mode == Mode.ASIX) {
            out = new byte[8 + eth.length];
            putLe32(out, 0, eth.length);
            int maxPacket = plan.out.getMaxPacketSize() > 0 ? plan.out.getMaxPacketSize() : 512;
            if (out.length % maxPacket == 0) putLe32(out, 4, 0x80008000); // ask the chip to pad
            System.arraycopy(eth, 0, out, 8, eth.length);
        } else {
            int dg = 28; // NTH16 (12) + NDP16 (16)
            out = new byte[dg + eth.length];
            out[0] = 'N'; out[1] = 'C'; out[2] = 'M'; out[3] = 'H';
            putLe16(out, 4, 12);
            putLe16(out, 6, ntbSequence++ & 0xFFFF);
            putLe16(out, 8, out.length);
            putLe16(out, 10, 12);
            out[12] = 'N'; out[13] = 'C'; out[14] = 'M'; out[15] = '0';
            putLe16(out, 16, 16);
            putLe16(out, 18, 0);
            putLe16(out, 20, dg);
            putLe16(out, 22, eth.length);
            System.arraycopy(eth, 0, out, dg, eth.length);
        }
        return conn.bulkTransfer(plan.out, out, out.length, 1000) == out.length;
    }

    // ------------------------------------------------------------------ ASIX AX88179 family (vendor mode)

    private static final int AX_ACCESS_MAC = 0x01, AX_ACCESS_PHY = 0x02;
    private static final int AX_LINK_STATUS = 0x02, AX_RX_CTL = 0x0b, AX_NODE_ID = 0x10,
            AX_MEDIUM_STATUS_MODE = 0x22, AX_PHYPWR_RSTCTL = 0x26, AX_RX_BULKIN_QCTRL = 0x2e,
            AX_CLK_SELECT = 0x33, AX_PAUSE_WATERLVL_HIGH = 0x54, AX_PAUSE_WATERLVL_LOW = 0x55;
    private static final int AX_USB_SS = 0x04, AX_USB_HS = 0x02;
    private static final int AX_PHY_ID = 0x03, MII_BMCR = 0x00, GMII_PHY_PHYSR = 0x11;
    private static final int AX_MEDIUM_GIGAMODE = 0x01, AX_MEDIUM_FULL_DUPLEX = 0x02, AX_MEDIUM_EN_125MHZ = 0x08,
            AX_MEDIUM_RXFLOW_CTRLEN = 0x10, AX_MEDIUM_TXFLOW_CTRLEN = 0x20, AX_MEDIUM_RECEIVE_EN = 0x100,
            AX_MEDIUM_PS = 0x200;
    /** Bulk-in aggregation settings from the Linux driver: [SS/gigabit, HS/gigabit, 100M, slow]. */
    private static final byte[][] AX_BULKIN_SIZE = {
            {7, 0x4f, 0, 0x12, (byte) 0xff},
            {7, 0x20, 3, 0x16, (byte) 0xff},
            {7, (byte) 0xae, 7, 0x18, (byte) 0xff},
            {7, (byte) 0xcc, 0x4c, 0x18, 8},
    };
    private int axUsbSpeed;
    private int axMedium = -1;

    private int axRead(int cmd, int value, int index, byte[] buf, int len) {
        return conn.controlTransfer(0xC0, cmd, value, index, buf, len, 1000);
    }

    private boolean axWrite(int cmd, int value, int index, byte[] data) {
        return conn.controlTransfer(0x40, cmd, value, index, data, data == null ? 0 : data.length, 1000) >= 0;
    }

    private boolean axWriteMac(int reg, byte[] data) { return axWrite(AX_ACCESS_MAC, reg, data.length, data); }

    private boolean axWriteMac16(int reg, int v) { return axWriteMac(reg, new byte[]{(byte) v, (byte) (v >> 8)}); }

    private int axReadPhy(int reg) {
        byte[] b = new byte[2];
        return axRead(AX_ACCESS_PHY, AX_PHY_ID, reg, b, 2) == 2 ? le16(b, 0) : -1;
    }

    private void startAsix() throws IOException {
        if (device.getConfigurationCount() > 1) conn.setConfiguration(plan.config);
        if (!conn.claimInterface(plan.control, true)) {
            close();
            throw new IOException("Could not claim the ASIX adapter's interface");
        }
        status("Initializing ASIX adapter…");
        // Power-cycle the PHY and select clocks (as in ax88179_reset)
        if (!axWriteMac16(AX_PHYPWR_RSTCTL, 0)) {
            close();
            throw new IOException("The ASIX adapter did not accept commands");
        }
        axWriteMac16(AX_PHYPWR_RSTCTL, 0x0020); // IPRL: release PHY reset
        sleep(200);
        axWriteMac(AX_CLK_SELECT, new byte[]{0x03}); // ACS | BCS
        sleep(100);

        byte[] mac = new byte[6];
        if (axRead(AX_ACCESS_MAC, AX_NODE_ID, 6, mac, 6) == 6 && (mac[0] & 1) == 0
                && !(mac[0] == 0 && mac[1] == 0 && mac[2] == 0 && mac[3] == 0 && mac[4] == 0 && mac[5] == 0)) {
            macAddress = mac;
        }

        byte[] ls = new byte[1];
        axRead(AX_ACCESS_MAC, AX_LINK_STATUS, 1, ls, 1);
        axUsbSpeed = ls[0] & 0xFF;
        axWriteMac(AX_RX_BULKIN_QCTRL, AX_BULKIN_SIZE[(axUsbSpeed & AX_USB_SS) != 0 ? 0 : (axUsbSpeed & AX_USB_HS) != 0 ? 1 : 3]);
        axWriteMac(AX_PAUSE_WATERLVL_LOW, new byte[]{0x34});
        axWriteMac(AX_PAUSE_WATERLVL_HIGH, new byte[]{0x52});

        // RX: drop CRC errors, 2-byte IP alignment header, start, accept our MAC, all multicast,
        // broadcast and promiscuous (CDP/LLDP use link-local multicast addresses)
        axWriteMac16(AX_RX_CTL, 0x0100 | 0x0200 | 0x0080 | 0x0020 | 0x0002 | 0x0008 | 0x0001);
        axMedium = -1;
        axWriteMac16(AX_MEDIUM_STATUS_MODE, AX_MEDIUM_RECEIVE_EN | AX_MEDIUM_TXFLOW_CTRLEN | AX_MEDIUM_RXFLOW_CTRLEN
                | AX_MEDIUM_FULL_DUPLEX | AX_MEDIUM_GIGAMODE);

        // Restart auto-negotiation
        int bmcr = axReadPhy(MII_BMCR);
        if (bmcr >= 0) {
            int v = (bmcr | 0x1000 | 0x0200) & ~0x0800; // AN enable + restart, clear power-down
            axWrite(AX_ACCESS_PHY, AX_PHY_ID, MII_BMCR, new byte[]{(byte) v, (byte) (v >> 8)});
        }

        rxBufferSize = 32 * 1024;
        running = true;
        rxThread = new Thread(this::rxLoop, "usb-eth-rx");
        rxThread.start();
        linkThread = new Thread(this::asixLinkLoop, "usb-eth-link");
        linkThread.start();
        status("Listening on " + describe(device) + " [ASIX native mode]. Waiting for link…");
    }

    /** Polls the PHY once a second and programs the MAC's medium mode to match the negotiated link. */
    private void asixLinkLoop() {
        boolean wasUp = false;
        boolean first = true;
        while (running) {
            int physr;
            synchronized (this) {
                physr = conn == null ? -1 : axReadPhy(GMII_PHY_PHYSR);
            }
            if (physr >= 0) {
                boolean up = (physr & 0x0400) != 0;
                if (up) {
                    int mode = AX_MEDIUM_RECEIVE_EN | AX_MEDIUM_TXFLOW_CTRLEN | AX_MEDIUM_RXFLOW_CTRLEN;
                    int speedBits = physr & 0xC000;
                    byte[] q;
                    long speed;
                    if (speedBits == 0x8000) {
                        mode |= AX_MEDIUM_GIGAMODE | AX_MEDIUM_EN_125MHZ;
                        q = AX_BULKIN_SIZE[(axUsbSpeed & AX_USB_SS) != 0 ? 0 : (axUsbSpeed & AX_USB_HS) != 0 ? 1 : 3];
                        speed = 1_000_000_000L;
                    } else if (speedBits == 0x4000) {
                        mode |= AX_MEDIUM_PS;
                        q = AX_BULKIN_SIZE[(axUsbSpeed & (AX_USB_SS | AX_USB_HS)) != 0 ? 2 : 3];
                        speed = 100_000_000L;
                    } else {
                        q = AX_BULKIN_SIZE[3];
                        speed = 10_000_000L;
                    }
                    if ((physr & 0x2000) != 0) mode |= AX_MEDIUM_FULL_DUPLEX;
                    if (mode != axMedium) {
                        synchronized (this) {
                            if (conn != null) {
                                axWriteMac(AX_RX_BULKIN_QCTRL, q);
                                axWriteMac16(AX_MEDIUM_STATUS_MODE, mode);
                            }
                        }
                        axMedium = mode;
                        listener.onLinkSpeed(speed, speed);
                    }
                }
                if (up != wasUp || first) {
                    if (!up) axMedium = -1;
                    listener.onLinkChange(up);
                    wasUp = up;
                    first = false;
                }
            }
            sleep(1000);
        }
    }

    /**
     * An ASIX bulk-in transfer holds one or more frames followed by a header table. The last 4 bytes
     * give the packet count and the table offset; each table entry gives a frame length (which
     * includes a 2-byte alignment pad at the start of the frame). Frames start on 8-byte boundaries.
     */
    private void parseAsixRx(byte[] b, int n) {
        if (n < 8) return;
        long rxHdr = le32(b, n - 4);
        int count = (int) (rxHdr & 0xFFFF);
        int hdrOff = (int) ((rxHdr >>> 16) & 0xFFFF);
        if (count == 0 || hdrOff >= n - 4 || hdrOff + count * 4 > n) return; // same bounds as Linux
        int pos = 0;
        for (int i = 0; i < count; i++) {
            long ph = le32(b, hdrOff + i * 4);
            int len = (int) ((ph >>> 16) & 0x1FFF);
            int next = pos + ((len + 7) & ~7);
            boolean bad = (ph & 0x20000000L) != 0 || (ph & 0x80000000L) != 0 || len < 2 + 14;
            if (len == 0) continue;
            if (pos + len > hdrOff) break;
            if (!bad) listener.onFrame(b, pos + 2, len - 2);
            pos = next;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void status(String s) { listener.onCaptureStatus(s); }

    private static int le16(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }

    private static long le32(byte[] b, int i) { return (le16(b, i) & 0xFFFFL) | ((long) le16(b, i + 2) << 16); }

    private static void putLe32(byte[] b, int i, long v) {
        putLe16(b, i, (int) v);
        putLe16(b, i + 2, (int) (v >>> 16));
    }

    private static void putLe16(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >> 8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
