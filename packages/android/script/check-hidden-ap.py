"""Check a host-side netsim capture of the isolated OcHiddenFixture connection."""
import subprocess
import sys

bssid = "02:15:b2:00:00:39"
ssid = b"OcHiddenFixture".hex()
fields = ("frame.number", "wlan.fc.type_subtype", "wlan.sa", "wlan.da", "wlan.ssid", "wlan.tag.number", "wlan.tag.length")
data = subprocess.check_output([
    "tshark", "-n", "-r", sys.argv[1], "-Y", f"wlan.bssid == {bssid} || wlan.fc.type_subtype == 4",
    "-T", "fields", *(arg for field in fields for arg in ("-e", field)),
], text=True)

hidden = named = 0
probes = {}
first_beacon = pair = None
for row in data.splitlines():
    number, subtype, src, dst, value, tags, lengths = row.split("\t")
    number, subtype = int(number), int(subtype, 16)
    if subtype == 8 and src == bssid:
        if tags.split(",").count("0") != 1 or lengths.split(",")[tags.split(",").index("0")] != "0":
            sys.exit(f"Frame {number}: target beacon did not have exactly one empty SSID")
        hidden += 1
        if first_beacon is None:
            first_beacon = number
    if subtype == 4 and value == ssid:
        probes[src] = number
    if subtype == 5 and src == bssid:
        if value != ssid:
            sys.exit(f"Frame {number}: target probe response SSID={value!r}, expected OcHiddenFixture")
        if dst in probes and first_beacon is not None and first_beacon < probes[dst] < number:
            named += 1
            if pair is None:
                pair = (probes[dst], number)
if not hidden or not named:
    sys.exit(f"Insufficient evidence: {hidden} anonymous beacons, {named} named replies to probing stations")
print(f"BSSID={bssid} emptySSIDBeacons={hidden} namedProbeResponsesToStation={named} firstBeacon={first_beacon} probe/reply={pair}")
