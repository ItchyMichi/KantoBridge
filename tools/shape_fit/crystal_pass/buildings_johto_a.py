"""Crystal checkpoint 28: the modern Johto town buildings, first half (New Bark, Azalea, Cherrygrove, Violet, Ecruteak's
Pokémon Center, Mart, Gym and small house, Olivine's).

Source reading (buildings-overview/drawings-*.png; building_kit.read's overlays, `readings.png` here). Johto's town
buildings are one archetype in each town's roof art: a roof seen from above (its field, then a rim and the dark eave lines)
over a facade (ochre corner posts, a dark outline, the wall's art, a brown wainscot, the ochre base row). The Pokémon
Center and Mart stand a sign on two legs beside the door; the Gym has its sign on the wall over the door.

Built by building_kit.johto from each drawing's reading: a plinth; walls behind the corner posts (the facade's art over the
front, a plain swatch of the same wall along the sides and back), scaled as Codex's accepted b010 (facades of 16 rows 1.375x,
of 28 or more 1:1); a plain gable over the whole footprint (the ridge along x at the middle, the eaves overhanging the
front by three voxels), the roof's field laid along the slope, its eave bands on the edges and the wall's swatch on the
gables; the door a real recess four voxels deep with the door at its back over the step; windows and wall signs a voxel
proud; the standing sign a board on its two legs in front.
"""
from __future__ import annotations

from PIL import Image, ImageDraw

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import drawing
from tools.shape_fit.crystal_pass.common import Pass

BUILDINGS = {
    "crystal_b000_G2_NEW_BARK_TOWN": ("New Bark house (6x4)", "bj-new-bark"),
    "crystal_b001_G2_NEW_BARK_TOWN": ("New Bark house (4x4)", "bj-new-bark"),
    "crystal_b002_G2_NEW_BARK_TOWN": ("New Bark small house", "bj-new-bark"),
    "crystal_b003_G2_AZALEA_TOWN": ("Azalea small house", "bj-azalea"),
    "crystal_b004_G2_AZALEA_TOWN": ("Azalea Mart", "bj-azalea"),
    "crystal_b005_G2_AZALEA_TOWN": ("Azalea Pokémon Center", "bj-azalea"),
    "crystal_b006_G2_AZALEA_TOWN": ("Azalea Gym", "bj-azalea"),
    "crystal_b058_G2_CHERRYGROVE_CITY": ("Cherrygrove Mart", "bj-cherrygrove"),
    "crystal_b059_G2_CHERRYGROVE_CITY": ("Cherrygrove Pokémon Center (4x3)", "bj-cherrygrove"),
    "crystal_b060_G2_CHERRYGROVE_CITY": ("Cherrygrove small house", "bj-cherrygrove"),
    "crystal_b089_G2_VIOLET_CITY": ("Violet Mart", "bj-violet"),
    "crystal_b090_G2_VIOLET_CITY": ("Violet Gym", "bj-violet"),
    "crystal_b091_G2_VIOLET_CITY": ("Violet Pokémon Center", "bj-violet"),
    "crystal_b092_G2_VIOLET_CITY": ("Violet small house", "bj-violet"),
    "crystal_b011_G2_ECRUTEAK_CITY": ("Ecruteak Pokémon Center", "bj-ecruteak"),
    "crystal_b012_G2_ECRUTEAK_CITY": ("Ecruteak Mart", "bj-ecruteak"),
    "crystal_b013_G2_ECRUTEAK_CITY": ("Ecruteak Gym", "bj-ecruteak"),
    "crystal_b014_G2_ECRUTEAK_CITY": ("Ecruteak small house", "bj-ecruteak"),
    "crystal_b017_G2_OLIVINE_CITY": ("Olivine Gym", "bj-olivine"),
    "crystal_b018_G2_OLIVINE_CITY": ("Olivine small house", "bj-olivine"),
    "crystal_b019_G2_OLIVINE_CITY": ("Olivine Mart", "bj-olivine"),
    "crystal_b020_G2_OLIVINE_CITY": ("Olivine Pokémon Center", "bj-olivine"),
    "crystal_b021_G2_OLIVINE_CITY": ("Olivine house (4x4)", "bj-olivine"),
}


def readings_sheet(out, items):
    W = 4 * 300
    rows = (len(items) + 3) // 4
    sheet = Image.new("RGB", (W, rows * 230), "white")
    dr = ImageDraw.Draw(sheet)
    for i, (bid, im) in enumerate(items):
        im.thumbnail((290, 210))
        x, y = (i % 4) * 300, (i // 4) * 230
        sheet.paste(im, (x + 5, y + 18))
        dr.text((x + 5, y + 3), bid.replace("crystal_", ""), fill="black")
    sheet.save(out / "readings.png")


def main():
    out = Pass("buildings-johto-a")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, (label, family) in BUILDINGS.items():
        img = drawing(table[bid])
        f = BK.read(img)
        if f.top is None:
            raise SystemExit(f"{bid}: no facade line")
        items.append((bid, BK.overlay(img, f, 3)))
        what = ", ".join(x for x in (
            "the door recessed" if f.door else "no door on this side",
            f"{len(f.windows)} windows" if f.windows else "",
            {"stand": "the sign on its legs", "wall": "the wall sign"}.get(f.signs[0][1], "") if f.signs else "") if x)
        out.building(bid, BK.johto(f), label=label, family=family,
                     reason=f"The Johto archetype from its drawing (facade from row {f.top}): walls behind the corner posts, "
                            f"a gable over the footprint with the roof art along its slope, {what}.")
    out.write(owned=list(BUILDINGS))
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
