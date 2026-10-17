#!/usr/bin/env bash
# Blockbench desktop under Xvfb with remote debugging (CDP on 127.0.0.1:9222), x11vnc + noVNC (127.0.0.1:6080) for a
# person to watch or edit; everything on localhost only (reached through ssh tunnels).
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends xvfb x11vnc novnc websockify xauth fonts-dejavu-core libgtk-3-0t64 libnss3 \
  libxss1 libasound2t64 libgbm1 libxshmfence1 libdrm2 libxkbfile1 libsecret-1-0 libnotify4 libatspi2.0-0t64 libcups2t64 \
  libgl1 libegl1 libgl1-mesa-dri curl python3-venv >/dev/null
mkdir -p /root/bb && cd /root/bb
[ -f Blockbench.AppImage ] || curl -sSL -o Blockbench.AppImage https://github.com/JannisX11/blockbench/releases/download/v5.2.2/Blockbench_5.2.2.AppImage
chmod +x Blockbench.AppImage
[ -d squashfs-root ] || ./Blockbench.AppImage --appimage-extract >/dev/null
echo SETUP_OK
