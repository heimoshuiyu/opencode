"""Offline only. Compare the saved network rows; do not compare the MCP envelope to a row."""
import hashlib
import json
from collections import Counter
from pathlib import Path

base = Path(__file__).parent
raw = {name: (base / name).read_bytes() for name in ('termux.json', 'mcp.json')}
mapping = {
    'bssid': 'bssid', 'ssid': 'ssid', 'frequency_mhz': 'frequencyMHz', 'rssi': 'levelDbm',
    'timestamp': 'timestampUs', 'channel_bandwidth_mhz': 'channelBandwidthMHz',
    'capabilities': 'capabilities', 'center_frequency_mhz': 'centerFreq0MHz',
    'operator_name': 'operatorName', 'venue_name': 'venueName',
}

def index(rows):
    result = {}
    for row in rows:
        key = row['bssid'].lower()  # Normalize only the join key, not compared values.
        if key in result:
            raise ValueError(f'ambiguous BSSID: {key}')
        result[key] = row
    return result

def kind(value):
    return {str: 'string', int: 'number', float: 'number', bool: 'boolean',
            list: 'array', dict: 'object', type(None): 'null'}[type(value)]

up = index(json.loads(raw['termux.json']))
mcp = index(json.loads(raw['mcp.json'])['networks'])
assert len(up) == len(mcp) == 102 and up.keys() == mcp.keys(), 'Incomplete BSSID join'
stats = {k: Counter() for k in mapping}
unmapped = {'termux': Counter(), 'mcp': Counter()}
pairs = []
for key in sorted(up):
    a, b = up[key], mcp[key]
    ta, tb = a['timestamp'], b['timestampUs']
    assert type(ta) is int and type(tb) is int, 'Expected microsecond timestamps'
    changes = {}
    for k, v in mapping.items():
        at = kind(a[k]) if k in a else 'absent'
        bt = kind(b[v]) if v in b else 'absent'
        # Absent/absent is evidence of omission, not two matching populated values.
        outcome = ('bothAbsent' if at == bt == 'absent' else 'presenceMismatch' if 'absent' in (at, bt)
                   else 'typeMismatch' if at != bt else 'equal' if a[k] == b[v] else 'differentValue')
        stats[k][f'{at}/{bt}: {outcome}'] += 1
        if outcome not in ('equal', 'bothAbsent'):
            changes[k] = outcome
    unmapped['termux'].update(a.keys() - mapping.keys())
    unmapped['mcp'].update(b.keys() - set(mapping.values()))
    pairs.append(dict(bssid=key, termuxTimestampUs=ta, mcpTimestampUs=tb, deltaUs=tb-ta, differences=changes))

deltas = [p['deltaUs'] for p in pairs]
print(json.dumps(dict(inputsSha256={k: hashlib.sha256(v).hexdigest() for k, v in raw.items()},
    join='unique BSSID', paired=len(pairs), sameSample=sum(d == 0 for d in deltas),
    mcpSampleNewer=sum(d > 0 for d in deltas), mcpSampleOlder=sum(d < 0 for d in deltas),
    deltaUsRange=[min(deltas), max(deltas)], mapping=mapping, fields=stats, unmappedRecordKeys=unmapped,
    mcpOnlyCenterFreq1MHzPresent=sum('centerFreq1MHz' in b for b in mcp.values()), pairs=pairs), indent=2))
