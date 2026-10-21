# Changelog

## v1.1.0: Findings, Switch & Traffic tabs, Connection Test

The port test works exactly as before. It now also analyzes everything the switch port sends while it listens, and the results are organized into tabs.

### Added
- **Findings card** after every port test: a quick health check of the port, collapsed to one line by default. Red problems open it automatically; tap any item to jump to its details. It checks for:
  - more than one DHCP server (a likely **rogue DHCP server** when they hand out different gateways or subnets; DHCP failover when they don't)
  - **802.1X** requests from the switch (and whether that's why DHCP failed)
  - **switching loops** (the same broadcast repeating) and **broadcast storms**
  - **duplicate IP addresses**, including the gateway (HSRP/VRRP virtual MACs aren't counted)
  - **DTP** trunk negotiation on the port, **tagged VLAN** traffic, and a possible **native VLAN mismatch** (CDP vs. PVST+)
  - **spanning-tree topology changes** (a single change right after link-up, normal on ports without PortFast, isn't flagged)
  - **routing protocol hellos** (OSPF, EIGRP, RIP) reaching a user port, and more than one **IPv6 router**
- **Results tabs** under the Findings card:
  - **Overview**: the same switch, port, VLAN, DHCP and internet results as before.
  - **Switch**: spanning tree (mode, root bridge and who it is per CDP/LLDP, root path cost, topology changes, PVST+ VLANs), VLANs and trunking (tagged frames, DTP, VTP, 802.1X), HSRP/VRRP gateways, routing hellos, IPv6 routers and the IGMP querier.
  - **Traffic**: frames per second by protocol, broadcast and multicast rates, devices announcing themselves (mDNS, NetBIOS and DHCP host names), and the top broadcast senders.
  - **Connection Test**: check whether the drop can reach a printer, server or website. Enter an IP address or hostname and a port, or tap a quick pick (Print 9100, IPP 631, HTTPS 443, HTTP 80, SMB 445, RDP 3389, SSH 22, DNS 53). It tries 3 times and shows Open, Refused or Timed out with response times. It runs only when you tap Test, never scans, and keeps a list of recent checks you can tap to re-run.
- **Save capture (.pcap)** on the Traffic tab: save what the port test received (plus the app's own test traffic) and open it in Wireshark. Captures can contain other devices' information, so share them only where you're authorized to.
- **Connection Test on the Wi-Fi tab** too, for checking printers and servers over wireless.
- The network map shows **your phone's model** (e.g. "Z Fold 8", "Pixel 9 Pro") instead of "This phone".
- Findings and the Switch details are included in saved tests, shared reports and History.

### Changed
- Updated the in-app terms, [TERMS.md](TERMS.md) and [PRIVACY.md](PRIVACY.md) to describe the traffic analysis, capture files and connection tests. You'll be asked to accept the updated terms once.

## v1.0.0: First release

PortPerson's first public version. Find the switch port behind any network drop from an Android phone, then check DHCP, VLAN, internet and speed, on both wired and Wi-Fi.

### LAN (Ethernet, via a USB adapter)
- Switch and port discovery from **CDP** (v1/v2) and **LLDP**, including LLDP-MED, 802.1 and 802.3 extensions: switch name, port, data and voice VLAN, management IP, model, link speed/duplex and PoE.
- **Waits for link before testing.** Taking over the USB adapter resets it, so a short "getting ready" checklist (Adapter → Link) shows until link is up and settled. If there's no link after 45 seconds, the test stops with a clear message: check the cable, the jack, and that the switch port isn't shut down or err-disabled.
- Optional **LLDP-MED announcement** so many switches answer within seconds.
- **DHCP** with full lease details (IP, gateway and its MAC, DNS, domain, DHCP server, relay/helper, lease time, time to lease). It keeps trying for about 30 seconds, long enough for switch ports without PortFast, and the lease is released when the test ends.
- **VLAN**: access VLAN as reported by the switch, plus voice VLAN and the subnet DHCP handed out.
- **Internet checks**: gateway ping, DNS lookup, ping and **ICMP traceroute** with reverse-DNS hop names.
- Everything above runs over the adapter itself (never Wi-Fi or cellular) using the adapter's own MAC address.
- Works with USB Ethernet adapters in standard **CDC-ECM/NCM** mode (most Realtek RTL8152/8153/8156) and **ASIX AX88179 / AX88179A** in native mode. After a test, the adapter is put back in its original mode so Android can use it again.

### Wi-Fi
- Connection details: SSID, BSSID, signal, channel/band/width, Wi-Fi standard, security, link rate, IP/gateway/DNS/DHCP.
- **Access point identity**: AP name from beacons (Cisco, Aruba, Juniper Mist, Extreme/Aerohive; WPS device name on home routers), vendor, client count and channel utilization when advertised, other SSIDs on the same AP and roaming candidates.
- **Wi-Fi Speed Factors** (Spectrum, Radio Potential, Channel Health) with an overall rating.
- Gateway ping, DNS, TCP connect, ping and traceroute over Wi-Fi.

### Scan
- Channel usage per band and every nearby access point.

### Speed test
- **Cloudflare** speed test (latency, jitter, download, upload, server, ISP, public IP) on both the LAN and Wi-Fi tabs.
- **Route check**: before measuring, it confirms the connection really leaves through Ethernet (LAN tab) or Wi-Fi (Wi-Fi tab) and shows the interface and address used. It never falls back to another network.

### History and sharing
- **History tab**: port tests, Wi-Fi tests and speed tests are saved automatically on the phone (last 200), newest first, with LAN / Wi-Fi / Speed filters.
- **Drop labels** (e.g. "Rm 204 jack A"), added before a test or changed later.
- Full reports for every saved test, with **share**, **rename** and **delete**.
- **Export CSV** for documenting drops in a spreadsheet, and **Share all** as text.
- **Share as image**: any result can be shared as a PNG report card styled like the app, or as text.

### Look and feel
- Dark interface with a network map that reads left to right: this phone → switch/AP → gateway → Internet.
- The LAN and Wi-Fi tabs share one layout: the tab's own test at the top, then the details, then the speed test at the bottom.
- App icon (a person in front of a network wall jack) and a short splash screen when the app opens.

### Legal and privacy
- First-launch notice (must be accepted): authorized use only, what the app does on a network, no warranty, not a certified instrument, no affiliation, built with AI.
- **About & legal** link on the LAN, Wi-Fi and History tabs with the full terms and privacy summary.
- [TERMS.md](TERMS.md), [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).
- No accounts, ads, analytics or tracking. Saved tests stay on the phone unless you share or export them.
