import importlib.util
from pathlib import Path
import struct
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('cleanup', Path(__file__).resolve().parents[1]/'cleanup-herdr-mosh.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class CleanupTest(unittest.TestCase):
    def test_selection_preserves_live_unknown_fresh_and_non_herdr_processes(self):
        base = dict(pid=1, start=100, age_seconds=7200, ports=[60001],
                    disconnected=True, eligible_shape=True)
        records = [base, dict(base, pid=2, ports=[60002]),
                   dict(base, pid=3, disconnected=False),
                   dict(base, pid=4, eligible_shape=False),
                   dict(base, pid=5, age_seconds=200), dict(base, pid=6, ports=[])]
        self.assertEqual([base], M.select_candidates(records, {60002: 1}, 3600, 0))
        self.assertEqual([], M.select_candidates(records, {60002: 1}, 3600, 6))

    def test_utmp_connected_host_is_never_treated_as_disconnected(self):
        output = 'a pts/1 2026-11-15 08:00 . 1 (mosh [123])\n'
        output += 'a pts/2 2026-11-15 08:00 . 2 (100.1.1.1 via mosh [456])\n'
        with patch.object(M.subprocess, 'check_output', return_value=output):
            self.assertEqual({123}, M.disconnected_pids())

    def test_packet_filter_handles_tailscale_nat_framing_ipv4_and_ipv6(self):
        v4 = bytearray(28);v4[0] = 0x45;v4[9] = 17
        struct.pack_into('!HHHH', v4, 20, 12345, 60001, 8, 0)
        self.assertEqual(60001, M.incoming_udp_port(b'\1\0\0\0'+v4))
        self.assertIsNone(M.incoming_udp_port(b'\0\0\0\0'+v4))
        prefix = b'\1\0\4'+b'xxxx'+b'\20'+b'x'*16
        self.assertEqual(60001, M.incoming_udp_port(prefix+v4))
        v6 = bytearray(48);v6[0] = 0x60;v6[6] = 17
        struct.pack_into('!HHHH', v6, 40, 12345, 60002, 8, 0)
        self.assertEqual(60002, M.incoming_udp_port(b'\1\0\0\0'+v6))
        with self.assertRaises(ValueError):M.incoming_udp_port(b'\1\0\0\0'+v4[:22])

    def test_signal_revalidates_identity_and_children(self):
        record = dict(pid=10, child=11, start=100, child_start=101)
        table = {10:dict(name='mosh-server',start=100,parent=1),
                 11:dict(name='herdr',start=101,parent=10)}
        with patch.object(M.os, 'pidfd_open', side_effect=[20,21]), \
             patch.object(M.os, 'close'), patch.object(M.signal, 'pidfd_send_signal') as send, \
             patch.object(M, 'processes', return_value=table), \
             patch.object(M, 'disconnected_pids', return_value={10}):
            self.assertTrue(M.terminate(record))
            send.assert_called_once_with(20, M.signal.SIGTERM)
        for change in ['reused', 'active', 'descendant']:
            current = {k:dict(v) for k,v in table.items()}
            if change == 'reused':current[10]['start'] = 999
            if change == 'descendant':current[12] = dict(name='codex',parent=11,start=102)
            with patch.object(M.os, 'pidfd_open', side_effect=[20,21]), \
                 patch.object(M.os, 'close'), patch.object(M.signal, 'pidfd_send_signal') as send, \
                 patch.object(M, 'processes', return_value=current), \
                 patch.object(M, 'disconnected_pids', return_value=set() if change=='active' else {10}):
                self.assertFalse(M.terminate(record));send.assert_not_called()

if __name__ == '__main__':
    unittest.main()
