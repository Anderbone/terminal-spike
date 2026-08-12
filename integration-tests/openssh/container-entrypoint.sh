#!/bin/sh
set -eu

test_password=${TERMINAL_SPIKE_TEST_PASSWORD:-terminal-spike-test-only}

if ! id terminal >/dev/null 2>&1; then
    adduser -D -h /home/terminal -s /bin/ash terminal
fi

printf '%s:%s\n' terminal "$test_password" | chpasswd
install -d -m 0700 -o terminal -g terminal /home/terminal/.ssh
install -m 0600 -o terminal -g terminal /fixtures/id_ed25519.pub /home/terminal/.ssh/authorized_keys
install -d -m 0755 /run

/usr/sbin/sshd -t -f /etc/ssh/sshd_config
exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config
