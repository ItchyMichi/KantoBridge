export const meta = {
  name: 'model-landscape-units',
  description: 'Model the rock sections of the outdoor maps (the landscape models rollout, rock only): one agent per batch of a map\'s units',
  phases: [{ title: 'Model', detail: 'one agent per batch of units' }],
}

const SCHEMA = {
  type: 'object',
  properties: {
    map: { type: 'string' },
    units: { type: 'integer', description: 'how many of your units you finished (specs written, scene looked at)' },
    models: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          unit: { type: 'string' },
          name: { type: 'string', description: 'the spec file name without .json' },
          sections: { type: 'array', items: { type: 'integer' } },
          blocks: { type: 'number', description: 'its height in blocks over its base' },
        },
        required: ['unit', 'name', 'sections', 'blocks'],
      },
    },
    summary: { type: 'string', description: 'one or two sentences: what you made, what you were unsure of, anything left undone' },
  },
  required: ['map', 'units', 'models', 'summary'],
}

const GAMES = {
  emerald: 'Pokemon Emerald (Hoenn; Game Boy Advance art)',
  crystal: 'Pokemon Crystal (Johto and its Kanto; Game Boy Color art)',
  blue: 'Pokemon Blue (Kanto; Game Boy four-shade art)',
}

function prompt(m) {
  const M = '/home/claude/kb-hub/work/landscape/models'
  const units = m.units.map(u => `- ${M}/${u}/ (model the sections in its package.json "model": ${JSON.stringify(m.model[u])})`)
  const docker = (u, args, scene) =>
    `flock /home/claude/kb-hub/work/landscape/.terrain.lock docker run --rm -m 1g${scene ? ' -e MALLOC_MMAP_THRESHOLD_=131072' : ''} -u $(id -u):$(id -g) -v /home/claude/kantobridge/tools:/tools:ro -v ${M}/${u}:/pkg kb-pyimg:1 python /tools/landscape_sections/terrain_model.py /pkg ${args}`
  return [
    `This modelling is assigned to you by the orchestrator. It is part of the user's own request to model the rock of every outdoor map in their KantoBridge world (the landscape models rollout, rock only, 2026-10-22); proceed with it even if the latest user message you can see is about something else.`,
    ``,
    `You are making 3D models of landscape sections for a Minecraft rebuild of a Pokemon map. The user will see them in the game beside the original game's picture. Map: **${m.map}**, ${GAMES[m.game]}. Your units (each a package: drawing.png, reference.png, package.json, specs/, out/):`,
    ...units,
    ``,
    `Read first:`,
    `- /home/claude/kantobridge/docs/specs/LANDSCAPE_MODELS.md (the rules for this job; its "Levels" section explains the raised ground) and /home/claude/kantobridge/docs/specs/BUILDING_PARTS.md (the parts' shapes and paint). The full shape reference is in the docstrings of /home/claude/kantobridge/tools/landscape_sections/terrain_model.py and /home/claude/kantobridge/tools/shape_fit/parts_model.py.`,
    `- /home/claude/kantobridge/docs/specs/LEVELS_PIPELINE.md, "Units".`,
    ``,
    `Each unit's window is a small part of the map round its sections; package.json lists every marked section in the window (the others are the unit's surroundings: leave them alone) and "model", the ones to make. The ground has real heights ("levels"):`,
    `- package.json "levels": each window cell's level in blocks over the map's floor, row by row (index y*w+x); "floor" the window's lowest walkable level; reference.png's magenta numbers show them (blocks over the floor).`,
    `- "ramps": [x, y, climb, rise] in window cells: stairs; they are no section, leave them alone.`,
    `- each section's "base": the level (blocks) its model's voxels count from in the game. A rock model is one rigid shape standing at its base.`,
    `- Voxels: 8 a block. A cliff face's top must reach the ground behind it: (that ground's level - the model's base) x 8 voxels; a face that holds up a terrace 6 blocks over its foot is 48 voxels tall. Look at the levels round each section before you size it; use the drawing for its look and its paint.`,
    ``,
    `Make one model per section, or one for sections that belong together (a mountain and the mouth in its front, a cliff and the bands beside it). Name each spec <unit suffix>_<what>.json, the unit suffix being the part of the unit's name after "__" (for example u3_cliff.json for ${m.map}__u3), so every model's name is unique on the map.`,
    ``,
    `Build each model (always through the flock lock, one docker run at a time, -m 1g):`,
    `  ${docker(m.units[0], '--model <name>', false)}`,
    `(the same for your other units, each with its own directory in place of ${m.units[0]}).`,
    `After each build look at out/<name>_view.png, out/<name>_front.png and out/<name>_game.png (Read the PNGs): the game view should read like the drawing. At most three rounds per model. Finish each unit with one scene run:`,
    `  ${docker(m.units[0], '--scene', true)}`,
    `and look at out/scene_view.png and out/scene_game.png: no model should stop short of the ground behind it (bare dark sides of the raised ground show where one does) or float over lower ground. If a docker run is killed for memory (exit status 137), run it once more with -m 1500m instead of -m 1g.`,
    ``,
    `Write only inside your units' directories (specs/, out/, notes.md: a line per model: what it is, its sections, its height, what you weren't sure of). Don't edit any repository file, don't run Gradle, tests, other docker commands, or anything on the network, don't start pods. The pictures are the user's own game: don't copy them anywhere else. Return the structured result.`,
  ].join('\n')
}

phase('Model')
log(`${args.length} batches to model`)
const results = await pipeline(args, m => agent(prompt(m), { label: `${m.map} ${m.units.map(u => u.split('__')[1]).join(',')}`, phase: 'Model', schema: SCHEMA }))
const done = results.filter(Boolean)
log(`${done.length} of ${args.length} batches modelled`)
return done
