# PortPerson Terms of Use and Disclaimer

*Effective October 21, 2026*

These terms apply to the PortPerson app and its source code ("PortPerson"), made by YZIOHP. By installing or using PortPerson you agree to them. If you don't agree, don't use it.

## 1. Authorized use only
Use PortPerson only on networks, devices and connections that **you own or have permission to test**. You are solely responsible for complying with all laws and regulations that apply to you, including computer-misuse laws such as the U.S. Computer Fraud and Abuse Act, and with your employer's policies and the rules of any network owner. Do not use PortPerson to access, disrupt or gather information about networks you are not authorized to test.

## 2. What PortPerson does on a network
So you can judge whether it's allowed where you use it:

- **Wired (LAN) tests** run through a USB Ethernet adapter that the app puts in a receive-all (promiscuous) mode. The app analyzes the frames the switch port delivers (on a normal access port: broadcasts, multicasts and replies to its own tests) to show switch, spanning tree, VLAN, DHCP, gateway and device information, and points out problems such as a second DHCP server or a loop. The received frames are kept in memory on the phone until the next test, so you can save them as a `.pcap` file; they aren't sent anywhere unless you share that file. The app requests and releases a DHCP lease using the adapter's MAC address, and sends ARP, ICMP (ping and traceroute) and DNS traffic. When you turn it on, it announces itself with LLDP-MED.
- **Connection tests** (LAN and Wi-Fi) open a TCP connection to the address and port you enter, only when you tap Test. PortPerson does not scan networks or ports.
- **Speed tests** transfer a large amount of data (roughly 50 MB to over 1 GB) to and from Cloudflare.
- **Wi-Fi tests** read the Wi-Fi information and scan results Android provides, and send ping, DNS, TCP and traceroute traffic over the Wi-Fi network.

This is ordinary client behavior, but it may appear in network monitoring, trigger port security or network access control, or be restricted by your organization's policies.

**Capture files** can contain other devices' addresses, names and traffic. You are responsible for saving and sharing them only where you're authorized to, and only with people entitled to see them.

## 3. Information only, not a certified instrument
Results are provided for troubleshooting and documentation. PortPerson is **not a certified cable, network or safety test instrument**, and results may be incomplete or wrong (for example, when a switch doesn't announce itself or a network filters traffic). Verify important results with your network's own management tools before acting on them.

## 4. No warranty
PORTPERSON IS PROVIDED "AS IS" AND "AS AVAILABLE", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, ACCURACY AND NON-INFRINGEMENT.

## 5. Limitation of liability
TO THE MAXIMUM EXTENT PERMITTED BY LAW, THE AUTHOR AND CONTRIBUTORS ARE NOT LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL OR EXEMPLARY DAMAGES, OR ANY OTHER CLAIM OR LIABILITY, INCLUDING NETWORK OUTAGES, DATA LOSS, SECURITY INCIDENTS, POLICY OR EMPLOYMENT CONSEQUENCES, DATA CHARGES OR LOST PROFITS, ARISING FROM THE USE OF OR INABILITY TO USE PORTPERSON, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES. Some jurisdictions don't allow certain limitations; in those places the limitations apply to the fullest extent allowed.

## 6. Your responsibility
You use PortPerson at your own risk. You are responsible for any mobile data charges, for the equipment you connect, and for any consequences of tests you run.

## 7. Third-party services
Speed tests use Cloudflare (speed.cloudflare.com) and access point vendor lookups use api.macvendors.com. Those services are operated by third parties under their own terms and privacy policies, and may change or stop working at any time.

## 8. Trademarks and no affiliation
PortPerson is an independent project. It is not affiliated with, endorsed by or sponsored by NetAlly, Ubiquiti, Cisco, Aruba/HPE, Juniper, Extreme Networks, Cloudflare, Google or any other company. Product and company names are trademarks of their respective owners and are used only to describe compatibility or for comparison.

## 9. Built with AI
PortPerson's code was written with the help of Claude, Anthropic's AI assistant, and reviewed and tested by its author. Like all software, it may contain errors.

## 10. Open-source license
The source code is licensed under the [MIT License](LICENSE). The MIT License's own warranty disclaimer and limitation of liability also apply. Nothing in these terms limits your rights under that license.

## 11. Privacy
See the [Privacy Policy](PRIVACY.md).

## 12. Changes
These terms may be updated. Material changes will be shown in the app, and the effective date above will change.

## Contact
Open an issue on this project's GitHub page.
