#!/usr/bin/env bash
# Starts (or restarts) Xvfb :98, Blockbench, x11vnc and noVNC. Logs in /root/bb/*.log.
cd /root/bb
pkill -f "[s]quashfs-root/blockbench" ; pkill -f "[x]11vnc -display :98"; pkill -f "[w]ebsockify.*6080"
if ! [ -S /tmp/.X11-unix/X98 ]; then (setsid nohup Xvfb :98 -screen 0 1600x1000x24 > xvfb.log 2>&1 < /dev/null &); sleep 2; fi
(DISPLAY=:98 setsid nohup ./squashfs-root/blockbench --no-sandbox --disable-gpu-sandbox --remote-debugging-port=9222 \
   --remote-debugging-address=127.0.0.1 > blockbench.log 2>&1 < /dev/null &)
(setsid nohup x11vnc -display :98 -localhost -forever -shared -nopw -rfbport 5900 > x11vnc.log 2>&1 < /dev/null &)
(setsid nohup websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900 > novnc.log 2>&1 < /dev/null &)
sleep 8
curl -s http://127.0.0.1:9222/json/version | head -5
