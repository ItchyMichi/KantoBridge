#!/bin/bash
# Every outdoor map's levels and place in the world, per game (docs/specs/LEVELS_PIPELINE.md, step 1 and the seams check),
# on the pod: OutdoorLevelsDumpTest with "all" into $OUT (default /workspace/videos/landscape/levels_all). OUTDOOR names the
# maps on raised ground the build's way (default: the picture maps; "all" for every one: pins and models apply to all),
# CHAIN=1 chains them (the placements' heights), TABLE the models table, GAMES the games (default emerald crystal blue).
# A minute or so a game. Ends "DUMP DONE".
cd /root/kantobridge/fabric-bridge && export JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
E=/workspace/local/minecraft/kantobridge/export
O=${OUT:-/workspace/videos/landscape/levels_all}
M=${OUTDOOR:-ROUTE_23,ROUTE_10,G2_BLACKTHORN_CITY,G2_ROUTE_26,EM_ROUTE119,EM_ROUTE127,EM_BATTLE_FRONTIER_OUTSIDE_EAST,EM_MOSSDEEP_CITY}
mkdir -p "$O"
for g in ${GAMES:-emerald crystal blue}; do
  echo "== $g start $(date +%T)"
  timeout 2400 ./gradlew test --tests "dev.kantobridge.builder.plan.OutdoorLevelsDumpTest" "-Pkb.outdoorDump=$g;$E/$g;$O;all" \
    "-Pkb.outdoorLevels=$M" ${CHAIN:+-Pkb.chainLevels=true} -Pkb.landscapeModels=${TABLE:-/workspace/videos/landscape/models_table_v2.json} \
    -Pkb.testHeap=10g > /root/dump_$g.log 2>&1
  echo "== $g exit $? $(date +%T) $(grep -E "BUILD" /root/dump_$g.log | head -1)"
  grep -E "error:|IllegalState|FAILED|Exception" /root/dump_$g.log | head -5
done
echo "DUMP DONE $(date +%T)"
