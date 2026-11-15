/* Host-only fault injection: fail writes only inside an explicitly selected fixture directory. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/uio.h>
#include <sys/sendfile.h>

static int fails(int fd) {
    const char *root = getenv("TERMINAL_SPIKE_QUOTA_ROOT");
    if (root && root[0] == '/') {
        char descriptor[64], target[4096];
        snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%d", fd);
        ssize_t size = readlink(descriptor, target, sizeof(target) - 1);
        if (size >= 0) {
            target[size] = 0;
            size_t prefix = strlen(root);
            if (!strncmp(target, root, prefix) && target[prefix] == '/') {
                errno = EDQUOT;
                return 1;
            }
        }
    }
    return 0;
}

ssize_t write(int fd, const void *data, size_t length) {
    if (fails(fd)) return -1;
    ssize_t (*real_write)(int, const void *, size_t) = dlsym(RTLD_NEXT, "write");
    return real_write(fd, data, length);
}

ssize_t writev(int fd, const struct iovec *data, int count) {
    if (fails(fd)) return -1;
    ssize_t (*real)(int, const struct iovec *, int) = dlsym(RTLD_NEXT, "writev");
    return real(fd, data, count);
}

ssize_t splice(int in, off64_t *in_off, int out, off64_t *out_off, size_t size, unsigned flags) {
    if (fails(out)) return -1;
    ssize_t (*real)(int, off64_t *, int, off64_t *, size_t, unsigned) = dlsym(RTLD_NEXT, "splice");
    return real(in, in_off, out, out_off, size, flags);
}

ssize_t copy_file_range(int in, off64_t *in_off, int out, off64_t *out_off, size_t size, unsigned flags) {
    if (fails(out)) return -1;
    ssize_t (*real)(int, off64_t *, int, off64_t *, size_t, unsigned) = dlsym(RTLD_NEXT, "copy_file_range");
    return real(in, in_off, out, out_off, size, flags);
}

ssize_t sendfile(int out, int in, off_t *offset, size_t size) {
    if (fails(out)) return -1;
    ssize_t (*real)(int, int, off_t *, size_t) = dlsym(RTLD_NEXT, "sendfile");
    return real(out, in, offset, size);
}
