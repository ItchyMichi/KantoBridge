import base64
import hashlib

import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald_pass.review import SOURCE_AMENDMENTS
from tools.shape_fit.emerald_pass.scenery import BUNDLE

COUNTER_KEYS={
    'g3q/7989-7990-7991-7992','g3q/8318-8319-8239-8320','g3q/8319-8319-8149-8149',
    'g3q/8319-8321-8322-8323','g3q/8319-8319-8330-8320',
    'g3q/7740-7740-7764-7764','g3q/7741-7743-7741-7743',
    'g3q/7740-7788-7764-7764','g3q/7788-7789-7764-7764',
    'g3q/3545-3546-3547-3548','g3q/3553-3554-3555-3556','g3q/3569-3570-3571-3571',
    'g3q/7377-7377-7379-7379','g3q/7377-7380-7379-7381','g3q/7376-7377-7382-7379',
    'g3q/8382-8383-8384-8383','g3q/8393-8393-8394-8394','g3q/8395-8396-8394-8394',
    'g3q/7112-7113-7112-7114','g3q/7118-7119-7120-7119',
    'g3q/7125-7126-7127-7127','g3q/7122-7122-7127-7127',
    'g3q/8739-8740-8741-8742','g3q/8759-8760-8761-8762','g3q/8759-8760-8759-8760',
    'g3q/9963-9964-9962-9962','g3q/10048-10049-10050-10051',
}
STACK_FIT_KEYS={'g3q/10875-10876-10877-10878','g3q/10891-10892-10893-10894','g3q/11285-11286-10757-10758','g3q/15876-15877-15878-15879'}
STACK_FIT_TARGETS={'g3q/10913-10914-17555-17556','g3q/10913-10914-17563-17564','g3q/10913-10914-17582-17583'}


def test_pinned_aliases_match_source_neighbors_and_added_pixels():
    if not BUNDLE.exists():
        pytest.skip('local Emerald source export not installed')
    art=EmeraldArt(BUNDLE)
    for key,pin in SOURCE_AMENDMENTS['stacks'].items():
        allowed={pin['after']['on'],*pin['after'].get('alsoOn',[])}
        witnessed=set()
        for loc in pin['witnesses']:
            scene=art.maps[loc['map']];grid=art.map_cells(scene);x,y=loc['x'],loc['y']
            assert grid[y][x]==key
            assert grid[y+1][x]==loc['below_key'] in allowed
            assert base64.b64decode(scene['cells'])[((y+1)*scene['w']+x)*4+2]==loc['below_collision']
            witnessed.add(loc['below_key'])
        assert set(pin['after'].get('alsoOn',[])) <= witnessed
    for key,pin in SOURCE_AMENDMENTS['added_cells'].items():
        assert hashlib.sha256(art.cell(key,'BAKED').convert(pin['source_pixel_mode']).tobytes()).hexdigest()==pin['source_pixel_sha256']
        assert sorted((p['map'],p['x'],p['y']) for p in art.locations[key])==sorted(tuple(p) for p in pin['occurrences'])
    for key,pin in SOURCE_AMENDMENTS.get('metadata',{}).items():
        allowed_fields={'class','walk','on','alsoOn','seat'}
        if 'counter' in pin['after']:
            assert key in COUNTER_KEYS
            assert pin['before']=={'class':'voxels'}
            assert pin['after']=={'class':'voxels','counter':True}
            assert all(loc['collision']==144 for loc in pin['witnesses'])
            allowed_fields.add('counter')
        if 'stackFit' in pin['after']:
            assert key in STACK_FIT_KEYS and pin['after']['stackFit'] is True
            assert pin['after']['on']==pin['before']['on']
            assert set(pin['before'].get('alsoOn',[])) <= set(pin['after']['alsoOn'])
            targets={'g3q/15880-15881-15882-15883'} if key=='g3q/15876-15877-15878-15879' else STACK_FIT_TARGETS
            assert targets <= set(pin['after']['alsoOn'])
            witnessed={loc['below_key'] for loc in pin['witnesses'] if loc.get('below_key') in targets}
            assert witnessed==targets
            allowed_fields.add('stackFit')
        assert set(pin['after']) <= allowed_fields
        assert hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest()==pin['source_pixel_sha256']
        assert sorted((p['map'],p['x'],p['y']) for p in art.locations[key])==sorted((p['map'],p['x'],p['y']) for p in pin['witnesses'])
        for loc in pin['witnesses']:
            scene=art.maps[loc['map']];x,y=loc['x'],loc['y']
            assert art.map_cells(scene)[y][x]==key
            assert base64.b64decode(scene['cells'])[(y*scene['w']+x)*4+2]==loc['collision']
