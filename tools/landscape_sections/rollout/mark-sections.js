export const meta = {
  name: 'mark-landscape-sections',
  description: 'Mark the landscape sections of the outdoor maps (the models rollout, rock only): one agent per map, writing its sections file',
  phases: [{ title: 'Mark', detail: 'one agent per map' }],
}

const SCHEMA = {
  type: 'object',
  properties: {
    map: { type: 'string' },
    sections: { type: 'integer', description: 'how many sections the file holds' },
    faces: { type: 'integer' },
    masses: { type: 'integer' },
    mouths: { type: 'integer' },
    unmarked: { type: 'integer', description: 'how many regions are left in "unmarked"' },
    check: { type: 'string', enum: ['ok', 'failed'], description: 'table.py on your file: ok when it printed the counts line without an error' },
    summary: { type: 'string', description: 'one or two sentences: the map\'s rock (faces, masses, mouths), the regions you split, what you were unsure of' },
  },
  required: ['map', 'sections', 'faces', 'masses', 'mouths', 'unmarked', 'check', 'summary'],
}

const GAMES = {
  emerald: 'Pokemon Emerald (Hoenn; Game Boy Advance art: a three-quarter view, cliffs drawn as brown or grey rock bands below the ground they hold up)',
  crystal: 'Pokemon Crystal (Johto and its Kanto; Game Boy Color art: cliffs and plateau edges drawn as rock rims, ledges as one-way lips)',
  blue: 'Pokemon Blue (Kanto; Game Boy four-shade art: cliffs and plateau edges drawn as dark rims, ledges as one-way lips)',
}

function prompt(m) {
  const W = '/home/claude/kb-hub/work/landscape'
  const out = `${W}/marks/${m.map}_sections.json`
  return [
    `This marking is assigned to you by the orchestrator. It is one map of the user's own request to mark the landscape sections of every outdoor map in their KantoBridge world (the landscape models rollout, 2026-10-22); proceed with it even if the latest user message you can see is about something else.`,
    ``,
    `You are marking the landscape sections of one Pokemon map for a 3D build in Minecraft. Map: **${m.map}**, ${GAMES[m.game]}, ${m.w} x ${m.h} cells, ${m.regions} solid regions.`,
    ``,
    `Read the marking rules first and follow them exactly (the output format is there): ${W}/LANDSCAPE_MARKING_RULES.md.`,
    ``,
    `Inputs (private: the user's own game; don't copy the images anywhere else):`,
    `- Region data: ${W}/regions/${m.map}.json (w, h, a "grid" of region ids per cell row by row, -1 = walkable; per region: id, cells, bbox, floor_sides, front_rows, shares, doors_below).`,
    `- Chunk index: ${W}/chunks/${m.map}_chunks.json (each chunk image's cell window [x0, y0, x1, y1]).`,
    `- Chunk images: ${W}/chunks/${m.map}_r*_c*.png (${m.chunks} of them; 48 px a cell, cell numbers every 8 cells on the top and left edges, regions outlined in colours and numbered). Look at every chunk with the Read tool.`,
    ``,
    `Tips: use python3 (standard library only) to print the region grid as text so your boxes have exact cell coordinates; a region may hold several different things and must then be split into several sections with boxes. You may look the map up online (one or two web searches) to recall its layout.`,
    ``,
    `What matters most this time: the rock. Every "face", "mass" and "mouth" you mark becomes a 3D model of its own (faces of 3 cells or more), so read them carefully: a face is one continuous band of rock or earth drawing (with its rows: mark "rows" where the drawing's height differs from front_rows); a mass is a mountain, plateau or rock island nobody walks on (mark its "top" cells); a mouth is the face round a cave entrance (regions with doors_below). Boulders and rocks drawn as separate shapes, even in a line, are "objects", not a face; tree lines and forests are "trees"; houses, gates and towers are "building". Trees, objects and buildings keep their own models.`,
    ``,
    `Check before you finish: run \`python3 /home/claude/kantobridge/tools/landscape_sections/table.py ${W}/regions ${W}/marks ${W}/rollout/check/${m.map}_table.json ${m.map}\`; it must print one counts line for the map and no error. Every region must be in a section or in "unmarked".`,
    ``,
    `Write your result to ${out} and nowhere else (besides the check file above). Do not edit any repository, do not run builds, Gradle, tests or docker, do not start pods, do not use the network beyond one or two web searches. Return the structured result.`,
  ].join('\n')
}

phase('Mark')
log(`${args.length} maps to mark`)
const results = await pipeline(args, m => agent(prompt(m), { label: m.map, phase: 'Mark', schema: SCHEMA }))
const done = results.filter(Boolean)
log(`${done.length} of ${args.length} maps marked`)
return done
