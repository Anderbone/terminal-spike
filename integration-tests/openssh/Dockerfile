FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b

ARG OPENSSH_VERSION=10.3_p1-r1
ARG TMUX_VERSION=3.7c-r0
ARG MOSH_VERSION=1.4.0-r18

RUN apk add --no-cache \
        "openssh-client-default=${OPENSSH_VERSION}" \
        "openssh-server=${OPENSSH_VERSION}" \
        "mosh=${MOSH_VERSION}" \
        "tmux=${TMUX_VERSION}"

COPY sshd_config /etc/ssh/sshd_config
COPY container-entrypoint.sh /usr/local/bin/terminal-spike-sshd

RUN chmod 0755 /usr/local/bin/terminal-spike-sshd

EXPOSE 22
EXPOSE 62000-62010/udp

ENTRYPOINT ["/usr/local/bin/terminal-spike-sshd"]
