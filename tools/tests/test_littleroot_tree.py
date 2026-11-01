"""Assembly, material and scope regressions for Littleroot's four-cell trees."""
import base64
import importlib.util
import io
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "littleroot_tree", ROOT / "tools/shape_fit/emerald/littleroot_tree.py")
tree = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tree)

# Actual arrangements around Littleroot, including the forest-interior row and
# the two edge variants. Rows here are ground cells, not vertical stack links.
LAYOUTS = (
    ((468, 469), (476, 477)),
    ((468, 469), (484, 485)),
    ((468, 469), (484, 487)),
    ((468, 469), (486, 485)),
    ((470, 469), (476, 477)),
    ((468, 471), (476, 477)),
)


def cell_voxels(entry):
    """Decode the published byte contract independently of the authoring code."""
    result = {}
    for layer_id, layer in enumerate([entry, *entry.get("up", [])]):
        raw = base64.b64decode(layer["v"], validate=True)
        paint = base64.b64decode(layer["t"], validate=True)
        assert len(raw) == 512 and len(paint) == 4096
        for i in range(4096):
            if raw[i // 8] & (1 << (i % 8)):
                result[(i % 16, i // 256 + layer_id * 16, i // 16 % 16)] = paint[i]
    return result


def assembled(layout, entries=None):
    entries = tree.generate() if entries is None else entries
    return {(x + cx * 16, y, z + cz * 16)
            for cz, row in enumerate(layout) for cx, mid in enumerate(row)
            for x, y, z in cell_voxels(entries[tree.KEYS[mid]])}


def connected(volume):
    reached = {next(iter(volume))}
    pending = list(reached)
    while pending:
        x, y, z = pending.pop()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0),
                           (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            neighbour = (x + dx, y + dy, z + dz)
            if neighbour in volume and neighbour not in reached:
                reached.add(neighbour)
                pending.append(neighbour)
    return reached == volume


@pytest.mark.parametrize("layout", LAYOUTS)
def test_whole_tree_has_no_disconnected_cell_or_vertical_layer_seams(layout):
    volume = assembled(layout)
    assert connected(volume)
    assert all(0 <= x < 32 and 0 <= z < 32 and 0 <= y < 40 for x, y, z in volume)
    assert max(y for _, y, _ in volume) >= 32
    # The crown joins across both ground-cell cuts, and across the two height
    # cuts. A fragment left at its old origin would fail these joins.
    for axis, cuts in ((0, (15,)), (1, (15, 31)), (2, (15,))):
        for cut in cuts:
            assert any(p[axis] == cut and
                       tuple(v + (i == axis) for i, v in enumerate(p)) in volume
                       for p in volume)


def test_rounded_crown_is_supported_by_a_narrow_trunk_without_a_shadow_slab():
    volume = assembled(LAYOUTS[1])
    base = {(x, z) for x, y, z in volume if y == 0}
    cross_sections = [{(x, z) for x, yy, z in volume if yy == y} for y in range(40)]
    assert 4 <= len(base) <= 100
    assert max(x for x, _ in base) - min(x for x, _ in base) <= 11
    assert max(z for _, z in base) - min(z for _, z in base) <= 11
    assert max(map(len, cross_sections)) >= 5 * len(base)
    crown_top = max(y for _, y, _ in volume)
    assert len(cross_sections[crown_top]) < max(map(len, cross_sections)) / 3
    assert max(x for x, _, _ in volume) - min(x for x, _, _ in volume) >= 27
    assert max(z for _, _, z in volume) - min(z for _, _, z in volume) >= 27


def test_tree_variants_keep_the_same_geometry_at_repeating_forest_boundaries():
    entries = tree.generate()
    first = assembled(LAYOUTS[0], entries)
    for layout in LAYOUTS[1:]:
        assert assembled(layout, entries) == first
    # Side-by-side repeated models must not double-claim voxels or create
    # detached fragments: every occupied point belongs to one connected tree.
    repeated = {(x + 32, y, z) for x, y, z in first}
    assert first.isdisjoint(repeated)
    assert connected(first) and connected(repeated)


def test_only_solid_tree_cells_are_authored_and_bundled_entries_are_reproducible():
    entries = tree.generate()
    assert set(tree.KEYS) == {468, 469, 470, 471, 476, 477, 484, 485, 486, 487}
    assert set(entries) == set(tree.KEYS.values())
    table = json.loads(tree.TABLE.read_text(encoding="utf-8"))
    for key, entry in entries.items():
        assert entry["class"] == "voxels"
        assert not entry.get("on") and not entry.get("walk") and not entry.get("seat")
        assert table["cells"][key] == entry
    # Walkable 462/463 are only sprite-reference caps; no new collision or
    # freestanding model is authored on the grass north of a tree.
    assert "g3q/10658-10659-10659-12318" not in entries
    assert "g3q/10658-10659-12319-10658" not in entries


def test_available_export_paints_the_crown_with_foliage_not_ground_or_shadow():
    bundle = ROOT / "local/minecraft/kantobridge/export/emerald"
    if not (bundle / "g3image/g3q.12.json").is_file():
        pytest.skip("optional user's local exported sprite pages are unavailable")
    Image = pytest.importorskip("PIL.Image")
    pages = {}

    def color(key, texel):
        quarters = list(map(int, key.split("/")[1].split("-")))
        u, v = texel % 16, texel // 16
        q = quarters[(v // 8) * 2 + u // 8]
        page_id, index = divmod(q, 1024)
        if page_id not in pages:
            doc = json.loads((bundle / "g3image" / f"g3q.{page_id}.json").read_text())
            pages[page_id] = Image.open(io.BytesIO(base64.b64decode(doc["png"]))).convert("RGB")
        return pages[page_id].getpixel(((index % 32) * 8 + u % 8, (index // 32) * 8 + v % 8))

    for key, entry in tree.generate().items():
        for (_, y, _), texel in cell_voxels(entry).items():
            if y >= 12:
                r, g, b = color(key, texel)
                assert g > r > b, (key, y, texel, (r, g, b))
