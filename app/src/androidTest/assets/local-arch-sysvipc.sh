#!/bin/bash
set -euo pipefail
cat > /root/arch-sysvipc-reuse.c <<'C'
#include <sys/ipc.h>
#include <sys/shm.h>
#include <stdio.h>
#include <stdlib.h>
int main(void) {
    setbuf(stdout, NULL);
    for (int round = 0; round < 3; ++round) {
        int id = shmget((key_t)0x41524348, 4096, IPC_CREAT | 0600);
        if (id < 0) { perror("shmget"); return 1; }
        if (shmctl(id, IPC_RMID, NULL) != 0) { perror("shmctl"); return 2; }
        printf("ARCH_SYSVIPC_REMOVED_%d\n", round);
    }
    puts("ARCH_SYSVIPC_KEY_REUSE_OK");
    return 0;
}
C
gcc -Wall -Wextra -Werror /root/arch-sysvipc-reuse.c -o /root/arch-sysvipc-reuse
/root/arch-sysvipc-reuse
