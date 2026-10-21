#!/bin/bash
# A test world with the landscape models and outdoor levels (docs/specs/LEVELS_PIPELINE.md, step 8), on the pod: every
# region in $REGIONS (default "kanto hoenn johto"), the models table $TABLE (models_table.py, with its marked slopes),
# the outdoor maps $OUTDOOR (map ids, comma-separated) on raised ground, in world $WORLD (default LandLevels; Kanto,
# slot 0, starts it fresh). Logs and build reports in $LOGS (default /workspace/videos/landscape/logs). Ends "ALL DONE".
#   TABLE=... OUTDOOR=EM_ROUTE119,... REGIONS="hoenn" setsid nohup tools/landscape_sections/pod/levels_build.sh >> build.log 2>&1 &
export KB_JAVA_HOME=/opt/java/openjdk JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
cd /root/kantobridge
WORLD=${WORLD:-LandLevels}
L=${LOGS:-/workspace/videos/landscape/logs}
mkdir -p "$L"
for r in ${REGIONS:-kanto hoenn johto}; do
  case $r in
    kanto) g=blue; s=0;;
    hoenn) g=emerald; s=1;;
    johto) g=crystal; s=2;;
  esac
  fresh=""
  [ "$r" = "kanto" ] && fresh="--fresh"
  echo "== $r start $(date +%T)"
  tools/.venv/bin/python tools/e2e/build_world.py --skip-export --game $g --maps all --world "$WORLD" $fresh --region $r --slot $s \
    --heap 8g --landscape-models "$TABLE" --outdoor-levels "$OUTDOOR" > "$L/levels_$r.log" 2>&1
  echo "== $r exit $? $(date +%T)"
  grep -E "build OK|FAILED|no build report" "$L/levels_$r.log" | tail -2
  cp local/reports/build_report.json "$L/levels_report_$r.json" 2>/dev/null
done
echo "ALL DONE $(date +%T)"
