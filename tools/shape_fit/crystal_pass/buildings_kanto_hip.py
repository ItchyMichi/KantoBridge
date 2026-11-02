"""Crystal checkpoint 30: the Kanto hip-roof buildings (houses and Gyms of Viridian, Pewter, Cerulean, Vermilion, Lavender,
Celadon, Fuchsia, Saffron, Pallet, Mount Moon Square and Route 28).

Source reading (building_kit.kanto_read; the pixel maps of b094, b076, b093, b052): a hip roof seen from the front: its
front field (rows 1..E-3) narrowing to the top, the triple eave line (rows E-2..E), the hatched side slopes running down at
both corners to the row under the window band; the window band (rows E+1..E+8) between them; below it the stone base (a
one-storey house) or the ground floor with its door, windows and the Gym's signs, grey stone pillars at both ends; in
Pallet's and Fuchsia's two-storey houses an awning band (a roof strip) between the window band and the ground floor.

Built by building_kit.kanto: a plinth; the lower walls between the stone pillars (the facade under the band on the front);
a hip roof over the footprint at 45 degrees from the band's bottom, in the drawn hatch; the window band as a wide dormer
standing out of the roof's front slope, roofed by the drawn front field sloping back into the hip; the awning as a lean-to
over the ground floor; the door a real recess with the door at its back. The Kanto windows are light panes on a light wall
(no colour separates them), so they stay painted on the facade.
"""
from __future__ import annotations

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import drawing
from tools.shape_fit.crystal_pass.buildings_johto_a import readings_sheet
from tools.shape_fit.crystal_pass.common import Pass

BUILDINGS = {
    "crystal_b048_G2_CELADON_CITY": "Celadon Gym",
    "crystal_b049_G2_CELADON_CITY": "Celadon house",
    "crystal_b050_G2_CERULEAN_CITY": "Cerulean Gym (two signs)",
    "crystal_b052_G2_CERULEAN_CITY": "Cerulean house (two storeys)",
    "crystal_b053_G2_CERULEAN_CITY": "Cerulean house (two storeys)",
    "crystal_b054_G2_CERULEAN_CITY": "Cerulean long house",
    "crystal_b055_G2_CERULEAN_CITY": "Cerulean house (6x2)",
    "crystal_b056_G2_CERULEAN_CITY": "Cerulean house (6x2)",
    "crystal_b057_G2_CERULEAN_CITY": "Cerulean small house",
    "crystal_b065_G2_FUCHSIA_CITY": "Fuchsia Gym",
    "crystal_b066_G2_FUCHSIA_CITY": "Fuchsia house",
    "crystal_b067_G2_FUCHSIA_CITY": "Fuchsia house",
    "crystal_b068_G2_FUCHSIA_CITY": "Fuchsia house (two storeys, awning)",
    "crystal_b070_G2_LAVENDER_TOWN": "Lavender house",
    "crystal_b076_G2_PALLET_TOWN": "Pallet house (two storeys, awning)",
    "crystal_b078_G2_PEWTER_CITY": "Pewter Gym",
    "crystal_b079_G2_PEWTER_CITY": "Pewter house",
    "crystal_b081_G2_SAFFRON_CITY": "Saffron Gym",
    "crystal_b082_G2_SAFFRON_CITY": "Saffron Fighting Dojo and Gym (two signs)",
    "crystal_b085_G2_VERMILION_CITY": "Vermilion Gym",
    "crystal_b086_G2_VERMILION_CITY": "Vermilion house",
    "crystal_b087_G2_VERMILION_CITY": "Vermilion house",
    "crystal_b093_G2_VIRIDIAN_CITY": "Viridian Gym",
    "crystal_b094_G2_VIRIDIAN_CITY": "Viridian house (no door this side)",
    "crystal_b095_G2_VIRIDIAN_CITY": "Viridian house",
    "crystal_b098_G2_MOUNT_MOON_SQUARE": "Mount Moon Square shop",
    "crystal_b103_G2_ROUTE_28": "Route 28 house",
}


def overlay(img, k, scale=3):
    from PIL import Image, ImageDraw
    im = img.resize((img.width * scale, img.height * scale), Image.Resampling.NEAREST)
    dr = ImageDraw.Draw(im)
    for z, col in ((k.eave + 1, (0, 220, 255)), (k.eave + 9, (0, 120, 255))):
        dr.line([(0, z * scale), (im.width, z * scale)], fill=col, width=2)
    if k.awning:
        dr.rectangle([0, k.awning[0] * scale, im.width - 1, k.awning[1] * scale - 1], outline=(255, 0, 255), width=2)
    if k.door:
        x0, z0, x1, z1 = k.door
        dr.rectangle([x0 * scale, z0 * scale, x1 * scale - 1, z1 * scale - 1], outline=(255, 0, 0), width=2)
    return im


def main():
    out = Pass("buildings-kanto-hip")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, label in BUILDINGS.items():
        img = drawing(table[bid])
        k = BK.kanto_read(img)
        items.append((bid, overlay(img, k)))
        what = ", ".join(x for x in ("an awning over the ground floor" if k.awning else "",
                                     "the door recessed" if k.door else "no door on this side") if x)
        out.building(bid, BK.kanto(k), label=label, family="bk-hip",
                     reason=f"The Kanto hip-roof archetype from its drawing (eave line row {k.eave}): lower walls between the "
                            f"stone pillars, a hip roof in the drawn hatch, the window band as a dormer under the drawn front "
                            f"field, {what}.")
    out.write(owned=list(BUILDINGS))
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
