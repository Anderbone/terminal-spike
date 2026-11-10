from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('task_notify', Path(__file__).resolve().parents[2] / 'tools/terminal-task-notify.py')
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


class TaskNotifyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.env = patch.dict(os.environ, CODEX_HOME=str(self.home), XDG_STATE_HOME=str(self.home / 'state'))
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_excerpt_preserves_failure_and_question_without_controls(self):
        self.assertEqual('需要你选择 A 或 B？ 构建失败。', notify.excerpt('**需要你选择 A 或 B？**\n\u202e构建失败。'))
        self.assertEqual('打开报告', notify.excerpt('[打开报告](/private/path)'))
        self.assertEqual(400, len(notify.excerpt('中' * 401)))

    def test_notify_is_private_bounded_and_thread_scoped(self):
        notify.save_event({'type': 'agent-turn-complete', 'thread-id': 'thread1', 'turn-id': 'turn1',
                           'last-assistant-message': '已完成测试。'})
        path = self.home / 'state/terminal-spike/replies/thread1.json'
        self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertEqual({'id': 'turn1', 'message': '已完成测试。'}, notify.reply_for('thread1'))
        self.assertIsNone(notify.reply_for('thread2'))
        self.assertIsNone(notify.reply_for('../thread1'))
        notify.save_event({'type': 'agent-turn-complete', 'thread-id': '../bad', 'turn-id': 'turn1'})
        self.assertEqual(1, len(list(path.parent.iterdir())))

    def make_rollout(self, events):
        path = self.home / 'sessions/2026/11/10/exact-thread.jsonl'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('\n'.join(json.dumps(e) for e in events) + '\n')
        return path

    def event(self, kind, **kwargs):
        return {'type': 'event_msg', 'payload': {'type': kind, **kwargs}}

    def test_compatibility_reads_only_completed_current_turn_from_exact_indexed_thread(self):
        path = self.make_rollout([self.event('task_complete', turn_id='turn1', last_agent_message='第一项完成。')])
        with closing(sqlite3.connect(self.home / 'state_5.sqlite')) as db:
            db.execute('create table threads(id text, rollout_path text)')
            db.execute('insert into threads values (?, ?)', ('thread1', str(path)))
            db.commit()
        self.assertEqual('第一项完成。', notify.reply_for('thread1')['message'])
        with path.open('a') as stream:
            stream.write(json.dumps(self.event('task_started', turn_id='turn2')) + '\n')
        self.assertIsNone(notify.reply_for('thread1'))
        with path.open('a') as stream:
            stream.write(json.dumps(self.event('task_complete', turn_id='turn2', last_agent_message='需要你提供密码。')) + '\n')
        self.assertEqual('需要你提供密码。', notify.reply_for('thread1')['message'])
        self.assertIsNone(notify.reply_for('other-thread'))

    def test_bounded_tail_handles_partial_lines_and_rejects_outside_transcripts(self):
        path = self.make_rollout([{'padding': 'x' * notify.LIMIT}, self.event('task_complete', turn_id='turn1', last_agent_message='完成。')])
        self.assertEqual('完成。', notify.rollout_reply(path, self.home)['message'])
        outside = self.home / 'outside.jsonl'
        outside.write_text(path.read_text())
        self.assertIsNone(notify.rollout_reply(outside, self.home))

    def test_enrich_joins_thread_identity_without_inherited_pane_environment(self):
        notify.save_event({'type': 'agent-turn-complete', 'thread-id': 'thread1', 'turn-id': 'turn1',
                           'last-assistant-message': '第一个任务的回复'})
        agents = [dict(pane_id='w1:p1', agent_session={'agent': 'codex', 'kind': 'id', 'value': 'thread1'}),
                  dict(pane_id='w1:p2', agent_session={'agent': 'codex', 'kind': 'id', 'value': 'thread2'}),
                  dict(pane_id='w1:p3', agent_session=None)]
        with patch.dict(os.environ, HERDR_PANE_ID='wrong:p9'):
            result = notify.enrich({'result': {'agents': agents}})['result']['agents']
        self.assertEqual('第一个任务的回复', result[0]['task_reply']['message'])
        self.assertNotIn('task_reply', result[1])
        self.assertNotIn('task_reply', result[2])
