package com.portfinder.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Terms of use, disclaimer and privacy summary. Shown once on first launch (must be accepted) and
 * any time from "About & legal". Keep in sync with TERMS.md and PRIVACY.md in the repository.
 */
final class LegalNotice {

    /** Bump when the terms change materially so users see and accept them again. */
    private static final int TERMS_VERSION = 2; // 2: traffic analysis, .pcap capture, connection tests
    private static final String PREFS = "legal";
    private static final String KEY = "accepted_terms_version";

    static final String FIRST_RUN =
            "Before you start:\n\n"
            + "• Only use PortPerson on networks you own or are authorized to test. Plugging into or testing a network "
            + "without permission may break workplace policies or the law. You are responsible for how you use it.\n\n"
            + "• On a wired connection the app listens to the traffic the port receives (switch announcements, spanning "
            + "tree, DHCP, ARP and other broadcasts), requests and releases a DHCP address, pings, traces routes and can "
            + "run a speed test or a connection test you start. This is normal device behavior, but it can show up in "
            + "network monitoring or trigger port security.\n\n"
            + "• You can save what a port test received as a capture file (.pcap). It can contain other devices' "
            + "addresses and names, so only share it with people allowed to see it.\n\n"
            + "• Results are for information and troubleshooting only. PortPerson is not a certified test instrument and "
            + "comes with no warranty. Verify results before making changes to a network.\n\n"
            + "• PortPerson was built with the help of Claude, an AI assistant, and reviewed by its author.\n\n"
            + "• PortPerson is an independent project, not affiliated with or endorsed by any network equipment maker, "
            + "Cloudflare or Google.\n\n"
            + "Full terms and the privacy summary are under \"About & legal\".";

    static final String FULL =
            "TERMS OF USE\n\n"
            + "1. Authorized use only. Use PortPerson only on networks, devices and connections you own or have "
            + "permission to test. You are solely responsible for complying with all laws, regulations, employer "
            + "policies and network owners' rules that apply to you, including computer-misuse laws.\n\n"
            + "2. What the app does on a network. Wired tests receive the traffic the switch port delivers through the USB "
            + "adapter (on a normal port: broadcasts, multicasts and replies to the app's own tests) and analyze it to "
            + "show switch, spanning tree, VLAN, DHCP, gateway and device information. The received frames are kept in "
            + "memory on the phone until the next test so you can save them as a .pcap file; they are not sent anywhere "
            + "unless you share that file. Wired tests also request and release a DHCP lease, send ARP, ping, DNS and "
            + "traceroute traffic, and can announce themselves with LLDP-MED when you turn that on. Connection tests open "
            + "a TCP connection to the address and port you enter, only when you tap Test; the app does not scan "
            + "networks. Speed tests transfer a large amount of data. Wi-Fi tests read Android's Wi-Fi information and "
            + "scan results.\n\n"
            + "Capture files can contain other devices' addresses, names and traffic. You are responsible for saving and "
            + "sharing them only where you're authorized to.\n\n"
            + "3. Information only. Results are provided for troubleshooting and documentation. PortPerson is not a "
            + "certified cable or network test instrument and results may be incomplete or wrong. Verify important "
            + "results with your network's own tools before acting on them.\n\n"
            + "4. No warranty. PortPerson is provided \"as is\" and \"as available\", without warranty of any kind, "
            + "express or implied, including merchantability, fitness for a particular purpose and non-infringement.\n\n"
            + "5. Limitation of liability. To the maximum extent permitted by law, the author and contributors are not "
            + "liable for any claim, damages or other liability, including network outages, data loss, policy "
            + "violations, data charges or lost profits, arising from the use of or inability to use PortPerson.\n\n"
            + "6. Data usage. Speed tests can use from about 50 MB to over 1 GB. Mobile data charges are your "
            + "responsibility.\n\n"
            + "7. Third-party services. Speed tests use Cloudflare (speed.cloudflare.com) and access point vendor lookups "
            + "use api.macvendors.com. Their own terms and privacy policies apply. Availability is not guaranteed.\n\n"
            + "8. Trademarks. Product and company names mentioned in the app or its documentation are trademarks of their "
            + "owners and are used only to describe compatibility. PortPerson is not affiliated with or endorsed by them.\n\n"
            + "9. Built with AI. PortPerson's code was written with the help of Claude, Anthropic's AI assistant, and "
            + "reviewed and tested by its author.\n\n"
            + "10. Open source. PortPerson is released under the MIT License, which includes its own no-warranty and "
            + "no-liability terms.\n\n"
            + "PRIVACY SUMMARY\n\n"
            + "• PortPerson has no accounts, ads, analytics or tracking, and the author receives no data from the app.\n"
            + "• Saved tests stay in the app's private storage on this phone until you delete them or uninstall the app. "
            + "They leave the phone only when you share or export them.\n"
            + "• Location permission is required by Android to show Wi-Fi network names and scan results. PortPerson does "
            + "not read, store or send your location.\n"
            + "• Captured frames stay in memory on this phone until the next test, and are written to a file only when you "
            + "tap Save capture. Recent connection checks (address and port) are remembered on this phone.\n"
            + "• Network tests contact the DNS servers of the network you're on and the test target you choose (default "
            + "google.com). Speed tests contact Cloudflare, which sees your public IP address. Vendor lookups send only the "
            + "first half of an access point's MAC address (the vendor prefix) to api.macvendors.com.\n\n"
            + "The full terms (TERMS.md) and privacy policy (PRIVACY.md) are on the project's GitHub page. Questions or "
            + "problems: open an issue on GitHub.";

    private LegalNotice() {}

    static boolean accepted(Context c) {
        return prefs(c).getInt(KEY, 0) >= TERMS_VERSION;
    }

    /** Shows the first-run notice if it hasn't been accepted. The app can't be used until it is. */
    static void showIfNeeded(Activity a) {
        if (accepted(a)) return;
        new AlertDialog.Builder(a)
                .setTitle("Use PortPerson responsibly")
                .setView(scrollingText(a, FIRST_RUN))
                .setCancelable(false)
                .setPositiveButton("I understand and agree", (d, w) -> prefs(a).edit().putInt(KEY, TERMS_VERSION).apply())
                .setNeutralButton("Full terms", (d, w) -> showFull(a, true))
                .setNegativeButton("Exit", (d, w) -> a.finish())
                .show();
    }

    /** Full terms and privacy summary. When fromFirstRun, returns to the first-run notice afterwards. */
    static void showFull(Activity a, boolean fromFirstRun) {
        new AlertDialog.Builder(a)
                .setTitle("About & legal")
                .setView(scrollingText(a, "PortPerson by YZIOHP\nVersion " + version(a) + "\n\n" + FULL))
                .setCancelable(!fromFirstRun)
                .setPositiveButton(fromFirstRun ? "Back" : "Close", (d, w) -> {
                    if (fromFirstRun) showIfNeeded(a);
                })
                .show();
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static ScrollView scrollingText(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(MainActivity.TEXT);
        t.setTextIsSelectable(true);
        int p = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22, a.getResources().getDisplayMetrics()));
        t.setPadding(p, p / 2, p, p / 2);
        ScrollView s = new ScrollView(a);
        s.addView(t);
        return s;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
