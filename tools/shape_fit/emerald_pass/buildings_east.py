"""Audited Emerald eastern-city and Frontier architecture (b060..b111).

Every assigned entry has its own semantic finding. Patches contain only evidenced
geometry/UV corrections; sound existing assemblies remain unchanged. All output
is local and candidates require Minecraft visual review before acceptance.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path

from tools.shape_fit.emerald import building_trials as p
from tools.shape_fit.emerald_pass import buildings as batch, review

ROOT = batch.ROOT
OUT = ROOT / 'local/model-gallery/emerald-pass-study/buildings-east'
EXCLUDED = {59, 64, 65, 85, 86}

# Findings refer to the reconstructed drawing and actual existing parts, not a
# filename-derived assumption. A retained decision still awaits runtime views.
FINDINGS = {
60: ('Petalburg red-tile house', 'tile-house', 'Replace the false raised rear storey with one continuous red-tile gable, fascia, a recessed central door and separate front windows.'),
61: ('Devon headquarters', 'civic-office', 'Retain the three grounded wings, projecting central entrance, three recessed rooftop parapets and clear door recess; the source has a tall central bay with flanking windowed wings.'),
62: ('Rustboro Gym', 'gym', 'Separate the ochre roof from its grey walls, retain the projecting vestibule and carve its blue double-door recess; the old flat wall box used roof paint as its entire top.'),
63: ('Rustboro stone cottage', 'stone-house', 'Retain the bounded masonry volume, low inset roof with parapet, foundation strip and recessed central entrance; source windows and stone courses remain facade-local.'),
66: ('Rustboro long apartment', 'parapet-apartment', 'Retain the broad windowed wall, recessed flat roof, raised northeast rooftop vent and central door recess; their distinct footprints match the long source frontage.'),
67: ('Rustboro compact apartment', 'parapet-apartment', 'Preserve the compact two-window frontage, recessed parapet roof, northeast vent and central door recess; replace the striped edge swatch with uninterrupted native masonry.'),
68: ('Rustboro four-window apartment', 'parapet-apartment', 'Retain the long four-window wall and centred door recess, inset roof and raised northeast vent; the side uses the masonry strip between structural features.'),
69: ('Rustboro three-window apartment', 'parapet-apartment', 'Retain the vent-free inset roof, three-window facade and off-centre recessed entrance; this is the separately bounded source variant, not a scaled vented apartment.'),
70: ('Rustboro narrow stone tower', 'stone-tower', 'Retain the four-window two-level facade, base plinth and recessed rooftop parapet; no new entrance is invented on this doorless source segment.'),
71: ('Rustboro tall stone apartment', 'stone-tower', 'Retain the tall multi-level masonry wall, parapet roof and off-centre entrance recess; its twelve-window facade is kept intact with separate roof paint.'),
72: ('Slateport Contest Hall', 'contest-hall', 'Keep the round hall silhouette but separate its white contest badge from the stretched dome texture, add the supported roof oculus and recess the vestibule doors.'),
73: ('Slateport purple-tile house', 'tile-house', 'Replace two unrelated flat slabs with a continuous purple gable and ridge, preserving the left door and right window as separate facade details.'),
74: ('Slateport long purple-tile wing', 'tile-house-wing', 'Replace the raised rear strip with one continuous long gable and two discrete window panels; this wing has no drawn door, so none is added.'),
75: ('Slateport green-roof porch house', 'porch-house', 'Retain the curved hip profile and supported open porch, but crop grass from the wall sides and roof corners; the source grass is background outside the physical green-roof house.'),
76: ('Slateport harbor terminal', 'harbor-terminal', 'Retain the broad terminal, wide entrance and twelve separate domed rooftop fixtures; crop the grassy lower corners from the front wall material while preserving the fixtures above the green roof.'),
77: ('Slateport shipyard warehouse', 'warehouse', 'Give the corrugated warehouse one shallow gabled roof and its two supported rooftop vents while preserving the ground-level entrance and existing siding volume.'),
78: ('Slateport produce canopy', 'market-canopy', 'Connect all four posts to an explicit perimeter roof frame and hollow the two produce tubs; retain the source-matched hipped fabric roof and open market floor.'),
79: ('Slateport jar canopy', 'market-canopy', 'Remove the artificial paved-floor slab and hollow the two pottery jars while retaining the four connected posts, perimeter frame and hipped fabric canopy.'),
80: ('Slateport orange canopy on grass', 'market-canopy', 'Extend the rear supports into the canopy frame so the fabric roof is structurally connected; preserve the open floor and orange diamond-pattern cloth.'),
81: ('Slateport orange canopy on paving', 'market-canopy', 'Retain the four unequal-height posts, sloping fabric profile and front valance; the taller rear supports intersect the roof and no ground slab fills the stall.'),
82: ('Slateport small sailboat', 'sailboat', 'Retain the narrow oval hull, separate deck, grounded mast and two thin triangular sail profiles; sea pixels remain outside the occupied hull footprint.'),
83: ('Slateport paired sailboat mooring', 'paired-sailboats', 'The actual source map contains two overlapping north/south moored boats. Replace the single hull and duplicated hull pixels on its tall sail with two separate oval hulls, recessed decks, masts and thin native cream/cyan sails.'),
84: ('Verdanturf Contest Hall', 'contest-hall', 'Retain the domed shell and existing oculus but place the white badge on a supported upright sign and restore blue doors behind the actual vestibule recess.'),
87: ('Verdanturf narrow green-tile house', 'tile-house', 'Join the green-tile roof into a single coherent gable and ridge with the source left door recessed and the small right window represented separately.'),
88: ('Verdanturf broad green-tile house', 'tile-house', 'Replace the asymmetric flattened roof profile with a symmetric gable and ridge; preserve the central entrance and two separated yellow windows.'),
89: ('Battle Dome', 'battle-dome', 'Retain the oval ring, raised dome shell, stepped glass lobby, orange entrance canopy and real entry recess; add supported raised silver ribs matching the five source roof ribs.'),
90: ('Battle Factory', 'battle-factory', 'Replace the rectangular shared slab with three individually chamfered towers, taller central bay and independent recessed entrance; this preserves the source three-lobed outline.'),
91: ('Battle Pike', 'battle-pike', 'Replace the unsupported vertical tail post with a continuous curved taper rising from the rear body inside its supported footprint; the upper source tail is perspective over ground, not a grounded path across excluded grass. Add supported golden back plates and retain the rounded body, mouth and entrance ramp.'),
92: ('Frontier glass-front kiosk west', 'frontier-kiosk', 'Retain the rounded orange roof and glass frontage but deepen and paint the recessed entrance, add a threshold, and use the blank wall material on the rear.'),
93: ('Frontier red-door cottage west', 'frontier-cottage', 'Retain the rounded orange roof but replace the empty paint-cut entrance with an inset red door and threshold; remove the repeated front door from rear-wall paint.'),
94: ('Frontier long red-door cottage', 'frontier-cottage', 'Retain the long rounded orange roof and off-centre red door; give that door a proper painted recess and threshold without adding windows absent from the source.'),
95: ('Frontier cropped glass kiosk', 'frontier-kiosk', 'Preserve the distinct cropped roof drawing and compact footprint; provide the central blue entrance with real depth, a door slab and threshold, plus plain rear siding.'),
96: ('Frontier cropped Mart', 'poke-mart', 'Isolate the circular Mart badge from the blue roof, clean the rear siding, and give the cropped facade a painted inset entrance and threshold.'),
97: ('Frontier western reception', 'frontier-reception', 'Retain two separate curved-roof glass wings and the sheltered central entrance; move the contest badge from the buried rear roof position onto the supported front pediment.'),
98: ('Frontier stone arch', 'stone-arch', 'Retain two carved-column piers and the stepped arched lintel; the rectangular plus barrel cuts form a genuinely open full-depth passage under the source badge.'),
99: ('Battle Tower', 'battle-tower', 'Replace the false staircase skyline with a continuous tall facade and two brick corner piers; the source facade continues off its north crop and does not depict four different roof heights. Use an inset flat top, separate forecourt canopy and recessed central door.'),
100: ('Frontier glass-front kiosk east', 'frontier-kiosk', 'Preserve this east-map source variant while adding a painted inset entrance and threshold below its orange rounded roof; plain siding replaces the repeated rear frontage.'),
101: ('Frontier eastern reception north', 'frontier-reception', 'Preserve the long rounded roof and projecting entry porch; isolate the white contest badge on the forward raised roof and add a door slab behind the clear recess.'),
102: ('Frontier eastern reception south', 'frontier-reception', 'Preserve the wide glass frontage and raised central roof; isolate the white badge from curved-roof paint and provide a painted entrance at the back of the recess.'),
103: ('Battle Pyramid', 'battle-pyramid', 'Retain thirteen separately inset stone terraces, a projecting central entrance and the source-aligned approach ramp; carve the entrance arch above its doorway and expose the lower colonnade as independent piers.'),
104: ('Frontier red-door cottage east', 'frontier-cottage', 'Retain the east-map orange-roof variant, replacing its empty recess with a source-painted red door and grounded threshold and removing the rear repeated door.'),
105: ('Battle Arena', 'battle-arena', 'Retain the surrounding courtyard walls, central gate opening and large hipped main roof; add physical front support columns and inset the lattice door behind the porch.'),
106: ('Battle Arena outer gate', 'japanese-gate', 'Retain the grounded paired pillars, separate inner posts, horizontal lintels and two roof profiles; the central path stays open through the full gate depth.'),
107: ('Battle Palace', 'battle-palace', 'Retain the four independently overhanging gold roof masses and projecting entrance portico; add the source red corner columns and a dark door slab behind the recess.'),
108: ('Mt Chimney cable station', 'cable-station', 'Retain the asymmetric station body, broad orange curved roof and wide cable entrance; side wall swatches come from the source station, not the adjacent volcanic ground.'),
109: ('Safari reception', 'safari-gatehouse', 'Remove grass from the bottom of the reception wall UV, retain the inset parapet roof and awning, and give the recessed entrance its source double doors.'),
110: ('Sky Pillar open-door tower', 'ancient-tower', 'Retain the open-door source identity and 28/30-cell near-match evidence, rebuild the plain tower sides with clean masonry, add supported corner pilasters and recessed windows, and keep the entrance truly arched.'),
111: ('Sky Pillar summit doorway', 'ancient-arch', 'Retain the short summit wall, projecting door surround and genuine barrel-topped opening; the source is an arch on the roof terrace, not another full tower.'),
}

RUNTIME_FINDINGS = {
60: 'Align the separate window panels exactly with their source rectangles on the wall, removing shifted duplicate frames.',
62: 'Use the white vestibule side above its projected paving corner; no paving pixels remain on its physical wall.',
67: 'The left uninterrupted masonry strip replaces a narrow dark edge that repeated as dense vertical stripes.',
70: 'Runtime side review found repeated clipped windows. Side and rear now sample the uninterrupted left masonry strip; the original four front windows remain intact.',
71: 'Runtime side review found repeated clipped windows. Replace side and rear paint with the uninterrupted left masonry strip while preserving all twelve facade windows.',
75: 'The lower wall now ends at z80, before the two excluded front corner cells; the central recessed porch stays supported.',
79: 'Jars now have rounded shoulders, narrower dark rims and hollow mouths, with native brown bodies and golden highlights; paving is excluded from all jar surfaces.',
80: 'Replace green background pixels at the valance edge with physical shallow notches, retaining the orange cloth band.',
81: 'Restore the three WALK carrier cells under the front cloth as noncolliding overhead decoration; geometry is empty below y18 and the complete roof crosses the open floor.',
82: 'Hull paint now uses the central timber stripe; sea and green shoreline pixels are excluded from the oval hull.',
87: 'Exclude source row63 grass and align the window panel to the wall UV so there is only one window frame.',
88: 'Exclude source row63 grass and align both window panels to the wall UV so their frames are not duplicated.',
90: 'Runtime carrier clipping exposed open side triangles; the renderer now clips omitted carrier columns before meshing so the tower sides seal at the actual footprint.',
97: 'Open the central walkway through the full vestibule depth rather than painting the source path upright as a door.',
98: 'Restore only the three central WALK carrier cells under the lintel. All nine cells across its three map occurrences have exported collision0; the central opening remains empty below y53.',
99: 'Claim the fourteen source-solid facade cells in row14 and join their low glass, brick and portico bases to the main tower. Keep the two WALK notches and central door cell excluded, with the recessed entrance behind them; source grass is cropped from the facade material.',
100: 'Crop the curved roof inside its orange border, excluding green background pixels at its rear corners.',
101: 'Use the uninterrupted pale-blue wall column on the rear and sides rather than repeating the narrow white edge seam.',
104: 'Crop the curved roof inside its orange border, excluding green background pixels at its rear corners.',
103: 'Claim the five source-solid rear cells under the projected upper terraces and join them to the first terrace with a low native-stone foundation; all surrounding WALK roof projections remain excluded.',
105: 'Restore only the nine WALK carrier cells at source row2 under the rear roof; there is no geometry below y46 in those cells.',
107: 'Raise the supporting middle roof to intersect the upper roof instead of leaving the upper roof floating above its tapered peak.',
109: 'Map the short source windows to a separate22-high upper wall band; the lower18-high wall uses repeated native siding instead of vertically stretching the windows.',
110: 'Place a dark native arch back surface at z66 while preserving the deep arched opening in front.',
}
for _number,_finding in RUNTIME_FINDINGS.items():
    _label,_family,_reason=FINDINGS[_number]
    FINDINGS[_number]=(_label,_family,_reason+' '+_finding)


def solid(rect):
    return p.paint(rect, rect, rect, rect)


def door(parts, x0, x1, front, high, rect):
    """Five-voxel recess with art at its back and a grounded two-voxel threshold."""
    parts.extend([p.box([x0,x1],[front-5,front+1],[0,high],carve=True),
                  p.box([x0,x1],[front-5,front-4],[0,high],p.paint(rect, [rect[0],rect[1],rect[2],rect[1]+1], [rect[0],rect[1],rect[0]+1,rect[3]])),
                  p.box([x0-1,x1+1],[front-4,front+1],[0,2],solid([rect[0],rect[3]-2,rect[2],rect[3]]))])


def tile_house(width, wall_top, door_rect, windows, roof_rect, wall_rect, *, align_windows=False):
    wall=p.paint(wall_rect,[4,wall_rect[1]+2,8,wall_rect[1]+6],[4,wall_rect[1]+2,8,wall_rect[3]-2])
    roof=p.paint([2,roof_rect[3]-5,width-2,roof_rect[3]],roof_rect,[3,roof_rect[1]+2,7,roof_rect[3]-2])
    parts=[p.box([3,width-3],[10,60],[0,wall_top],wall),
           dict(shape='gable',ridge='x',x=[1,width-1],z=[6,63],y=[wall_top,wall_top+24],paint=roof),
           p.box([1,width-1],[32,37],[wall_top+22,wall_top+25],solid([3,1,width-3,4])),
           p.box([1,width-1],[60,63],[wall_top-2,wall_top+1],solid([2,roof_rect[3]-2,width-2,roof_rect[3]]))]
    for r in windows:
        y=[wall_top*(wall_rect[3]-r[3])/(wall_rect[3]-wall_rect[1]),
           wall_top*(wall_rect[3]-r[1])/(wall_rect[3]-wall_rect[1])] if align_windows else [12,27]
        parts.append(p.box([r[0],r[2]],[60,61],y,p.paint(r,[r[0],r[1],r[2],r[1]+1],[r[0],r[1],r[0]+1,r[3]])))
    if door_rect: door(parts,door_rect[0],door_rect[2],60,27,door_rect)
    return dict(height=wall_top+28,parts=parts)


def paired_boats():
    """Two physical boats evidenced by the two complete source hulls 16px apart."""
    parts=[]
    cream=solid([34,10,35,11]);cyan=solid([20,10,21,11]);dark=solid([12,41,13,42])
    for center_z in (13,36):
        parts.extend([
            p.round_part('cylinder',[24,center_z],[21,9],[0,8],solid([16,40,28,46])),
            p.round_part('cylinder',[24,center_z],[18,6],[5,9],carve=True),
            p.round_part('cylinder',[24,center_z],[17.5,5.5],[4,5],solid([11,32,15,36])),
            p.box([31,41],[center_z-3,center_z+2],[5,8],solid([34,32,38,35])),
            p.box([28,31],[center_z-1,center_z+2],[4,44],p.paint([28,1,31,14],[29,5,30,6],[29,5,30,6])),
            dict(shape='profile',along='z',x=[9,28],z=[center_z,center_z+1],y=[12,42],profile=[0,30],paint=cyan),
            dict(shape='profile',along='z',x=[31,44],z=[center_z,center_z+1],y=[12,42],profile=[30,0],paint=cream),
            p.box([9,28],[center_z,center_z+1],[11,12],dark),
            p.box([31,44],[center_z,center_z+1],[11,12],dark)])
        # Source cream diagonal band is geometry-local, so no hull or sea from
        # the overlapping drawing can project onto the thin cyan sail.
        for x in range(9,28):
            top=12+30*(x+.5-9)/19
            lo=max(12,32-.3*(x-9)-2);hi=min(top,32-.3*(x-9)+2)
            if hi>lo:parts.append(p.box([x,x+1],[center_z,center_z+1],[lo,hi],cream))
    return dict(height=48,parts=parts)


def factory():
    parts=[]
    for x0,x1,z0,z1,high,front,top in [
        (2,76,18,130,90,[2,48,76,134],[100,16,108,24]),
        (72,140,2,142,120,[72,42,140,144],[100,16,108,24]),
        (136,222,18,130,90,[136,48,222,134],[100,16,108,24])]:
        parts.append(p.box([x0,x1],[z0,z1],[0,high],p.paint(front,top,[x0+12,front[1],x0+16,front[3]])))
        for xa,xb in [(x0,x0+7),(x1-7,x1)]:
            for za,zb in [(z0,z0+7),(z1-7,z1)]: parts.append(p.box([xa,xb],[za,zb],[0,high],carve=True))
        parts.append(p.box([x0+8,x1-8],[z0+7,z1-7],[high-2,high+1],solid(top)))
    door(parts,96,112,142,22,[96,124,112,144])
    return dict(height=124,parts=parts)


def tower():
    # This source is cropped mid-tower at its north edge. Preserve a continuous
    # truncated roofline instead of inventing four successively lower towers.
    wall=p.paint([16,0,256,216],[82,10,92,20],[54,12,74,192],[54,12,74,192])
    brick=solid([22,12,42,192])
    parts=[p.box([16,256],[24,204],[0,192],wall),
           p.box([96,176],[18,216],[0,192],p.paint([96,0,176,216],[82,10,92,20],[100,8,116,192])),
           p.box([18,46],[24,220],[0,192],brick),p.box([226,254],[24,220],[0,192],brick),
           p.box([16,256],[24,204],[192,196],solid([79,18,88,28])),
           p.box([24,248],[32,196],[193,196],carve=True),
           p.box([46,226],[196,224],[0,22],p.paint([48,216,224,232],[48,208,224,216],[52,218,60,230])),
           p.box([44,228],[194,224],[22,28],p.paint([48,208,224,216],[48,196,224,212],[52,208,60,216]))]
    door(parts,128,144,224,20,[128,218,144,239])
    # The lower facade row is mostly SOLID, not forecourt. Extend its physical
    # plinth/jambs into those exact cells without filling the three entry gaps.
    for x0,x1 in [(0,80),(96,128),(144,176),(192,272)]:
        parts.append(p.box([x0,x1],[204 if x0 in (0,192) else 220,239],[0,22],
                           p.paint([x0,216,x1,224],[x0,216,x1,220],[52,218,60,224])))
    for x0,x1 in [(18,46),(226,254)]:
        parts.append(p.box([x0,x1],[218,239],[0,22],brick))
    return dict(height=200,parts=parts)


def revise(number, original):
    entry=copy.deepcopy(original); parts=entry['parts']
    houses={
        60:(80,34,[32,45,48,64],[[16,42,30,54],[52,42,66,54]],[3,1,77,37],[3,39,77,64]),
        73:(64,32,[16,44,32,64],[[34,41,48,55]],[2,1,62,37],[3,39,61,64]),
        74:(96,30,None,[[16,43,30,55],[48,43,62,55]],[2,1,94,39],[3,41,93,64]),
        87:(64,32,[18,46,32,63],[[34,41,46,51]],[2,1,62,38],[3,40,61,63]),
        88:(80,32,[36,47,50,63],[[18,42,30,52],[54,42,66,52]],[2,1,78,39],[3,40,77,63])}
    if number in houses: entry.update(tile_house(*houses[number],align_windows=number in {60,87,88}))
    elif number==62:
        parts[0]['y']=[0,32]; parts[0]['paint']['top']=[84,50,90,71]
        parts[1]['paint']['front']=[44,64,45,65]
        parts[1]['paint']['side']=[43,62,46,70]
        parts.insert(1,p.box([1,95],[4,71],[32,42],p.paint([2,42,94,46],[2,2,94,43],[84,42,90,46])))
        parts.append(p.box([44,74],[78,79],[28,36],p.paint([44,54,75,61],[44,54,75,55],[44,56,46,60])))
        for x in (44,70):parts.append(p.box([x,x+4],[77,79],[0,28],solid([46,66,48,70])))
        door(parts,52,68,78,28,[52,60,68,79])
    elif number in {67,70,71}:
        body=parts[1 if number==70 else 0]
        masonry={67:[8,50,16,72],70:[8,48,16,120],71:[10,46,16,125]}[number]
        body['paint']['side']=masonry
        body['paint']['back']=masonry
    elif number in {72,84}:
        dome=next(q for q in parts if q['shape']=='dome')
        dome['paint']=solid([18,36,22,40])
        center=dome['center'];rx,rz=dome['radius'];base,top=dome['y']
        for x in (14,30,50,66):
            fraction=math.sqrt(1-((x-center[0])/rx)**2)
            parts.append(dict(shape='barrel',along='x',x=[x-4,x+4],z=[center[1]-rz*fraction,center[1]+rz*fraction],
                y=[base,top if fraction==1 else base+(top-base)*fraction+1],paint=solid([10,30,14,38])))
        parts[0]['paint']=p.paint([7,50,73,70],[10,54,14,64],[10,54,14,64],[10,54,14,64])
        if number==72:
            parts.append(p.round_part('cylinder',[40,34],[12,9],[52,58],p.paint([28,19,52,24],[26,13,54,24],[28,19,32,24])))
        parts.append(p.box([28,52],[69,73],[27,51],p.paint([28,30,52,54],[30,30,50,32],[28,32,30,50])))
        # Badge reaches the vestibule and cannot float above the lower roof.
        parts.append(p.box([30,50],[62,72],[26,29],solid([26,48,28,52])))
        door(parts,32,48,78,24,[32,58,48,79])
    elif number==75:
        parts[0]['x']=[5,75]
        parts[0]['z'][1]=80
        parts[0]['paint']=p.paint([6,56,74,79],[20,65,28,70],[20,65,28,78],[20,65,28,78])
        parts[1]['paint']['top']=[8,12,72,51]
    elif number==76:
        parts[0]['paint']['front']=[2,52,94,78]
    elif number==77:
        parts[0]['y']=[0,42]
        parts[0]['paint']['top']=[52,80,120,112]
        parts.insert(1,dict(shape='gable',ridge='x',x=[0,128],z=[1,110],y=[42,70],paint=p.paint([2,66,126,70],[2,18,126,68],[52,80,60,108])))
        parts[2]['y']=[42,46]
        for x in (16,96): parts.append(p.box([x,x+12],[28,44],[56,68],solid([x,8,x+12,28])))
    elif number in {78,79,80}:
        if number==79: parts.pop(0) # only source paving, not a physical stall platform
        if number in {78,80}:
            for q in parts[:4]: q['y'][1]=34 if number==78 else 32
            for x in (9,67): parts.append(p.box([x,x+4],[4,45],[28,34],solid([x,34,x+3,44])))
        if number in {78,79}:
            containers=[q for q in parts if q['shape']=='cylinder']
            for q in containers:
                if number==79:
                    offset=int(q['center'][0]-24)
                    q['paint']=solid([24+offset,43,26+offset,45])
                    # Rounded belly and shoulder; a separate narrow mouth
                    # avoids the straight barrel silhouette of the first fix.
                    parts.remove(q)
                    for y in range(12):
                        r=4.5+3*math.sin(math.pi*(y+.5)/14)
                        row=int(43-(y+.5)/12*6)
                        paint=p.paint([20+offset,row,28+offset,row+1],
                                      [24+offset,43,26+offset,45],[24+offset,43,26+offset,45])
                        parts.append(p.round_part('cylinder',q['center'],[r,r*.85],[y,y+1],paint))
                    parts.append(p.round_part('cylinder',q['center'],[5.5,4.7],[11,14],solid([23+offset,34,25+offset,35])))
                    parts.append(p.round_part('cylinder',q['center'],[3.5,2.7],[9,15],carve=True))
                    continue
                radius=q['radius']; r=[radius-2,radius-2] if isinstance(radius,(float,int)) else [radius[0]-2,radius[1]-2]
                parts.append(p.round_part('cylinder',q['center'],r,[max(2,q['y'][1]-4),q['y'][1]+1],carve=True))
        if number==80:
            parts[4]['paint']=p.paint([9,27,71,30],[8,27,72,29],[10,27,14,30])
            for x in range(15,65,8): parts.append(p.box([x,x+2],[44,47],[24,26],carve=True))
    elif number==82:
        parts[0]['paint']=solid([14,33,34,37])
    elif number==83:
        entry.update(paired_boats())
    elif number==89:
        # Parallel shallow barrel ribs follow the curved silver roof, each supported by the dome.
        for x in (80,92,104,116,128):
            radius=64*math.sqrt(1-((x-104)/78)**2)
            high=82*math.sqrt(1-((x-104)/78)**2)
            parts.append(dict(shape='barrel',along='x',x=[x-2,x+2],z=[98-radius,98+radius],y=[50,52+high],paint=solid([102,12,106,110])))
    elif number==90: entry.update(factory())
    elif number==91:
        parts[:]=parts[:-2]
        # The long upper source silhouette is a rising tail, projected north
        # over ground. Keep its actual sections on the existing solid footprint.
        # Each layer overlaps its neighbour and the rear body at the root.
        points=[(40,40,74,10),(54,44,74,9),(66,48,74,8),(80,44,73,7),
                (92,34,72,5),(103,24,70,3),(112,18,68,1)]
        for a,b in zip(points,points[1:]):
            for y in range(a[0],b[0]):
                t=(y-a[0])/(b[0]-a[0]);cx,cz,r=[u+(v-u)*t for u,v in zip(a[1:],b[1:])]
                parts.insert(0,p.round_part('cylinder',[cx,cz],[r,max(1,r*.6)],[y,y+1],solid([49,30,52,36])))
        for z,y in [(82,61),(102,61),(126,50)]:
            parts.append(dict(shape='hip',x=[32,48],z=[z-7,z+7],y=[y,y+8],paint=solid([34,62,46,70])))
    elif number in {92,93,94,95,96,100,104}:
        front=44 if number in {95,96} else 60
        offset=16 if number in {95,96} else 0
        parts[0]['paint']['back']=parts[0]['paint']['side']
        door(parts,17,31,front,22,[17,42-offset,31,62-offset])
        if number==96:
            parts[0]['paint']=p.paint([6,24,58,44],[8,30,12,42],[8,30,12,42],[8,30,12,42])
            parts[1]['paint']['top']=[2,1,22,10]
            parts.append(p.box([24,40],[43,47],[31,47],p.paint([24,8,40,24],[24,8,40,9],[24,9,25,24])))
        elif number in {100,104}:
            parts[1]['paint']['top']=[7,10 if number==100 else 2,41,39 if number==100 else 37]
    elif number in {97,101,102}:
        if number==97:
            parts[7]['z']=[58,62];parts[8]['z']=[58,62]
            parts[5]['z']=[19,61]
            parts.append(p.box([42,70],[54,62],[38,45],solid([42,32,70,35])))
        else:
            parts[1]['paint']['top']=[16,20,32,52]
            parts[2]['paint']=solid([50,14,58,20])
            parts.append(p.box([44,68],[74,78],[35,59],p.paint([44,24,68,48],[44,24,68,25],[44,25,45,48])))
            parts.append(p.box([44,68],[62,78],[28,36],solid([42,50,70,54])))
        if number!=97: door(parts,48,64,76,22,[48,58,64,78])
        if number==101:
            parts[0]['paint']['side']=[10,59,14,72]
            parts[0]['paint']['back']=[10,59,14,72]
    elif number==99: entry.update(tower())
    elif number==103:
        # Front hall spans x40..200. Open the gaps while retaining eight support piers.
        for x0,x1 in [(44,60),(68,84),(92,104),(136,148),(156,172),(180,196)]:
            parts.append(p.box([x0,x1],[146,155],[0,24],carve=True))
        parts.append(dict(shape='barrel',along='z',x=[112,128],z=[150,157],y=[20,28],carve=True))
        parts.append(p.box([80,160],[16,42],[0,8],
                           p.paint([60,42,180,52],[60,54,180,58],[17,60,20,100])))
    elif number==105:
        for x in (48,72,128,152):
            parts.append(p.box([x,x+6],[106,112],[0,46],solid([40,76,44,106])))
        door(parts,92,116,108,22,[92,88,116,111])
    elif number==107:
        parts[3]['y'][1]=76
        for x in (7,39,135,167):
            parts.append(p.box([x-2,x+2],[78,84],[0,43],solid([6,52,10,78])))
        door(parts,82,94,108,26,[82,84,94,110])
    elif number==109:
        parts[0]['y']=[18,40]
        parts[0]['paint']['front']=[1,46,160,64]
        # Preserve the short source windows. The lower facade is plain siding,
        # rather than stretching the cropped window band over the full wall.
        for x in range(1,160,16):
            width=min(16,160-x)
            parts.insert(0,p.box([x,x+width],[16,76],[0,18],solid([72,48,72+width,66])))
        door(parts,33,50,76,18,[33,66,50,79])
    elif number==110:
        wall=solid([34,40,40,48])
        entry['parts']=[p.box([4,76],[6,92],[0,168],wall)]
        parts=entry['parts']
        for x in (4,72): parts.append(p.box([x,x+4],[6,94],[0,168],solid([3,12,5,20])))
        parts.append(p.box([4,76],[90,94],[0,12],p.paint([4,80,76,92],[8,84,28,88],[8,84,12,90])))
        for y in (42,88,134):
            for x in (18,50):
                parts.append(p.box([x,x+12],[88,94],[y,y+8],carve=True))
                parts.append(p.box([x,x+12],[88,89],[y,y+8],solid([18,34,29,38])))
        parts.extend([p.box([32,48],[66,96],[0,18],carve=True),
            dict(shape='barrel',along='z',x=[32,48],z=[66,96],y=[18,26],carve=True),
            p.box([32,48],[66,67],[0,18],solid([35,84,45,89])),
            dict(shape='barrel',along='z',x=[32,48],z=[66,67],y=[18,26],paint=solid([35,84,45,89])),
            p.box([4,76],[6,92],[166,170],wall)])
        entry['height']=172
    remove=review.OVERHEAD_TERRAIN_REMOVALS.get(entry['id'],set())
    if remove: entry['terrain']=[cell for cell in entry['terrain'] if tuple(cell) not in remove]
    return entry


def assigned(table):
    return [b for b in table['buildings'] if int(b['id'][9:12])>=59 and int(b['id'][9:12]) not in EXCLUDED]


def generate(table):
    return {b['id']:revise(int(b['id'][9:12]),b) for b in assigned(table)}


def preview(entry,drawing,out,scale):
    """Use the game helper; scale 2 downsamples geometry only for large authoring studies.

    Source paint coordinates are unchanged. Minecraft uses the full original spec.
    Thin features may disappear in this explicitly labelled working preview.
    """
    import numpy as np
    from tools.shape_fit.emerald import parts_model
    working=copy.deepcopy(entry)
    if scale>1:
        working['height']=math.ceil(working['height']/scale)
        for part in working['parts']:
            for key in ('x','y','z','center','profile','cross'):
                if key in part: part[key]=[v/scale for v in part[key]]
            if 'radius' in part:
                r=part['radius'];part['radius']=[v/scale for v in r] if isinstance(r,list) else r/scale
    occ,colors=parts_model.build(working,np.asarray(drawing),terrain_cell_px=16//scale)
    occ=occ[:,:math.ceil(drawing.height/scale),:math.ceil(drawing.width/scale)]
    colors=colors[:,:occ.shape[1],:occ.shape[2]]
    parts_model.picture(occ,colors,out/'view.png',3)
    parts_model.front_view(occ,colors,out/'front.png',3)
    return int(occ.sum())


def contacts(out):
    from PIL import Image,ImageDraw
    folders=sorted(d for d in out.glob('emerald_b*') if (d/'view.png').exists())
    for page in range(math.ceil(len(folders)/8)):
        canvas=Image.new('RGB',(1600,1320),(35,39,45));pen=ImageDraw.Draw(canvas)
        for i,folder in enumerate(folders[page*8:page*8+8]):
            x,y=i%2*800,i//2*330
            pen.text((x+8,y+5),folder.name.removeprefix('emerald_'),fill='white')
            for filename,left,width in [('drawing.png',0,200),('front.png',200,230),('view.png',430,370)]:
                image=Image.open(folder/filename).convert('RGB')
                image.thumbnail((width-8,292),Image.Resampling.NEAREST)
                canvas.paste(image,(x+left+(width-image.width)//2,y+28+(292-image.height)//2))
        canvas.save(out/f'review-contact-{page}.png')


def main():
    from PIL import Image,ImageDraw
    from tools import model_gallery
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline',type=Path,default=batch.BASELINE)
    ap.add_argument('--bundle',type=Path,default=batch.BUNDLE)
    ap.add_argument('--out',type=Path,default=OUT)
    ap.add_argument('--previews',action='store_true')
    ap.add_argument('--only',type=int,action='append')
    args=ap.parse_args();args.out=args.out.resolve();raw=args.baseline.read_bytes();table=json.loads(raw)
    originals={b['id']:b for b in assigned(table)}
    generated=generate(table);art=model_gallery.EmeraldArt(args.bundle)
    catalog={e['id']:e for e in model_gallery.building_catalog(table,art)}
    patch={};ledger=[]
    for name,entry in generated.items():
        number=int(name[9:12])
        if args.only and number not in args.only: continue
        label,family,reason=FINDINGS[number];old=originals[name]
        if entry!=old:
            review.validate_building(name,old,entry);patch[name]=entry
        target=args.out/name;target.mkdir(parents=True,exist_ok=True)
        drawing=p.drawing(entry,args.bundle);drawing.save(target/'drawing.png')
        drawing.resize((drawing.width*3,drawing.height*3),Image.Resampling.NEAREST).save(target/'source.png')
        (target/'spec.json').write_text(json.dumps(entry,indent=2)+'\n')
        occurrence=catalog[name]['occurrences']
        place=occurrence[0] if occurrence else {'map':'EM_SKY_PILLAR_OUTSIDE','x':12,'y':0,'matching_cells':28,'total_cells':30,'match_kind':'near-match; two door cells differ'}
        source_map=art.map_image(place['map']).convert('RGB');x,y=place['x']*16,place['y']*16
        bounds=(max(0,x-24),max(0,y-24),min(source_map.width,x+drawing.width+24),min(source_map.height,y+drawing.height+24))
        crop=source_map.crop(bounds);pen=ImageDraw.Draw(crop)
        pen.rectangle((x-bounds[0],y-bounds[1],x-bounds[0]+drawing.width-1,y-bounds[1]+drawing.height-1),outline='red')
        crop.save(target/'context.png')
        evidence=[{'drawing':str((target/'drawing.png').relative_to(ROOT)),'source_pixels_sha256':hashlib.sha256(drawing.tobytes()).hexdigest(),'size':list(drawing.size)},
                  {'map_occurrences':occurrence,'inspected_context':place,'context':str((target/'context.png').relative_to(ROOT))},
                  {'baseline_parts':len(old['parts']),'current_parts':len(entry['parts']),'source_inspected':True,'geometry_finding':reason}]
        if args.previews:
            # Full resolution for small models; the huge tower/dome only need a coarse working view before runtime.
            factor=2 if drawing.width*drawing.height*entry['height']>1500000 else 1
            voxels=preview(entry,drawing,target,factor)
            preview_record={'entry_sha256':review.entry_hash(entry),'geometry_downsample':factor,'preview_occupied_voxels':voxels}
            (target/'preview.json').write_text(json.dumps(preview_record,indent=2)+'\n')
        if (target/'preview.json').exists():
            preview_record=json.loads((target/'preview.json').read_text())
            if preview_record['entry_sha256']==review.entry_hash(entry):
                evidence.append({'working_preview':str((target/'view.png').relative_to(ROOT)),**preview_record})
        ledger.append(dict(key=name,kind='building',label=label,family=family,decision='changed' if name in patch else 'retained',reason=reason,
                           baseline_entry_sha256=review.entry_hash(old),evidence=evidence,visual_status='source-and-geometry-reviewed; Minecraft comparison pending'))
        print(name,ledger[-1]['decision'],flush=True)
    args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'updates.json').write_text(json.dumps({'cells':{},'buildings':patch},indent=2)+'\n')
    (args.out/'ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
    (args.out/'provenance.json').write_text(json.dumps({'baseline_table_sha256':hashlib.sha256(raw).hexdigest(),'generator':str(Path(__file__).relative_to(ROOT)),
        'scope':sorted(FINDINGS),'counts':{'changed':len(patch),'retained':len(ledger)-len(patch)},'status':'candidate; Minecraft visual review pending'},indent=2)+'\n')
    if args.previews: contacts(args.out)


if __name__=='__main__': main()
