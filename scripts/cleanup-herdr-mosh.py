#!/usr/bin/env python3
"""Reclaim old, silent Mosh/Herdr frontends; never stop the persistent Herdr server.

Dry run by default. Packet payloads and peer addresses are never retained. Requires
the existing Tailscale debug capture permission; incomplete capture fails closed.
"""
import argparse
import collections
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import selectors
import signal
import struct
import subprocess
import time


def processes():
    result = {}
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            if directory.stat().st_uid != os.getuid():
                continue
            fields = (directory / 'stat').read_text().rsplit(') ', 1)[1].split()
            result[int(directory.name)] = dict(
                name=(directory / 'comm').read_text().strip(),
                parent=int(fields[1]), start=int(fields[19]),
                executable=os.readlink(directory / 'exe'),
            )
        except (OSError, ValueError):
            continue
    return result


def inventory():
    table = processes()
    uptime = float(Path('/proc/uptime').read_text().split()[0])
    clock = os.sysconf('SC_CLK_TCK')
    disconnected = disconnected_pids()
    ports = collections.defaultdict(set)
    supported = collections.defaultdict(set)
    tail_addresses = set(subprocess.check_output(['tailscale', 'ip'], text=True, timeout=10).split())
    output = subprocess.check_output(['ss', '-H', '-uanp'], text=True, timeout=10)
    # Keep numeric local ports only. Addresses and unrelated sockets are discarded.
    for line in output.splitlines():
        if '"mosh-server"' not in line:
            continue
        fields = line.split()
        for pid in re.findall(r'"mosh-server",pid=(\d+),', line):
            address, port = fields[3].rsplit(':', 1)
            port = int(port)
            ports[int(pid)].add(port)
            if address.strip('[]') in tail_addresses:
                supported[int(pid)].add(port)
    records = []
    for pid, process in table.items():
        if process['name'] != 'mosh-server':
            continue
        children = [(child, value) for child, value in table.items() if value['parent'] == pid]
        herdr_only = len(children) == 1 and children[0][1]['name'] == 'herdr'
        child = children[0][0] if herdr_only else None
        # A frontend itself must have no children: never kill a shell or agent tree.
        leaf = herdr_only and not any(value['parent'] == child for value in table.values())
        records.append(dict(pid=pid, start=process['start'],
                            age_seconds=uptime-process['start']/clock,
                            ports=sorted(ports[pid]), child=child,
                            child_start=table[child]['start'] if child else None,
                            disconnected=pid in disconnected,
                            eligible_shape=bool(leaf and ports[pid] and ports[pid] == supported[pid])))
    return records


def disconnected_pids():
    # Mosh changes "host via mosh [pid]" to exactly "mosh [pid]" after
    # 30 seconds without hearing its peer. Never classify a missing record as idle.
    output = subprocess.check_output(['who', '-u'], text=True, timeout=10)
    return {int(match[1]) for line in output.splitlines()
            if (match := re.search(r'\(mosh \[(\d+)\]\)$', line))}


def incoming_udp_port(frame):
    """Decode Tailscale USER0 framing and IP/UDP headers, never payloads.

    Format source: tailscale/tailscale feature/capture/capture.go.
    Unknown paths, fragments and extension headers are ignored conservatively.
    """
    if len(frame) < 4:
        raise ValueError('Truncated capture frame')
    path = struct.unpack_from('<H', frame)[0]
    offset = 2
    for _ in range(2):
        if offset >= len(frame) or frame[offset] not in (0, 4, 16):
            raise ValueError('Invalid Tailscale capture metadata')
        offset += 1 + frame[offset]
    if path != 1:  # FromPeer; outgoing PTY redraws do not prove a live phone.
        return None
    packet = frame[offset:]
    if not packet:
        raise ValueError('Missing IP header')
    if packet[0] >> 4 == 4:
        if len(packet) < 20:
            raise ValueError('Short IPv4 header')
        header = (packet[0] & 15) * 4
        if header < 20 or packet[9] != 17 or int.from_bytes(packet[6:8], 'big') & 0x3fff:
            return None
    elif packet[0] >> 4 == 6:
        if len(packet) < 40:
            raise ValueError('Short IPv6 header')
        if packet[6] != 17:
            return None
        header = 40
    else:
        return None
    if len(packet) < header + 8:
        raise ValueError('Short UDP header')
    return struct.unpack_from('!H', packet, header + 2)[0]


def observe(seconds):
    child = subprocess.Popen(['tailscale', 'debug', 'capture', '--o', '-'],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ)
    pending = bytearray()
    header_seen = False
    received = collections.Counter()
    deadline = time.monotonic() + seconds
    try:
        while time.monotonic() < deadline:
            for key, _ in selector.select(min(1, max(0, deadline-time.monotonic()))):
                data = os.read(key.fd, 65536)
                if not data:
                    raise RuntimeError('Capture exited; no processes will be cleaned')
                pending.extend(data)
                if not header_seen and len(pending) >= 24:
                    if pending[:4] != b'\xd4\xc3\xb2\xa1' or struct.unpack_from('<I', pending, 20)[0] != 147:
                        raise RuntimeError('Unsupported capture format')
                    del pending[:24]
                    header_seen = True
                while header_seen and len(pending) >= 16:
                    size = struct.unpack_from('<I', pending, 8)[0]
                    if size > 65535:
                        raise RuntimeError('Invalid packet length')
                    if len(pending) < 16 + size:
                        break
                    port = incoming_udp_port(pending[16:16+size])
                    if port is not None:
                        received[port] += 1
                    del pending[:16+size]
            if child.poll() is not None:
                raise RuntimeError('Capture died; no processes will be cleaned')
        if not header_seen or not received:
            raise RuntimeError('No incoming UDP observed; cannot establish capture health')
        return received
    finally:
        selector.close()
        child.terminate()
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        child.stdout.close()


def select_candidates(records, incoming, min_age, keep_newest):
    frontends = [r for r in records if r['eligible_shape']]
    protected = {r['pid'] for r in sorted(frontends, key=lambda r: r['start'], reverse=True)[:keep_newest]}
    return [r for r in frontends if r['pid'] not in protected
            and r['age_seconds'] >= min_age and r['ports'] and r['disconnected']
            and not any(incoming.get(port, 0) for port in r['ports'])]


def terminate(record):
    # Register each pinned identity immediately; later acquisition or cleanup can fail.
    with ExitStack() as handles:
        try:
            parent_fd = os.pidfd_open(record['pid'])
            handles.callback(os.close, parent_fd)
            child_fd = os.pidfd_open(record['child'])
            handles.callback(os.close, child_fd)
            current = processes()
            parent, child = current.get(record['pid']), current.get(record['child'])
            if not parent or not child or parent['name'] != 'mosh-server' or child['name'] != 'herdr':
                return False
            if parent['start'] != record['start'] or child['start'] != record['child_start']:
                return False
            if child['parent'] != record['pid'] or any(p['parent'] == record['child'] for p in current.values()):
                return False
            if [pid for pid, p in current.items() if p['parent'] == record['pid']] != [record['child']]:
                return False
            if record['pid'] not in disconnected_pids():
                return False
            # Mosh's normal SIGTERM path shuts down its PTY frontend. No server/pane signals.
            signal.pidfd_send_signal(parent_fd, signal.SIGTERM)
            return True
        except ProcessLookupError:
            return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--observe-seconds', type=float, default=180)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.observe_seconds < 120:
        parser.error('At least 120 seconds of continuous observation is required')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    before = inventory()
    incoming = observe(args.observe_seconds)
    if not any(incoming.get(p) for r in before for p in r['ports']):
        raise RuntimeError('No live Mosh peer observed; cannot safely identify stale peers')
    candidates = select_candidates(before, incoming, 3600, 0)
    report = dict(before=before, observed_seconds=args.observe_seconds,
                  candidate_pids=[r['pid'] for r in candidates],
                  active_mosh_ports=sorted({p for r in before for p in r['ports'] if incoming.get(p)}),
                  signalled=[], applied=args.apply)
    args.report.write_text(json.dumps(report, indent=2))
    if args.apply:
        # Refresh capture immediately before applying; any reappearing peer is protected.
        final_incoming = observe(5)
        for record in candidates:
            if any(final_incoming.get(p) for p in record['ports']):
                continue
            if terminate(record):
                report['signalled'].append(record['pid'])
                args.report.write_text(json.dumps(report, indent=2))
        time.sleep(5)
    report['after'] = inventory()
    args.report.write_text(json.dumps(report, indent=2))
    print(json.dumps(dict(before=len(before), candidates=len(candidates),
                          signalled=len(report['signalled']), remaining=len(report['after']))))


if __name__ == '__main__':
    main()
