# Reviewing models in game

Model review mode lets you point at a model, highlight it, and save a comment with a screenshot and the information
needed to find its source. Reviews stay on your computer. Saving a review does not change a model or contact an AI
service; an assistant can inspect the saved review later and write a proposed fix for you to read in game.

## Record a problem

1. Load the Kanto Bridge world and stand where the problem is clearly visible.
2. Press **F9** to turn on model review mode. Aim the crosshair at the model and check the highlight.
3. Press **F10** to capture the highlighted surface on the next rendered frame, before the comment form opens.
   F10 also enables review mode if it is off. If there is no target, aim at a model and press it again.
4. Choose **Background**, **Missing parts**, **Shape**, **Texture**, or **Other**, then describe what should change.
   For example: "Remove the ground behind this plant, but keep its thin stem" or "The table needs four legs and
   an open space underneath."
5. Choose **Save**. **Cancel** closes the form without saving the draft.

The controls are rebindable in Minecraft's **Options → Controls → Key Binds → Kanto Bridge**. Press **F9** again
to leave review mode. Ordinary gameplay does not require review mode.

Press **Shift+F10** while playing, or choose **Saved notes** in the comment form, to read your reviews, their
status, and any proposed fix. Reopen the inbox after an assistant updates a review on disk so it loads the latest
proposal.

## Files and model identity

Each saved review has its own folder under the Minecraft game directory:

```text
kantobridge/model-reviews/<review-id>/
  review.json
  screenshot.png       (when capture succeeded)
```

For the development client, the default root is `local/minecraft/kantobridge/model-reviews/`. A different Minecraft
instance or `kb.clientRunDir` has its own root. The review records the comment, category, model selection and its
available world/region/game context alongside the screenshot. Use the recorded source keys and hashes when
investigating a review: a location or numerical shape ID alone may refer to different content after a rebuild.

The highlight outlines the selected rendered fragment. Capturing the review also records the containing fitted
cell or building template and its origin when available; the outline does not expand to the whole building.
An amber highlight means this same fragment has an unresolved note. If geometry is completely missing, aim at its
floor/location and describe what should be there. Review mode selects scenery models, not NPCs or Pokémon.

Blue, Crystal and Emerald have separate model tables. An assistant should check the review's game before editing
a table, mask, parts specification or import pipeline. A shared runtime change may affect all three games and
needs corresponding validation.

## Working through reviews with an assistant

Tell the assistant which review folder to read and whether you want a proposal or an implementation. A useful
review pass is:

1. Read the note and screenshot, identify the selected occurrence and the generation path that produced it, and
   compare it with any available source reference.
2. For unwanted background or missing pixels, inspect the foreground mask, extraction bounds and neighbouring
   cells first. Make a targeted mask correction when that explains the problem.
3. For depth or volume problems, inspect the fitted shape and its recorded edits. Preserve intentional holes,
   supports and repeated structures. Use a depth adjustment where it is sufficient; use parts or a hand model
   when the object's structure needs explicit modelling.
4. For misplaced colours, inspect paint selection and UV mapping as well as geometry. Increasing voxel resolution
   will not correct the wrong source pixel.
5. Write a proposal that names the likely cause, the specific change, the affected models and how to check it.
   The proposal appears in **Saved notes** when the inbox is reopened.
6. After implementation, rebuild or reload the affected content and revisit the same view. Check other sides and
   nearby repeated uses before marking the review complete.

The helper `tools/model_reviews.py` reads and updates these local review files. Use its `--help` for the available
commands; it supports listing and showing reviews, writing a proposal from a UTF-8 text file, and updating status.
Pass `--root` to work on a particular Minecraft instance; its default is this repository's
`local/minecraft/kantobridge/model-reviews`.

```powershell
python tools/model_reviews.py --root "local/minecraft/kantobridge/model-reviews" list
python tools/model_reviews.py --root "local/minecraft/kantobridge/model-reviews" show <review-id>
python tools/model_reviews.py --root "local/minecraft/kantobridge/model-reviews" propose <review-id> --text-file proposal.txt --method mask
python tools/model_reviews.py --root "local/minecraft/kantobridge/model-reviews" status <review-id> resolved
```

Statuses are `open`, `proposed` and `resolved`. Writing a proposal sets the status to `proposed`; its method can be
`mask`, `depth_rule`, `hand_model` or `other`. Categories are stored as `background`, `missing_parts`, `shape`,
`texture` or `other`. `list --status open` filters the inbox and `list --json` provides machine-readable output.

Use a working Python installation for these commands. Replace `<review-id>` with the UUID shown by `list`. The
review workflow does not automatically apply a proposal or send notes elsewhere.

## Build and manual verification

Compile with this project's pinned JDK 21. On the development Windows machine:

```powershell
cd fabric-bridge
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat compileClientJava test '-Pkb.testHeap=4g'
```

The heap option applies only to tests; the full unit suite can exceed Gradle's default test heap.
`build` also runs the project's server GameTests. Compiling and running unit tests do not verify the in-game
appearance or input flow. The following playtest is still required on a running client:

- Toggle review mode on and off. Confirm the keys can be rebound and normal interaction still works.
- Aim at a small prop, a tall building, a decorative surface and an empty part of a model. Confirm the selection
  follows the visible surface and does not select a hidden model through an intervening wall.
- Save a note in each of the three game regions. Check that its region and game identify the model you selected.
- Press F10 while a model is highlighted. Check that the screenshot shows the selected model before the form,
  and that turning the camera or moving later cannot change the saved selection.
- Enter a multiline note, save it, reopen **Saved notes**, and confirm its text and category. Cancel another draft
  and confirm it did not create a saved review.
- Write a proposal and change a status with the helper, reopen **Saved notes**, and confirm the update appears.
- Resize the window or change GUI scale while composing a note. Confirm the text remains usable and the Save and
  Cancel buttons stay reachable.
- Leave and rejoin the world. Confirm saved reviews remain readable and an old selection is not reused in the
  new session.

Screenshots and notes are local working material. They are not added to the project's distributable model tables.

## Implementation checks (2026-10-31)

Client compilation and mod packaging succeeded. The 16 review-specific Java tests, 10 Python queue tests, and all
148 existing server GameTests passed. The complete Java suite ran with `-Pkb.testHeap=4g`: 1,573 tests, 16 skipped,
one failure in the unchanged `KbPackReloadTest.modelsReadFitAndStayWithinTheBudget`. That test still expects a
`pack.model_budget` rejection, while `CellUnits.MAX_QUADS` was already deliberately disabled. The review feature
does not change that policy. The in-game visual/input checklist above remains for the user's playtest.

The Python checks run with `python -m pytest -c tools/pytest.ini tools/tests/test_model_reviews.py -q` from the
repository root. The host test environment reports an unavailable optional `timeout` pytest setting; these checks
passed without that plugin.
