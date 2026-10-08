#!/bin/sh
# A real, read-only shell example used in the Play screenshots.
set -eu
printf '\033[2J\033[H'
cd "$(dirname "$0")/.."
printf '\033[1;36m\n  ATLAS / WORKSPACE\033[0m\n'
printf '  A little space for your next big idea.\n\n'
printf '\033[1;32m  01  PROJECT FILES\033[0m\n\n'
ls --color=always -F
printf '\n\033[1;32m  02  PROJECT NOTES\033[0m\n\n'
cat README.md
printf '\n\033[1;32m  03  DISK SPACE\033[0m\n\n'
df -h . | awk 'NR == 2 { printf "Used: %s   Available: %s\n", $3, $4 }'
printf '\n'
