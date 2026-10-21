#!/usr/bin/env bash
# The packages a dev client needs for pictures on a CPU pod (Xvfb, Mesa's software GL, ffmpeg), the repo's usual name
# and the volume's local/ linked in: run once after every pod start (the container disk is new), after kb-pod prep.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends xvfb xauth libgl1-mesa-dri libglx-mesa0 libgl1 libegl1 mesa-utils \
  libasound2t64 ffmpeg x11-utils xdotool libxrandr2 libxcursor1 libxi6 libxinerama1 libxxf86vm1 libxss1 \
  libxext6 libx11-6 libxtst6 fontconfig >/dev/null
ln -sfn /root/kantobridge /root/KantoBridge
[ -e /root/kantobridge/local ] || ln -s /workspace/local /root/kantobridge/local
echo "prep_pictures done: $(ffmpeg -version | head -1 | cut -c1-40)"
