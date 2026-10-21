package com.portfinder.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.SecureRandom;
import java.util.TreeMap;

public class MainActivity extends Activity implements UsbEthernetCapture.Listener, NetworkTester.Listener {

    private static final String ACTION_USB_PERMISSION = "com.portfinder.app.USB_PERMISSION";
    private static final int TEST_SECONDS = 90;       // CDP default interval is 60 s, LLDP 30 s
    private static final int ANNOUNCE_EVERY_SECONDS = 10;

    // Colors
    // Theme: charcoal surfaces, blue for actions, green/amber/red for status
    static final int BG = 0xFF1B1D21;
    static final int NAV_BG = 0xFF17191C;
    static final int CARD = 0xFF22252A;
    static final int BORDER = 0xFF363A41;
    static final int LINE = 0xFF5A6068;     // network-map links
    static final int TEXT = 0xFFF2F4F5;
    static final int MUTED = 0xFF9AA1A9;
    static final int BLUE = 0xFF4E8DF5;
    static final int ACCENT = 0xFF5DBB63;   // good
    static final int WARN = 0xFFF0B03F;
    static final int ERROR = 0xFFE5544B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbEthernetCapture capture;
    private LldpAnnouncer announcer;

    private final LinkedHashMap<String, NeighborInfo> neighbors = new LinkedHashMap<>();
    private volatile long framesSeen;
    private boolean testing;
    private boolean startAfterPermission;
    private int startRetries;
    private boolean linkUp;
    /** True from Run Test until the link is up and settled; nothing is sent or timed until then. */
    private boolean waitingForLink;
    /** Whether the adapter has reported link state at all (some CDC adapters never do). */
    private boolean linkReported;
    private boolean noLink, linkAssumed;
    private long linkUpSince;
    private long captureStartedAt;
    private TestProgress progress;
    private static final int LINK_WAIT_SECONDS = 45;
    private static final long LINK_SETTLE_MS = 2000;
    private static final int STEP_ADAPTER = 0, STEP_LINK = 1;
    private long testStartedAt;
    private String linkState = "";
    private boolean discoveryDone, netDone;

    // Network test state (UI thread only)
    private volatile NetworkTester net;
    private NetworkTester.DhcpLease lease;
    private String dhcpFailure;
    private String gatewayMac;
    private NetworkTester.PingResult gatewayPing, targetPing;
    private NetworkTester.DnsResult dnsResult;
    /** Traceroute lines by TTL, snapshotted on the worker thread. */
    private final TreeMap<Integer, String> hopLines = new TreeMap<>();
    private Boolean traceReached;
    private String traceNote = "";
    private String netStatus = "";

    // Views
    private TextView adapterText, usbText, linkText, statusText, timerText;
    private View lanView, wlanView;
    private WifiPanel wifiPanel;
    private SpeedTestCard lanSpeed;
    /** Passive analysis of every frame the port test receives (Switch, Traffic, Findings, .pcap). */
    final FrameAnalyzer analyzer = new FrameAnalyzer();
    private FindingsCard findingsCard;
    private ConnectionTestCard lanConn;
    private LinearLayout overviewBox, switchBox, trafficBox, connBox;
    private final List<TextView> subTabs = new ArrayList<>();
    private int subTab;
    private FrameAnalyzer.Report lastReport;
    private Findings lastFindings;
    private HistoryStore history;
    private HistoryPanel historyPanel;
    private View historyView;
    private EditText dropLabelEdit;
    private int currentTab;
    private TextView switchText, portText, vlanText, viaText;
    private LinearLayout headlineCard, resultsBox, netBox;
    private Button runButton, shareButton;
    private CheckBox keepListeningBox, announceBox, netTestsBox;
    private EditText targetEdit;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        history = new HistoryStore(new java.io.File(getFilesDir(), "history.txt"));
        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        host.addView(buildUi());
        setContentView(host);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        handleLaunchIntent(getIntent());
        if (savedInstanceState == null) {
            // Brief splash on launch; the first-run terms notice comes after it
            AppIcon.showSplash(this, host, () -> LegalNotice.showIfNeeded(this));
        } else {
            LegalNotice.showIfNeeded(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!testing) refreshAdapter();
        if (currentTab == 1) wifiPanel.onShow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiPanel.onHide();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WifiPanel.PERMISSION_REQUEST) wifiPanel.onPermissionsResult();
    }

    @Override
    protected void onDestroy() {
        stopTest(null, false);
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

    private void handleLaunchIntent(Intent intent) {
        // Launched because the adapter was plugged in: Android has already granted permission.
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            refreshAdapter();
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice d = deviceExtra(intent);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && startAfterPermission) {
                    startAfterPermission = false;
                    startCapture();
                } else if (!granted) {
                    startAfterPermission = false;
                    setStatus("USB permission was denied. Tap Run Test to ask again.", ERROR);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (!testing) refreshAdapter();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (testing && capture != null && d != null && d.getDeviceId() == capture.getDevice().getDeviceId()) {
                    stopTest("Adapter was unplugged.", false);
                }
                if (!testing) refreshAdapter();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    // ------------------------------------------------------------------ adapter selection

    private void refreshAdapter() {
        device = null;
        List<UsbDevice> others = new ArrayList<>();
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (UsbEthernetCapture.findPlan(d) != null) {
                if (device == null) device = d;
            } else {
                others.add(d);
            }
        }
        usbText.setText("");
        usbText.setVisibility(View.GONE);
        if (device != null) {
            UsbEthernetCapture.Plan plan = UsbEthernetCapture.findPlan(device);
            String how = plan.mode == UsbEthernetCapture.Mode.ASIX ? "ASIX native driver" : "standard USB " + plan.mode + " mode";
            adapterText.setText(UsbEthernetCapture.describe(device) + "\nSupported (" + how + ")");
            adapterText.setTextColor(TEXT);
            runButton.setEnabled(true);
            runButton.setAlpha(1f);
        } else if (!others.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (UsbDevice d : others) sb.append(UsbEthernetCapture.describe(d)).append('\n');
            sb.append("None of these is a supported Ethernet adapter. Supported: adapters with the standard "
                    + "USB CDC-ECM/NCM mode (most Realtek RTL8152/8153/8156), and ASIX AX88179/AX88179A.");
            adapterText.setText(sb.toString());
            adapterText.setTextColor(WARN);
            showUsbDetails(others);
            runButton.setEnabled(false);
            runButton.setAlpha(0.4f);
        } else {
            adapterText.setText("No USB Ethernet adapter detected.\nPlug one into the phone (USB-C or OTG), then connect the network drop to it.");
            adapterText.setTextColor(MUTED);
            runButton.setEnabled(false);
            runButton.setAlpha(0.4f);
        }
        linkText.setText("");
    }

    /** Shows the raw USB layout so an unsupported or failing adapter can be diagnosed from a screenshot. */
    private void showUsbDetails(List<UsbDevice> devices) {
        StringBuilder sb = new StringBuilder("USB details\n");
        for (UsbDevice d : devices) {
            sb.append(String.format(Locale.US, "%04x:%04x class %02x\n", d.getVendorId(), d.getProductId(), d.getDeviceClass()));
            sb.append(UsbEthernetCapture.usbLayout(d)).append('\n');
        }
        usbText.setText(sb.toString().trim());
        usbText.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ test control

    /** "About & legal" link shown at the bottom of tabs. */
    TextView aboutLink() {
        TextView t = text("About & legal  ·  Use only on networks you're authorized to test", 13, BLUE, false);
        t.setPadding(dp(2), dp(14), 0, dp(6));
        t.setOnClickListener(v -> LegalNotice.showFull(this, false));
        return t;
    }

    private void onRunPressed() {
        if (testing) {
            stopTest("Stopped.", neighbors.isEmpty() && lease == null);
            return;
        }
        startRetries = 0;
        requestOrStart();
    }

    private void requestOrStart() {
        refreshAdapter();
        if (device == null) return;
        if (!usbManager.hasPermission(device)) {
            startAfterPermission = true;
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, i, flags));
            setStatus("Allow USB access to the adapter when Android asks.", MUTED);
            return;
        }
        startCapture();
    }

    private void handleStartFailure(UsbDevice failed, String message) {
        stopTest(null, false);
        if (startRetries < 2) {
            // The adapter often re-enumerates while Android's own driver lets go of it; look it up again and retry.
            startRetries++;
            setStatus("Couldn't open the adapter, retrying (" + startRetries + "/2)…", MUTED);
            ui.postDelayed(this::requestOrStart, 1500);
            return;
        }
        startRetries = 0;
        setStatus("Could not start: " + message + ".\n• Unplug the adapter (and any hub or dock it's in), plug it "
                + "straight into the phone if you can, then tap Run Test again.\n• If it keeps failing, send a "
                + "screenshot of the USB details below.", ERROR);
        List<UsbDevice> all = new ArrayList<>(usbManager.getDeviceList().values());
        if (all.isEmpty() && failed != null) all.add(failed);
        showUsbDetails(all);
    }

    private void startCapture() {
        if (device == null || testing) return;
        UsbEthernetCapture.Plan plan = UsbEthernetCapture.findPlan(device);
        if (plan == null) return;

        neighbors.clear();
        framesSeen = 0;
        linkState = "";
        linkUp = false;
        linkReported = false;
        noLink = false;
        linkAssumed = false;
        linkUpSince = 0;
        analyzer.reset();
        lastReport = null;
        lastFindings = null;
        findingsCard.reset();
        renderAnalysis();
        captureStartedAt = 0;
        waitingForLink = true;
        discoveryDone = false;
        netDone = !netTestsBox.isChecked();
        resetNetResults();
        renderResults();
        renderNet();
        testing = true;
        testStartedAt = System.currentTimeMillis();
        runButton.setText("Stop");
        shareButton.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("Starting…", MUTED);
        progress.show(true);
        renderProgress();

        announcer = announceBox.isChecked() ? new LldpAnnouncer() : null;
        final UsbEthernetCapture c = new UsbEthernetCapture(usbManager, device, plan, this);
        capture = c;
        new Thread(() -> {
            try {
                c.start();
                ui.post(() -> {
                    if (capture != c) return;
                    startRetries = 0;
                    captureStartedAt = System.currentTimeMillis();
                    setStatus("Adapter ready. Waiting for link…", MUTED);
                    tick();
                });
            } catch (IOException | RuntimeException e) {
                final UsbDevice failed = c.getDevice();
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                ui.post(() -> {
                    if (capture == c) handleStartFailure(failed, msg);
                });
            }
        }, "capture-start").start();
    }

    private void startNetworkTests(UsbEthernetCapture c) {
        byte[] mac = c.getMacAddress();
        if (mac == null) {
            mac = new byte[6];
            new SecureRandom().nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // locally administered
        }
        NetworkTester.Config cfg = new NetworkTester.Config();
        // ~30 s of DHCP: switch ports without PortFast take up to 30 s to start forwarding
        cfg.dhcpAttempts = 6;
        cfg.dhcpTimeoutMs = 5000;
        String t = targetEdit.getText().toString().trim();
        if (!t.isEmpty()) cfg.target = t;
        analyzer.setOwnMac(FrameAnalyzer.mac(mac, 0));
        final NetworkTester n = new NetworkTester(frame -> {
            analyzer.recordSent(frame, System.currentTimeMillis());
            return c.send(frame);
        }, mac, cfg, this);
        net = n;
        netStatus = "Client MAC " + n.macString();
        renderNet();
        new Thread(n::run, "net-tests").start();
    }

    private void resetNetResults() {
        lease = null;
        dhcpFailure = null;
        gatewayMac = null;
        gatewayPing = targetPing = null;
        dnsResult = null;
        hopLines.clear();
        traceReached = null;
        traceNote = "";
        netStatus = "";
    }

    /** Called when either half of the test finishes; ends the run once both are done. */
    private void maybeFinish() {
        if (!testing || !discoveryDone || !netDone) return;
        stopTest("Done.", true);
    }

    /**
     * Ends the test. message: shown in the status line (null = silent). advise: if no CDP/LLDP was
     * heard, show troubleshooting advice instead.
     */
    private void stopTest(String message, boolean advise) {
        if (!testing && capture == null) return;
        testing = false;
        startAfterPermission = false;
        ui.removeCallbacks(tickRunnable);
        final NetworkTester n = net;
        net = null;
        final UsbEthernetCapture c = capture;
        capture = null;
        if (n != null) n.stop();
        if (c != null) new Thread(() -> {
            try {
                Thread.sleep(n != null ? 300 : 0); // let the DHCP release go out first
            } catch (InterruptedException ignored) {
            }
            c.stop();
        }, "capture-stop").start();
        waitingForLink = false;
        runButton.setText("Run Test");
        timerText.setText("");
        renderProgress();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (advise && neighbors.isEmpty()) {
            setStatus(noResultAdvice(), WARN);
        } else if (message != null) {
            setStatus(message, neighbors.isEmpty() ? WARN : ACCENT);
        }
        renderNet();
        renderAnalysis();
        shareButton.setVisibility(neighbors.isEmpty() && lease == null && dhcpFailure == null ? View.GONE : View.VISIBLE);
        if (!neighbors.isEmpty() || lease != null || dhcpFailure != null) saveLanTest();
    }

    /** Saves the finished port test to History. */
    private void saveLanTest() {
        saveToHistory(lanEntry());
    }

    /** The current LAN results as a History entry (also used for image sharing). */
    private HistoryStore.Entry lanEntry() {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.type = HistoryStore.LAN;
        e.label = dropLabel();
        NeighborInfo b = bestNeighbor();
        e.title = b != null ? b.headlineName() + " · " + b.headlinePort() : "No CDP/LLDP heard";
        List<String> parts = new ArrayList<>();
        String vlan = "";
        for (NeighborInfo n : neighbors.values()) {
            if (!n.nativeVlan.isEmpty() && (vlan.isEmpty() || NeighborInfo.CDP.equals(n.protocol))) vlan = n.nativeVlan;
        }
        if (!vlan.isEmpty()) parts.add("VLAN " + vlan);
        if (lease != null) parts.add(lease.cidr());
        else if (dhcpFailure != null) parts.add("DHCP failed");
        if (targetPing != null) parts.add(targetPing.received > 0 ? "Internet OK" : "No internet");
        String speed = linkText.getText() == null ? "" : linkText.getText().toString();
        if (speed.startsWith("Link up · ")) parts.add(speed.substring(10));
        if (lastFindings != null && !lastFindings.items.isEmpty()) {
            int bad = lastFindings.count(Findings.BAD), warn = lastFindings.count(Findings.WARN);
            parts.add(bad > 0 ? bad + (bad == 1 ? " problem" : " problems") : warn + (warn == 1 ? " warning" : " warnings"));
        }
        e.summary = String.join("  ·  ", parts);
        boolean noInternet = targetPing != null && targetPing.received == 0;
        e.status = dhcpFailure != null || (b == null && lease == null) ? HistoryStore.FAIL
                : b == null || noInternet || (lastFindings != null && !lastFindings.items.isEmpty()) ? HistoryStore.WARN : HistoryStore.OK;
        e.report = lanReport();
        return e;
    }

    /** Adds a finished test to History (called by the LAN, Wi-Fi and speed tests). */
    void saveToHistory(HistoryStore.Entry e) {
        history.add(e);
        if (historyPanel != null) historyPanel.refresh();
        android.widget.Toast.makeText(this, "Saved to History", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Label for a saved speed test: the drop label on Ethernet, the network name on Wi-Fi. */
    String speedTestLabel(String networkLabel) {
        return "Ethernet".equals(networkLabel) ? dropLabel() : wifiPanel.headerTitle();
    }

    /** The optional drop/location label typed on the LAN tab. */
    String dropLabel() {
        return dropLabelEdit == null || dropLabelEdit.getText() == null ? "" : dropLabelEdit.getText().toString().trim();
    }

    private final Runnable tickRunnable = this::tick;

    private void tick() {
        ui.removeCallbacks(tickRunnable);
        if (!testing) return;
        long now = System.currentTimeMillis();
        if (waitingForLink) {
            if (capture != null && captureStartedAt > 0) {
                long waited = (now - captureStartedAt) / 1000;
                if (linkUp && linkUpSince > 0 && now - linkUpSince >= LINK_SETTLE_MS) {
                    onLinkReady();
                } else if (!linkReported && framesSeen > 0 && now - captureStartedAt >= 4000) {
                    // This adapter doesn't report link state, but traffic is arriving, so the link is up
                    linkAssumed = true;
                    onLinkReady();
                } else if (waited >= LINK_WAIT_SECONDS) {
                    noLink = true;
                    stopTest(null, false);
                    setStatus("No link after " + LINK_WAIT_SECONDS + " s, so the test didn't run.\n"
                            + "• Check the cable and that it's seated in the jack and the adapter.\n"
                            + "• The drop may be unpatched, or the switch port shut down or err-disabled.\n"
                            + "• Try a known-good cable, then tap Run Test again.", ERROR);
                    return;
                } else {
                    timerText.setText(String.format(Locale.US, "Waiting for link…  %d / %d s", waited, LINK_WAIT_SECONDS));
                }
            } else {
                timerText.setText("Taking over the USB adapter…");
            }
            if (waitingForLink) {
                renderProgress();
                ui.postDelayed(tickRunnable, 500);
                return;
            }
        }
        long elapsed = (now - testStartedAt) / 1000;
        boolean keep = keepListeningBox.isChecked();
        if (!discoveryDone && !keep && elapsed >= TEST_SECONDS) {
            discoveryDone = true;
            maybeFinish();
            if (!testing) return;
        }
        if (announcer != null && capture != null && elapsed % ANNOUNCE_EVERY_SECONDS == 0) {
            final UsbEthernetCapture c = capture;
            final byte[] frame = announcer.buildFrame();
            new Thread(() -> c.send(frame), "lldp-tx").start();
        }
        String what = discoveryDone ? "Running network tests…" : "Listening for CDP/LLDP…";
        String remaining = keep || discoveryDone ? elapsed + " s" : elapsed + " / " + TEST_SECONDS + " s";
        timerText.setText(String.format(Locale.US, "%s %s   ·   %,d frames received", what, remaining, framesSeen));
        renderProgress();
        renderAnalysis();
        ui.postDelayed(tickRunnable, 1000);
    }

    /** The link is up and settled: start the CDP/LLDP clock and the network tests. */
    private void onLinkReady() {
        if (!waitingForLink || !testing || capture == null) return;
        waitingForLink = false;
        testStartedAt = System.currentTimeMillis();
        if (neighbors.isEmpty()) setStatus(linkAssumed ? "Traffic received. Listening for CDP/LLDP…" : "Link up. Listening for CDP/LLDP…", MUTED);
        if (netTestsBox.isChecked()) startNetworkTests(capture);
    }

    /**
     * The "getting ready" screen: shown only while the app takes over the adapter and waits for link.
     * Once the link is ready it hides, and the results appear exactly as before.
     */
    private void renderProgress() {
        if (progress == null) return;
        boolean preparing = testing && (captureStartedAt == 0 || waitingForLink);
        progress.show(preparing || noLink);
        if (!preparing && !noLink) return;
        long now = System.currentTimeMillis();
        if (captureStartedAt == 0) progress.set(STEP_ADAPTER, TestProgress.ACTIVE, "Taking over the USB adapter…");
        else progress.set(STEP_ADAPTER, TestProgress.DONE, "Ready");
        if (noLink) {
            progress.set(STEP_LINK, TestProgress.FAIL, "No link after " + LINK_WAIT_SECONDS + " s");
        } else if (captureStartedAt == 0) {
            progress.set(STEP_LINK, TestProgress.PENDING, "");
        } else {
            long waited = (now - captureStartedAt) / 1000;
            progress.set(STEP_LINK, TestProgress.ACTIVE, linkUp ? "Link detected, letting it settle…"
                    : waited < 6 ? "The adapter resets when the test starts; link usually returns in 3–5 s."
                    : "Still waiting… " + waited + " s. Check the cable and the jack.");
        }
    }

    private String noResultAdvice() {
        if (framesSeen == 0) {
            return "No CDP or LLDP heard, and no Ethernet frames at all.\n"
                    + "• Check the link LED on the adapter: the drop may be dead or unpatched.\n"
                    + "• Unplug and replug the cable after tapping Run Test.\n"
                    + "• Some phones block taking over the adapter; try another adapter or phone.";
        }
        return String.format(Locale.US, "No CDP or LLDP heard in %d s, but %,d other frames were received, so the "
                + "link works.\n• The switch port may have CDP and LLDP disabled.\n"
                + "• Try again with \"Announce via LLDP-MED\" on.\n"
                + "• Use \"Keep listening\" if the switch uses a long advertisement interval.", (System.currentTimeMillis() - testStartedAt) / 1000, framesSeen);
    }

    // ------------------------------------------------------------------ capture callbacks (background threads)

    @Override
    public void onFrame(byte[] buf, int off, int len) {
        framesSeen++;
        analyzer.onFrame(buf, off, len, System.currentTimeMillis());
        NetworkTester t = net;
        if (t != null) t.onFrame(buf, off, len);
        NeighborInfo n = DiscoveryParser.parse(buf, off, len);
        if (n == null) return;
        final LldpAnnouncer a = announcer;
        if (a != null && a.macString().equals(n.sourceMac)) return;
        n.receivedAtMillis = System.currentTimeMillis();
        ui.post(() -> {
            if (!testing) return;
            neighbors.put(n.key(), n);
            renderResults();
            setStatus("Heard " + n.protocol + " from " + n.headlineName(), ACCENT);
            renderNet(); // VLAN card depends on CDP/LLDP
            if (!keepListeningBox.isChecked() && hasBothProtocols() && !discoveryDone) {
                discoveryDone = true;
                maybeFinish();
            }
        });
    }

    @Override
    public void onLinkChange(boolean up) {
        ui.post(() -> {
            linkState = up ? "Link up" : "Link down";
            linkReported = true;
            if (up && !linkUp) linkUpSince = System.currentTimeMillis();
            if (!up) linkUpSince = 0;
            linkUp = up;
            boolean waiting = !up && waitingForLink && testing;
            linkText.setText(up ? "Link up" : waiting ? "Waiting for link…" : "Link down — check the cable and the drop");
            linkText.setTextColor(up ? ACCENT : waiting ? MUTED : ERROR);
            updateLanTopology();
            if (testing) renderProgress();
        });
    }

    @Override
    public void onLinkSpeed(long down, long up) {
        ui.post(() -> {
            String speed = down >= 1_000_000_000L ? (down / 1_000_000_000L) + " Gbps" : (down / 1_000_000L) + " Mbps";
            linkText.setText("Link up · " + speed);
            linkText.setTextColor(ACCENT);
            linkReported = true;
            if (!linkUp) linkUpSince = System.currentTimeMillis();
            linkUp = true;
            updateLanTopology();
        });
    }

    @Override
    public void onCaptureStatus(String message) {
        ui.post(() -> {
            if (testing) setStatus(message, MUTED);
        });
    }

    // ------------------------------------------------------------------ network test callbacks (worker thread)

    @Override
    public void onNetStatus(String message) {
        ui.post(() -> {
            if (!testing) return;
            netStatus = message;
            renderNet();
        });
    }

    @Override
    public void onDhcp(NetworkTester.DhcpLease l) {
        ui.post(() -> {
            lease = l;
            renderNet();
        });
    }

    @Override
    public void onDhcpFailed(String reason) {
        ui.post(() -> {
            dhcpFailure = reason;
            renderNet();
        });
    }

    @Override
    public void onGateway(String mac, NetworkTester.PingResult ping) {
        ui.post(() -> {
            gatewayMac = mac;
            gatewayPing = ping;
            renderNet();
        });
    }

    @Override
    public void onDns(NetworkTester.DnsResult r) {
        ui.post(() -> {
            dnsResult = r;
            renderNet();
        });
    }

    @Override
    public void onPing(NetworkTester.PingResult r) {
        ui.post(() -> {
            targetPing = r;
            renderNet();
        });
    }

    @Override
    public void onHop(NetworkTester.Hop hop) {
        final String line = hop.line(); // snapshot; the worker may still update the name later
        ui.post(() -> {
            hopLines.put(hop.ttl, line);
            renderNet();
        });
    }

    @Override
    public void onTraceDone(boolean reached, String note) {
        ui.post(() -> {
            traceReached = reached;
            traceNote = note;
            renderNet();
        });
    }

    @Override
    public void onNetFinished() {
        ui.post(() -> {
            if (!testing) return;
            netDone = true;
            netStatus = "Done";
            renderNet();
            if (!discoveryDone) {
                setStatus(neighbors.isEmpty() ? "Network tests done. Still listening for CDP/LLDP…"
                        : "Network tests done.", ACCENT);
            }
            maybeFinish();
        });
    }

    // ------------------------------------------------------------------ network results rendering

    private void renderNet() {
        renderProgress();
        updateLanTopology();
        netBox.removeAllViews();
        if (!netTestsBox.isChecked() && lease == null && dhcpFailure == null) return;
        boolean started = testing || lease != null || dhcpFailure != null;
        if (!started) return;

        // VLAN
        LinearLayout vlan = card();
        vlan.addView(label("VLAN"));
        NeighborInfo best = bestNeighbor();
        String access = "";
        String source = "";
        for (NeighborInfo n : neighbors.values()) {
            if (!n.nativeVlan.isEmpty()) {
                access = n.nativeVlan;
                source = NeighborInfo.CDP.equals(n.protocol) ? "CDP native VLAN" : "LLDP port VLAN ID";
                if (NeighborInfo.CDP.equals(n.protocol)) break;
            }
        }
        if (!access.isEmpty()) {
            vlan.addView(text("VLAN " + access, 26, ACCENT, true));
            addRow(vlan, "Source", source + " from the switch");
        } else {
            boolean waiting = testing && !discoveryDone;
            vlan.addView(text(waiting ? "Waiting for the switch to announce it…" : "Not advertised by the switch", 15,
                    waiting ? MUTED : WARN, false));
        }
        if (best != null && !best.voiceVlan.isEmpty()) addRow(vlan, "Voice VLAN", best.voiceVlan);
        if (lease != null) addRow(vlan, "Subnet (from DHCP)", lease.network());
        netBox.addView(vlan);

        // DHCP
        LinearLayout dhcp = card();
        dhcp.addView(label("DHCP"));
        if (lease != null) {
            dhcp.addView(text("✓  Address received", 17, ACCENT, true));
            addRow(dhcp, "IP address", lease.cidr());
            addRow(dhcp, "Subnet mask", lease.mask);
            addRow(dhcp, "Gateway", lease.gateway.isEmpty() ? "(none)" : lease.gateway
                    + (gatewayMac != null && !gatewayMac.isEmpty() ? "  ·  " + gatewayMac : ""));
            addRow(dhcp, "DNS servers", String.join(", ", lease.dns));
            addRow(dhcp, "Domain", lease.domain);
            addRow(dhcp, "DHCP server", lease.server);
            addRow(dhcp, "Relay (helper)", lease.relay);
            if (lease.leaseSeconds >= 0) addRow(dhcp, "Lease time", duration(lease.leaseSeconds));
            addRow(dhcp, "Time to lease", String.format(Locale.US, "%.1f s (offer after %.1f s)", lease.ackMs / 1000.0, lease.offerMs / 1000.0));
        } else if (dhcpFailure != null) {
            dhcp.addView(text("✗  No address", 17, ERROR, true));
            TextView t = text(dhcpFailure, 13, TEXT, false);
            t.setPadding(0, dp(6), 0, 0);
            dhcp.addView(t);
        } else {
            dhcp.addView(text(netStatus.isEmpty() ? "Waiting…" : netStatus, 14, MUTED, false));
        }
        netBox.addView(dhcp);
        if (lease == null) return;

        // Internet
        LinearLayout inet = card();
        inet.addView(label("INTERNET"));
        boolean inetOk = targetPing != null && targetPing.received > 0;
        if (targetPing != null) {
            inet.addView(text(inetOk ? "✓  Internet reachable" : "✗  No reply from the internet", 17, inetOk ? ACCENT : ERROR, true));
        } else {
            inet.addView(text(netStatus, 14, MUTED, false));
        }
        if (gatewayPing != null) addRow(inet, "Ping gateway", gatewayPing.summary());
        if (dnsResult != null) {
            String d;
            if (dnsResult.ok() && dnsResult.ms >= 0) {
                d = dnsResult.name + " → " + dnsResult.address + String.format(Locale.US, "  (%d ms via %s)", dnsResult.ms, dnsResult.server);
            } else if (dnsResult.ok()) {
                d = dnsResult.error;
            } else {
                d = "✗ " + dnsResult.error;
            }
            addRow(inet, "DNS lookup", d);
        }
        if (targetPing != null) addRow(inet, "Ping " + targetPing.target, targetPing.summary());
        netBox.addView(inet);

        // Traceroute
        if (hopLines.isEmpty() && traceReached == null) return;
        LinearLayout tr = card();
        tr.addView(label("TRACEROUTE"));
        StringBuilder sb = new StringBuilder();
        for (String line : hopLines.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        TextView lines = text(sb.toString(), 12, TEXT, false);
        lines.setTypeface(Typeface.MONOSPACE);
        lines.setTextIsSelectable(true);
        lines.setPadding(0, dp(6), 0, 0);
        tr.addView(lines);
        if (traceReached != null) {
            TextView note = text(traceNote, 13, traceReached ? ACCENT : WARN, false);
            note.setPadding(0, dp(8), 0, 0);
            tr.addView(note);
        } else {
            TextView note = text("Tracing…", 13, MUTED, false);
            note.setPadding(0, dp(8), 0, 0);
            tr.addView(note);
        }
        netBox.addView(tr);
    }

    private static String duration(long s) {
        if (s >= 86400 && s % 3600 == 0) return (s / 86400) + " d" + (s % 86400 != 0 ? " " + (s % 86400) / 3600 + " h" : "");
        if (s >= 3600) return (s / 3600) + " h " + (s % 3600) / 60 + " min";
        if (s >= 60) return (s / 60) + " min";
        return s + " s";
    }

    private boolean hasBothProtocols() {
        boolean cdp = false, lldp = false;
        for (NeighborInfo n : neighbors.values()) {
            if (NeighborInfo.CDP.equals(n.protocol)) cdp = true;
            else lldp = true;
        }
        return cdp && lldp;
    }

    // ------------------------------------------------------------------ rendering

    private NeighborInfo bestNeighbor() {
        NeighborInfo best = null;
        for (NeighborInfo n : neighbors.values()) {
            if (best == null) best = n;
            else if (n.looksLikeInfrastructure() && !best.looksLikeInfrastructure()) best = n;
            else if (NeighborInfo.CDP.equals(n.protocol) && !NeighborInfo.CDP.equals(best.protocol)
                    && n.looksLikeInfrastructure() == best.looksLikeInfrastructure()) best = n;
        }
        return best;
    }

    private void renderResults() {
        updateLanTopology();
        resultsBox.removeAllViews();
        NeighborInfo best = bestNeighbor();
        if (best == null) {
            headlineCard.setVisibility(View.GONE);
            return;
        }
        headlineCard.setVisibility(View.VISIBLE);
        switchText.setText(best.headlineName());
        portText.setText(best.headlinePort().isEmpty() ? "(not reported)" : best.headlinePort());
        List<String> v = new ArrayList<>();
        if (!best.nativeVlan.isEmpty()) v.add("Data VLAN " + best.nativeVlan);
        if (!best.voiceVlan.isEmpty()) v.add("Voice VLAN " + best.voiceVlan);
        if (!best.mgmtAddress.isEmpty()) v.add("Mgmt " + best.mgmtAddress);
        vlanText.setText(String.join("   ·   ", v));
        vlanText.setVisibility(v.isEmpty() ? View.GONE : View.VISIBLE);
        viaText.setText("via " + best.protocol + (neighbors.size() > 1 ? "  ·  " + neighbors.size() + " announcements below" : ""));

        for (NeighborInfo n : neighbors.values()) resultsBox.addView(neighborCard(n));
    }

    private View neighborCard(NeighborInfo n) {
        LinearLayout card = card();
        TextView title = text(n.protocol + "  ·  " + n.headlineName(), 15, TEXT, true);
        card.addView(title);
        addRow(card, "Port", n.headlinePort());
        for (Map.Entry<String, String> e : n.details.entrySet()) addRow(card, e.getKey(), e.getValue());
        addRow(card, "Source MAC", n.sourceMac);
        if (!n.frameVlanTag.isEmpty()) addRow(card, "Frame VLAN tag", n.frameVlanTag);
        return card;
    }

    void addRow(LinearLayout parent, String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        TextView l = text(label, 13, MUTED, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(value, 13, TEXT, false);
        v.setTextIsSelectable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        row.addView(v);
        parent.addView(row);
    }

    private String lanReport() {
        StringBuilder sb = new StringBuilder();
        String label = dropLabel();
        if (!label.isEmpty()) sb.append("Drop: ").append(label).append("\n\n");
        for (NeighborInfo n : neighbors.values()) sb.append(n.toText()).append('\n');
        if (neighbors.isEmpty()) sb.append("No CDP or LLDP heard.\n\n");
        if (lastFindings != null) {
            String ft = lastFindings.toText();
            if (!ft.isEmpty()) sb.append(ft).append('\n');
        }
        sb.append(networkText());
        String sw = AnalysisViews.switchText(lastReport);
        if (!sw.isEmpty()) sb.append('\n').append(sw);
        return sb.toString();
    }

    private void share() {
        StringBuilder sb = new StringBuilder("PortPerson results\n\n").append(lanReport());
        String conn = lanConn.shareText();
        if (!conn.isEmpty()) sb.append('\n').append(conn);
        String speed = lanSpeed.shareText();
        if (!speed.isEmpty()) sb.append('\n').append(speed);
        NeighborInfo b = bestNeighbor();
        String subject = b != null ? "PortPerson: " + b.headlineName() + " " + b.headlinePort() : "PortPerson results";
        HistoryStore.Entry e = lanEntry();
        if (!speed.isEmpty()) e.report = e.report + "\n" + speed;
        e.time = System.currentTimeMillis();
        ReportShare.choose(this, e, subject, sb.toString());
    }

    private String networkText() {
        StringBuilder sb = new StringBuilder();
        if (lease != null) {
            sb.append("DHCP\n");
            sb.append("  IP: ").append(lease.cidr()).append('\n');
            sb.append("  Gateway: ").append(lease.gateway);
            if (gatewayMac != null && !gatewayMac.isEmpty()) sb.append(" (").append(gatewayMac).append(')');
            sb.append('\n');
            sb.append("  DNS: ").append(String.join(", ", lease.dns)).append('\n');
            if (!lease.domain.isEmpty()) sb.append("  Domain: ").append(lease.domain).append('\n');
            sb.append("  DHCP server: ").append(lease.server);
            if (!lease.relay.isEmpty()) sb.append(" via relay ").append(lease.relay);
            sb.append("\n\n");
        } else if (dhcpFailure != null) {
            sb.append("DHCP: FAILED. ").append(dhcpFailure).append("\n\n");
        }
        if (gatewayPing != null) sb.append("Ping gateway: ").append(gatewayPing.summary()).append('\n');
        if (dnsResult != null) {
            sb.append("DNS: ").append(dnsResult.name).append(" -> ")
              .append(dnsResult.ok() ? dnsResult.address : "FAILED (" + dnsResult.error + ")").append('\n');
        }
        if (targetPing != null) sb.append("Ping ").append(targetPing.target).append(": ").append(targetPing.summary()).append("\n");
        if (!hopLines.isEmpty()) {
            sb.append("\nTraceroute\n");
            for (String line : hopLines.values()) sb.append(line).append('\n');
            if (!traceNote.isEmpty()) sb.append(traceNote).append('\n');
        }
        return sb.toString();
    }

    /**
     * The speed test needs real TCP/TLS, so it runs over Android's own Ethernet support: once the port
     * test releases the adapter, Android's driver picks it back up and gets an address. Background thread.
     */
    private Network acquireEthernet(java.util.function.BooleanSupplier stillWanted, java.util.function.Consumer<String> status) throws Exception {
        long start = System.currentTimeMillis();
        boolean waited = false;
        while (System.currentTimeMillis() - start < 30_000) {
            if (!stillWanted.getAsBoolean()) throw new Exception("Stopped.");
            Network n = ethernetNetwork();
            if (n != null) {
                if (waited) Thread.sleep(1500); // let the new link settle (DNS, routes)
                return n;
            }
            if (System.currentTimeMillis() - start > 3000 && usbManager.getDeviceList().isEmpty()) {
                throw new Exception("No USB Ethernet adapter is plugged in.");
            }
            waited = true;
            status.accept("Waiting for Android to bring the adapter up as Ethernet and get an address… "
                    + (System.currentTimeMillis() - start) / 1000 + " s");
            Thread.sleep(1000);
        }
        throw new Exception("Android didn't bring up Ethernet on the adapter within 30 s. Unplug and replug the "
                + "adapter and try again. (A few phones don't support USB Ethernet for regular networking.)");
    }

    @SuppressWarnings("deprecation")
    private Network ethernetNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof java.net.Inet4Address) return n;
            }
        }
        return null;
    }

    private LinearLayout vbox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        return b;
    }

    /** Shows one LAN sub-tab: 0 Overview, 1 Switch, 2 Traffic, 3 Connection. */
    void selectSubTab(int i) {
        subTab = i;
        View[] boxes = {overviewBox, switchBox, trafficBox, connBox};
        for (int k = 0; k < boxes.length; k++) {
            if (boxes[k] != null) boxes[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            if (k < subTabs.size()) {
                TextView t = subTabs.get(k);
                t.setBackground(k == i ? rounded(BLUE, dp(9)) : null);
                t.setTextColor(k == i ? Color.WHITE : MUTED);
            }
        }
        if (i == 1 || i == 2) fillAnalysisTab();
    }

    /** Re-reads the analyzer and refreshes the Findings card and the visible Switch/Traffic tab. */
    private void renderAnalysis() {
        if (findingsCard == null) return;
        FrameAnalyzer.Report r = analyzer.report();
        if (r.frames == 0 || waitingForLink) {
            if (!testing) findingsCard.show(lastFindings);
            else findingsCard.show(null);
            if (r.frames == 0) {
                lastReport = null;
                fillAnalysisTab();
                return;
            }
        }
        lastReport = r;
        Findings.Context c = new Findings.Context();
        c.dhcpOk = lease != null;
        c.dhcpFailed = dhcpFailure != null;
        c.gateway = lease != null ? lease.gateway : "";
        c.leaseIp = lease != null ? lease.ip : "";
        NeighborInfo b = bestNeighbor();
        c.cdpNativeVlan = b != null ? b.nativeVlan : "";
        c.seconds = r.durationMs / 1000;
        // Wait a few seconds of listening so a half-second sample doesn't raise or clear alarms
        if (testing && r.durationMs < 4000) return;
        lastFindings = Findings.evaluate(r, c);
        findingsCard.show(lastFindings);
        fillAnalysisTab();
    }

    private void fillAnalysisTab() {
        if (switchBox == null) return;
        if (subTab == 1) AnalysisViews.fillSwitch(this, switchBox, lastReport, rootHint());
        else if (subTab == 2) AnalysisViews.fillTraffic(this, trafficBox, lastReport, this::savePcap);
    }

    /** "IDF-CORE-1 (per CDP)" when the root bridge MAC matches a switch that sent CDP/LLDP. */
    private String rootHint() {
        if (lastReport == null || lastReport.stp.isEmpty()) return "";
        String root = lastReport.stp.get(0).rootMac;
        for (NeighborInfo n : neighbors.values()) {
            if (n.sourceMac != null && n.sourceMac.length() >= 14 && root.length() >= 14
                    && n.sourceMac.substring(0, 14).equalsIgnoreCase(root.substring(0, 14))) {
                return n.headlineName() + " (per " + n.protocol + ")";
            }
        }
        return "";
    }

    /** Name of a device seen during the port test, for the Connection Test result. */
    private String deviceName(String ip) {
        FrameAnalyzer.Report r = lastReport;
        if (r == null) return null;
        for (FrameAnalyzer.Device d : r.devices) if (d.ip.equals(ip)) return d.name;
        return null;
    }

    static String phoneName() {
        return PhoneModel.shortName(Build.MANUFACTURER, Build.MODEL);
    }

    /** Saves the frames from the last port test as a .pcap and opens the share sheet. */
    private void savePcap() {
        if (testing) {
            android.widget.Toast.makeText(this, "Stop the test first, or wait for it to finish.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "exports");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                java.io.File[] old = dir.listFiles((d, n) -> n.endsWith(".pcap"));
                if (old != null) for (java.io.File o : old) //noinspection ResultOfMethodCallIgnored
                    o.delete();
                String label = dropLabel().replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-|-$", "");
                java.io.File file = new java.io.File(dir, "portperson-" + (label.isEmpty() ? "" : label + "-")
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date()) + ".pcap");
                try (java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))) {
                    analyzer.writePcap(out);
                }
                ui.post(() -> {
                    android.net.Uri uri = ExportProvider.uriFor(this, file);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/vnd.tcpdump.pcap");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "PortPerson capture " + file.getName());
                    send.setClipData(android.content.ClipData.newRawUri("", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Save or share capture"));
                });
            } catch (Exception e) {
                ui.post(() -> android.widget.Toast.makeText(this, "Couldn't save the capture: " + e.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }, "pcap-save").start();
    }

    private void setStatus(String s, int color) {
        statusText.setText(s);
        statusText.setTextColor(color);
    }

    // ------------------------------------------------------------------ UI construction (no XML layouts)

    private Icons.IconView headerIcon;
    private TextView headerTitle;
    private Icons.TopologyView lanTopo;
    private View scanView;
    private final List<View[]> navItems = new ArrayList<>(); // {container, icon, label}

    @SuppressWarnings("deprecation")
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // Android 15 draws apps edge-to-edge: keep content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        // Header: icon + network name, centered
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(16), dp(14), dp(16), dp(6));
        headerIcon = new Icons.IconView(this, Icons.Type.ETHERNET, ACCENT);
        header.addView(headerIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        headerTitle = text("Ethernet", 21, TEXT, true);
        headerTitle.setPadding(dp(10), 0, 0, 0);
        headerTitle.setSingleLine(true);
        header.addView(headerTitle);
        root.addView(header);

        FrameLayout body = new FrameLayout(this);
        lanView = buildLanUi();
        wifiPanel = new WifiPanel(this);
        wlanView = wifiPanel.build();
        scanView = wifiPanel.buildScan();
        historyPanel = new HistoryPanel(this, history);
        historyView = historyPanel.build();
        ViewGroup.LayoutParams full = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        body.addView(lanView, full);
        body.addView(wlanView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(scanView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(historyView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom navigation
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(NAV_BG);
        nav.setPadding(0, dp(8), 0, dp(6));
        nav.addView(navItem(Icons.Type.ETHERNET, "LAN", 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navItem(Icons.Type.WIFI, "Wi-Fi", 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navItem(Icons.Type.RADAR, "Scan", 2), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navItem(Icons.Type.HISTORY, "History", 3), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(nav);

        selectTab(0);
        return root;
    }

    private View navItem(Icons.Type type, String label, int index) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        Icons.IconView icon = new Icons.IconView(this, type, MUTED);
        item.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(label, 12, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(3), 0, 0);
        item.addView(t);
        item.setOnClickListener(v -> selectTab(index));
        navItems.add(new View[]{item, icon, t});
        return item;
    }

    private static final Icons.Type[] NAV_ICONS = {Icons.Type.ETHERNET, Icons.Type.WIFI, Icons.Type.RADAR, Icons.Type.HISTORY};

    private void selectTab(int i) {
        currentTab = i;
        for (int k = 0; k < navItems.size(); k++) {
            int color = k == i ? BLUE : MUTED;
            ((Icons.IconView) navItems.get(k)[1]).set(NAV_ICONS[k], color);
            ((TextView) navItems.get(k)[2]).setTextColor(color);
        }
        lanView.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        wlanView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        scanView.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        historyView.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        if (i == 3) historyPanel.refresh();
        if (i == 1 || i == 2) wifiPanel.onShow();
        else wifiPanel.onHide();
        updateHeader();
    }

    /** Header shows what you're connected to on the current tab. */
    void updateHeader() {
        if (headerTitle == null) return;
        if (currentTab == 0) {
            NeighborInfo b = bestNeighbor();
            boolean linked = linkUp || lease != null || b != null;
            headerIcon.set(Icons.Type.ETHERNET, linked ? ACCENT : MUTED);
            headerTitle.setText(b != null ? b.headlineName() + " · " + b.headlinePort() : "Ethernet");
        } else if (currentTab == 1) {
            String ssid = wifiPanel.headerTitle();
            headerIcon.set(Icons.Type.WIFI, ssid.isEmpty() ? MUTED : ACCENT);
            headerTitle.setText(ssid.isEmpty() ? "Wi-Fi" : ssid);
        } else if (currentTab == 2) {
            headerIcon.set(Icons.Type.RADAR, BLUE);
            headerTitle.setText("Nearby Wi-Fi");
        } else {
            headerIcon.set(Icons.Type.HISTORY, BLUE);
            headerTitle.setText("Test History");
        }
    }

    /** This phone — switch — gateway — Internet, from whatever the LAN test has found so far. */
    private void updateLanTopology() {
        if (lanTopo == null) return;
        List<Icons.Node> nodes = new ArrayList<>();
        List<Icons.Link> links = new ArrayList<>();
        // Internet
        String inet;
        int inetColor;
        if (targetPing != null && targetPing.received > 0) {
            inet = "Online";
            inetColor = ACCENT;
        } else if (targetPing != null || (dhcpFailure != null && !testing)) {
            inet = "Unavailable";
            inetColor = ERROR;
        } else if (testing && lease != null) {
            inet = "Testing…";
            inetColor = MUTED;
        } else {
            inet = "—";
            inetColor = MUTED;
        }
        nodes.add(new Icons.Node(Icons.Type.GLOBE, 0xFFB9BEC5, "Internet", "", inet, inetColor));
        links.add(new Icons.Link(false, ERROR == inetColor ? ERROR : LINE));
        // Gateway
        String gw = lease != null && !lease.gateway.isEmpty() ? lease.gateway : "—";
        String gwStat = gatewayPing != null && gatewayPing.received > 0 ? String.format(Locale.US, "%.0f ms", gatewayPing.avgMs) : "";
        nodes.add(new Icons.Node(Icons.Type.GATEWAY, 0xFFD5DAE0, "Gateway", gw, gwStat, ACCENT));
        links.add(new Icons.Link(false, LINE));
        // Switch
        NeighborInfo b = bestNeighbor();
        if (b != null) {
            nodes.add(new Icons.Node(Icons.Type.SWITCH, 0xFFD5DAE0, b.headlineName(),
                    b.mgmtAddress.isEmpty() ? b.protocol : b.mgmtAddress, b.headlinePort(), BLUE));
        } else {
            nodes.add(new Icons.Node(Icons.Type.SWITCH, 0xFF6B7178, "Switch", testing ? "Listening…" : "—", "", MUTED));
        }
        boolean down = linkState.startsWith("Link down");
        links.add(new Icons.Link(false, down ? ERROR : LINE));
        // This phone
        String ip = lease != null ? lease.ip : dhcpFailure != null ? "No address" : "—";
        String speed = linkText != null && linkText.getText() != null ? linkText.getText().toString() : "";
        speed = speed.startsWith("Link up · ") ? speed.substring(10) : "";
        nodes.add(new Icons.Node(Icons.Type.PHONE, 0xFFD5DAE0, phoneName(), ip, speed,
                dhcpFailure != null ? ERROR : ACCENT));
        // Read left to right from where you are: this phone → switch/AP → gateway → Internet
        java.util.Collections.reverse(nodes);
        java.util.Collections.reverse(links);
        lanTopo.set(nodes, links);
        updateHeader();
    }

    Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 18 : 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setBackground(primary ? rounded(BLUE, dp(12)) : outlined(CARD, dp(12)));
        return b;
    }

    /** "Speed Test"-style section title with optional right-hand text. */
    LinearLayout sectionHeader(String title, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(22), dp(2), dp(4));
        TextView t = text(title, 20, TEXT, true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    /** A "speed factor" card: bold title, status icon on the right, then "Key value" pairs. */
    LinearLayout factorCard(String title, CharSequence body, int status) {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, 17, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icons.Type t = status == 0 ? Icons.Type.CHECK : status == 1 ? Icons.Type.WARN : Icons.Type.CROSS;
        top.addView(new Icons.IconView(this, t, statusColor(status)), new LinearLayout.LayoutParams(dp(26), dp(26)));
        c.addView(top);
        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(TEXT);
        b.setPadding(0, dp(6), 0, 0);
        c.addView(b);
        return c;
    }

    static int statusColor(int status) {
        return status == 0 ? ACCENT : status == 1 ? WARN : ERROR;
    }

    /** "Key value    Key value" with muted keys and bright values. */
    static CharSequence kv(String... pairs) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] == null || pairs[i + 1].isEmpty()) continue;
            if (sb.length() > 0) sb.append("     ");
            int k = sb.length();
            sb.append(pairs[i]).append(' ');
            sb.setSpan(new ForegroundColorSpan(MUTED), k, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(pairs[i + 1]);
        }
        return sb;
    }

    private View buildLanUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, 0, pad, pad);
        scroll.addView(col);

        lanTopo = new Icons.TopologyView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        col.addView(lanTopo, tlp);

        TextView portHeaderRight = text("CDP · LLDP", 14, MUTED, false);
        col.addView(sectionHeader("Port Test", portHeaderRight));

        runButton = button("Run Test", true);
        runButton.setOnClickListener(v -> onRunPressed());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        blp.topMargin = dp(8);
        col.addView(runButton, blp);

        progress = new TestProgress(this, "Adapter", "Link");
        col.addView(progress.view());

        LinearLayout opts = card();
        keepListeningBox = checkBox("Keep listening (don't stop after " + TEST_SECONDS + " s)");
        announceBox = checkBox("Announce via LLDP-MED (faster answer from many switches)");
        netTestsBox = checkBox("Network tests (DHCP, VLAN, internet, traceroute)");
        netTestsBox.setChecked(true);
        TextView dropLabelTitle = text("Drop label (optional)", 13, MUTED, false);
        dropLabelTitle.setPadding(dp(4), 0, 0, dp(4));
        opts.addView(dropLabelTitle);
        dropLabelEdit = new EditText(this);
        dropLabelEdit.setSingleLine(true);
        dropLabelEdit.setHint("e.g. Rm 204 jack A");
        dropLabelEdit.setHintTextColor(0xFF6B7178);
        dropLabelEdit.setTextColor(TEXT);
        dropLabelEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        dropLabelEdit.setBackground(outlined(BG, dp(10)));
        dropLabelEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.bottomMargin = dp(6);
        opts.addView(dropLabelEdit, dl);
        opts.addView(netTestsBox);
        opts.addView(keepListeningBox);
        opts.addView(announceBox);
        TextView targetLabel = text("Internet test target", 13, MUTED, false);
        targetLabel.setPadding(dp(4), dp(8), 0, dp(4));
        opts.addView(targetLabel);
        targetEdit = new EditText(this);
        targetEdit.setText("google.com");
        targetEdit.setSingleLine(true);
        targetEdit.setTextColor(TEXT);
        targetEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        targetEdit.setBackground(outlined(BG, dp(10)));
        targetEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        opts.addView(targetEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(opts);

        statusText = text("", 14, MUTED, false);
        statusText.setPadding(dp(2), dp(10), 0, 0);
        col.addView(statusText);
        timerText = text("", 13, MUTED, false);
        timerText.setPadding(dp(2), dp(4), 0, dp(4));
        col.addView(timerText);

        findingsCard = new FindingsCard(this, this::selectSubTab);
        col.addView(findingsCard.view());

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(outlined(CARD, dp(12)));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"Overview", "Switch", "Traffic", "Connection"};
        for (int i = 0; i < names.length; i++) {
            TextView t = text(names[i], 13, MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            final int idx = i;
            t.setOnClickListener(v -> selectSubTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(3);
            tabBar.addView(t, lp);
            subTabs.add(t);
        }
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbl.topMargin = dp(12);
        col.addView(tabBar, tbl);

        overviewBox = vbox();
        switchBox = vbox();
        trafficBox = vbox();
        connBox = vbox();
        col.addView(overviewBox);
        col.addView(switchBox);
        col.addView(trafficBox);
        col.addView(connBox);

        lanConn = new ConnectionTestCard(this, "Ethernet",
                () -> {
                    if (testing) stopTest("Port test stopped for the connection test.", false);
                },
                card -> acquireEthernet(card::isRunning, card::status));
        lanConn.setNameLookup(this::deviceName);
        connBox.addView(lanConn.build());

        headlineCard = card();
        headlineCard.setVisibility(View.GONE);
        headlineCard.addView(label("Switch"));
        switchText = text("", 20, TEXT, true);
        headlineCard.addView(switchText);
        TextView portLabel = label("Port");
        portLabel.setPadding(0, dp(10), 0, 0);
        headlineCard.addView(portLabel);
        portText = text("", 30, BLUE, true);
        portText.setTextIsSelectable(true);
        headlineCard.addView(portText);
        vlanText = text("", 14, TEXT, false);
        vlanText.setPadding(0, dp(6), 0, 0);
        headlineCard.addView(vlanText);
        viaText = text("", 12, MUTED, false);
        viaText.setPadding(0, dp(6), 0, 0);
        headlineCard.addView(viaText);
        overviewBox.addView(headlineCard);

        netBox = new LinearLayout(this);
        netBox.setOrientation(LinearLayout.VERTICAL);
        overviewBox.addView(netBox);

        LinearLayout adapterCard = card();
        adapterCard.addView(label("Adapter"));
        adapterText = text("", 14, TEXT, false);
        adapterCard.addView(adapterText);
        linkText = text("", 14, ACCENT, true);
        linkText.setPadding(0, dp(4), 0, 0);
        adapterCard.addView(linkText);
        usbText = text("", 11, MUTED, false);
        usbText.setTypeface(Typeface.MONOSPACE);
        usbText.setTextIsSelectable(true);
        usbText.setPadding(0, dp(8), 0, 0);
        usbText.setVisibility(View.GONE);
        adapterCard.addView(usbText);
        overviewBox.addView(adapterCard);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        overviewBox.addView(resultsBox);
        selectSubTab(0);

        shareButton = button("Share results", false);
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> share());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(12);
        col.addView(shareButton, slp);

        // Speed test at the bottom, same as the Wi-Fi tab
        lanSpeed = new SpeedTestCard(this, "Ethernet",
                () -> {
                    if (testing) stopTest("Port test stopped for the speed test.", false);
                },
                card -> acquireEthernet(card::isRunning, card::status),
                () -> shareButton.setVisibility(View.VISIBLE));
        col.addView(lanSpeed.build());

        TextView foot = text("Switches send CDP about every 60 s and LLDP about every 30 s, so a port test can take up to a "
                + "minute. Network tests run over the adapter itself (never Wi-Fi or cellular) using its own MAC "
                + "address, and the DHCP lease is released when the test ends. The speed test hands the adapter back "
                + "to Android's Ethernet support and runs over that.", 12, MUTED, false);
        foot.setPadding(dp(2), dp(18), 0, 0);
        col.addView(foot);
        col.addView(aboutLink());
        updateLanTopology();
        return scroll;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(outlined(CARD, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private static final java.util.Set<String> ACRONYMS = new java.util.HashSet<>(java.util.Arrays.asList(
            "DHCP", "VLAN", "AP", "APS", "SSID", "LAN", "WAN", "IP", "MAC", "CDP", "LLDP", "DNS", "PTR"));

    /** Card titles: bold, title case (keeps acronyms like DHCP and VLAN). */
    TextView label(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            String u = w.toUpperCase(Locale.US);
            if (u.equals("WI-FI")) sb.append("Wi-Fi");
            else if (ACRONYMS.contains(u) || w.length() <= 1 || !w.equals(u)) sb.append(ACRONYMS.contains(u) ? (u.equals("APS") ? "APs" : u) : w);
            else sb.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.US));
        }
        TextView t = text(sb.toString(), 17, TEXT, true);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    CheckBox checkBox(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(6), dp(8), 0, dp(8));
        return c;
    }

    GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Filled shape with the thin border used on cards. */
    GradientDrawable outlined(int color, int radius) {
        GradientDrawable g = rounded(color, radius);
        g.setStroke(Math.max(1, dp(1)), BORDER);
        return g;
    }

    int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
