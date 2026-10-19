# batch_entities.sh <game>: entity_depth + entity_package for every map of flags_<game>.json with entities
g=$1; P=/root/dv/bin/python; E=/root/ent/$g
mkdir -p $E/out $E/hand
cd "$(dirname "$0")"
for m in $(python3 -c "import json;print(' '.join(json.load(open('$E/flags_$g.json'))['_entities']))"); do
  [ -f $E/maps/${m}_art.png ] || continue
  $P entity_depth.py $g $m $E/flags_$g.json $E/maps/${m}_art.png $E/out/$m --weights /root/esrgan --info $E/maps/$m.json 2>&1 | grep "^${g}_" | sed "s/^/  /"
  $P entity_package.py $g $m $E/flags_$g.json $E/maps/${m}_art.png $E/maps/$m.json $E/hand 2>&1 | tail -n +1 | sed "s/^/  hand /"
  echo "MAP_DONE $m"
done
echo BATCH_DONE
