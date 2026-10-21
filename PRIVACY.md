# PortPerson Privacy Policy

*Effective October 21, 2026*

PortPerson is a network testing app made by YZIOHP. This policy explains what information the app handles. Short version: **PortPerson doesn't collect your data. The developer receives nothing from the app.**

## What we collect
Nothing. PortPerson has no accounts, no advertising, no analytics, no crash reporting and no tracking. The developer does not receive any information from the app or your device.

## Information stored on your phone
- **Saved tests (History):** results of tests you run, including any labels you type (e.g. "Rm 204 jack A"), switch and port names, IP addresses and Wi-Fi network names. These are stored only in the app's private storage on your phone. They are deleted when you delete them in the app or uninstall it.
- **Captured frames:** what the switch port delivered during the last port test, kept in memory so you can save it as a `.pcap` file. It's replaced by the next test and cleared when the app closes, and written to a file only when you tap **Save capture**. It can include other devices' addresses and names.
- **Recent connection checks:** the last few addresses and ports you tested, so you can re-run them.
- **Settings:** whether you have accepted the terms.

Saved tests and capture files leave your phone only when **you** choose to share or export them (as text, image, CSV or .pcap), and only to the app or person you choose.

## Permissions and why
| Permission | Why |
|---|---|
| USB (adapter access) | To run wired tests through your USB Ethernet adapter. Android asks you first. |
| Location / Nearby Wi-Fi devices | Android requires these before any app can see Wi-Fi network names, access point details and scan results. **PortPerson never reads, stores or sends your location.** |
| Internet, network state, Wi-Fi state | To run network tests and speed tests and to show connection details. |

## Network connections the app makes
Only when you run a test:
- **Your network:** DHCP, DNS, ping and traceroute traffic on the network you're testing, and the DNS servers that network provides.
- **Your test target:** the internet test target you choose (default `google.com`).
- **Connection tests:** a TCP connection to the address and port you enter, only when you tap Test.
- **Cloudflare (speed.cloudflare.com):** for speed tests. Like any website, Cloudflare sees your public IP address. See Cloudflare's privacy policy.
- **api.macvendors.com:** to look up an access point's manufacturer. Only the vendor prefix (the first half of the access point's MAC address, e.g. `F0:9F:C2`) is sent, never your phone's address.

## Children
PortPerson is a tool for network professionals and is not directed at children under 13.

## Changes
If this policy changes, the new version will be posted here with a new effective date, and material changes will be shown in the app.

## Contact
Open an issue on this project's GitHub page.
