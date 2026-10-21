#!/bin/bash
# The picture spots in a test world (docs/specs/LEVELS_PIPELINE.md, step 8), on the pod: per game the spots of $SPOTS
# (cells_<game>.txt and spots_<game>.json, context_shots.py's; default /workspace/videos/heights-w6/spots, the seven
# picture spots) in world $WORLD (default LandLevels), into $OUT (default /workspace/videos/landscape/shots_levels).
# Needs Xvfb, Mesa and ffmpeg (prep_pictures.sh, after every pod start). Ends "SHOTS DONE".
export DISPLAY=:99 KB_DATASET_MEMO=30 KB_EXPORT_INDEX_TIMEOUT=900 KB_JAVA_HOME=/opt/java/openjdk JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
pgrep -f "Xvfb :99" >/dev/null || (Xvfb :99 -screen 0 1280x720x24 >/dev/null 2>&1 &)
pgrep -f "Xvfb :97" >/dev/null || (Xvfb :97 -screen 0 1280x720x24 >/dev/null 2>&1 &)
sleep 2
cd /root/kantobridge
S=${SPOTS:-/workspace/videos/heights-w6/spots}
for g in ${GAMES:-blue crystal emerald}; do
  tools/.venv/bin/python tools/e2e/context_shots.py --game $g --world "${WORLD:-LandLevels}" --cells "$S/cells_$g.txt" \
    --locations "$S/spots_$g.json" --out "${OUT:-/workspace/videos/landscape/shots_levels}" --game-display :97 --pitch -20 --settle 8 2>&1 | tail -6
done
echo "SHOTS DONE $(date +%T)"
