package com.portfinder.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One upstream device as announced by a single CDP or LLDP frame. Pure Java (no Android imports). */
public final class NeighborInfo {

    public static final String CDP = "CDP";
    public static final String LLDP = "LLDP";

    public final String protocol;
    public final String sourceMac;

    public String systemName = "";
    public String portId = "";
    public int portIdSubtype = -1;      // LLDP only
    public String portDescription = ""; // LLDP only
    public String platform = "";
    public String mgmtAddress = "";
    public String nativeVlan = "";
    public String voiceVlan = "";
    public String capabilities = "";
    public String frameVlanTag = "";    // set if the frame itself arrived 802.1Q-tagged
    public long receivedAtMillis;

    /** Every decoded field, in the order it appeared in the frame. */
    public final LinkedHashMap<String, String> details = new LinkedHashMap<>();

    public NeighborInfo(String protocol, String sourceMac) {
        this.protocol = protocol;
        this.sourceMac = sourceMac;
    }

    /** Add a detail row; repeated labels are joined with ", ". Empty values are ignored. */
    public void add(String label, String value) {
        if (value == null) return;
        value = value.trim();
        if (value.isEmpty()) return;
        String prev = details.get(label);
        details.put(label, prev == null ? value : prev + ", " + value);
    }

    /**
     * The best human-facing port name. Cisco/Arista put "Gi1/0/12" in the Port ID; Juniper and some
     * others put an ifIndex or MAC there and the real name in the port description.
     */
    public String headlinePort() {
        if (CDP.equals(protocol)) return portId;
        boolean idIsName = portIdSubtype == 1 || portIdSubtype == 5; // interface alias / interface name
        if (idIsName && !portId.isEmpty()) return portId;
        if (!portDescription.isEmpty()) {
            boolean idUseless = portIdSubtype == 3 || portIdSubtype == 4 || portId.matches("\\d+");
            if (idUseless || portId.isEmpty()) return portDescription;
        }
        return portId.isEmpty() ? portDescription : portId;
    }

    public String headlineName() {
        if (!systemName.isEmpty()) return systemName;
        String chassis = details.get("Chassis ID");
        return chassis != null ? chassis : sourceMac;
    }

    /** Identity used to de-duplicate repeated announcements. */
    public String key() {
        return protocol + "|" + headlineName() + "|" + portId;
    }

    public boolean looksLikeInfrastructure() {
        String c = capabilities.toLowerCase(Locale.US);
        return c.contains("bridge") || c.contains("switch") || c.contains("router");
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append(" neighbor");
        if (receivedAtMillis > 0) {
            sb.append(" (received ")
              .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(receivedAtMillis)))
              .append(')');
        }
        sb.append('\n');
        sb.append("  Switch: ").append(headlineName()).append('\n');
        sb.append("  Port:   ").append(headlinePort()).append('\n');
        for (Map.Entry<String, String> e : details.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("  Source MAC: ").append(sourceMac).append('\n');
        return sb.toString();
    }
}
