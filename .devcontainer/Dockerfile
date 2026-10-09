# ============================================================
# Devcontainer for the AI factory: the autopilot runs here, not on the host account.
# What it isolates: the host's files (only the repository is mounted), the host's credentials (SSH keys,
# browser sessions, other projects), and outbound network (init-firewall.sh: default deny, allow-list).
# What it does not isolate: the host's Docker is reachable (devcontainer feature docker-outside-of-docker)
# so Testcontainers can start Postgres/Kafka on it — whoever controls it can start a container that mounts any directory Docker Desktop
# shares. Restrict Docker Desktop's file sharing to the projects directory (docs/ai-factory.md, Setup).
# Arch: builds natively on arm64 (Apple silicon) and amd64.
# ============================================================
FROM eclipse-temurin:21-jdk-noble

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000

RUN apt-get update && apt-get install -y --no-install-recommends \
        git jq curl ca-certificates gnupg sudo less vim-tiny \
        iptables ipset iproute2 dnsutils \
    && rm -rf /var/lib/apt/lists/*

# GitHub CLI from GitHub's apt repository (signed).
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh && rm -rf /var/lib/apt/lists/*

# Non-root user; sudo only for the firewall script.
RUN (getent group ${USER_GID} || groupadd --gid ${USER_GID} ${USERNAME}) \
    && (id -u ${USER_UID} >/dev/null 2>&1 && usermod -l ${USERNAME} -d /home/${USERNAME} -m $(id -nu ${USER_UID}) || useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME}) \
    && echo "${USERNAME} ALL=(root) NOPASSWD: /usr/local/bin/init-firewall.sh" > /etc/sudoers.d/${USERNAME} \
    && chmod 0440 /etc/sudoers.d/${USERNAME}

COPY init-firewall.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/init-firewall.sh

USER ${USERNAME}
WORKDIR /home/${USERNAME}

# Claude Code, native installer. Updates are off in the container (DISABLE_AUTOUPDATER): the version the
# autopilot runs is the one this image was built with — rebuild the image to upgrade, then run the
# seed-bugs benchmark before trusting the new version (docs/ai-factory.md, "Pinned versions").
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/${USERNAME}/.local/bin:${PATH}" \
    DISABLE_AUTOUPDATER=1
