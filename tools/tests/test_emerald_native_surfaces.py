import base64
import copy
import hashlib
import unittest
from pathlib import Path
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import native_surfaces as subject


def fixture(water=False):
    collision=41 if water else 0;raw=bytes([1,0,collision,3])
    source={'id':'EM_FIXTURE','w':1,'h':1,'trueW':1,'trueH':1,'cells':base64.b64encode(raw).decode()}
    layout=[['g3q/1-2-3-4']];columns=[]
    for z in range(2):
        for x in range(2):
            tile=z*2+x+1;block={'y':-1,'code_kind':2 if water else 1,'code_value':tile,'tile':tile,'slot_shape':'HALF' if water else 'FULL','visible':True}
            columns.append({'x':x,'z':z,'feet':0,'source_tile':tile,'kind':'WATER' if water else 'WALK',
                'model_kind':'WATER' if water else 'GROUND','raw_collision':collision,'built_collision':collision,
                'source_elevation':3,'source_tags':0,'before_effective_floor':tile,'after_effective_floor':tile,
                'before':[block],'after':[copy.deepcopy(block)]})
    row={'map':'EM_FIXTURE','x':0,'y':0,'width':1,'depth':1,'layout':layout,'source_collisions':[[collision]],
        'map_cells_sha256':hashlib.sha256(raw).hexdigest(),'surface_evidence_mode':'worldplan-environment-v1','source_surface_columns':columns}
    kwargs={'source':source,'raw':raw,'grid':layout,'revision':'before','table':{'game':'emerald','cells':{}},'model_hash':gallery.model_hash}
    return row,kwargs


class NativeSurfaceTests(unittest.TestCase):
    def test_full_floor_and_half_water_preserve_absolute_floor_datum(self):
        for water in (False,True):
            row,kw=fixture(water);result=subject.consume(row,**kw)
            self.assertEqual([-1,0],result['y_range']);self.assertEqual([2,2],result['bounds_xz'])
            self.assertEqual(4,len(result['fragments']));self.assertFalse(result['audit'])
            self.assertEqual('slab:0|1|8' if water else 'cube:0|1,1,1,1,1,1',result['fragments'][0]['key'])

    def test_missing_duplicate_and_stale_source_are_refused(self):
        for mutate in (lambda r:r['source_surface_columns'].pop(),
                       lambda r:r['source_surface_columns'].__setitem__(1,copy.deepcopy(r['source_surface_columns'][0])),
                       lambda r:r.__setitem__('map_cells_sha256','0'*64),
                       lambda r:r['source_surface_columns'][0].__setitem__('source_tile',999),
                       lambda r:r['source_surface_columns'][0].__setitem__('feet',3),
                       lambda r:r['source_surface_columns'][0].__setitem__('built_collision',7)):
            row,kw=fixture();mutate(row)
            with self.assertRaises(ValueError):subject.consume(row,**kw)

    def test_story_and_barrier_are_audit_only_and_cannot_become_meshes(self):
        row,kw=fixture();column=row['source_surface_columns'][0]
        column['before'] += [{'y':0,'code_kind':5,'code_value':0,'story_key':'EM_FIXTURE:0:0','visible':False},
                             {'y':1,'code_kind':9,'code_value':20,'visible':False}]
        result=subject.consume(row,**kw);self.assertEqual(2,len(result['audit']));self.assertEqual(4,len(result['fragments']))
        column['before'][1]['visible']=True
        with self.assertRaisesRegex(ValueError,'visibility'):subject.consume(row,**kw)
        column['before'][1]['visible']=False;column['before'][2]['code_value']=0
        with self.assertRaisesRegex(ValueError,'invisible'):subject.consume(row,**kw)

    def test_unsupported_shaped_carrier_and_half_mapping_are_refused(self):
        row,kw=fixture();bad=copy.deepcopy(row['source_surface_columns'][0]['before'][0]);bad.update(y=0,code_kind=7);row['source_surface_columns'][0]['before'].append(bad)
        with self.assertRaisesRegex(ValueError,'explicit geometry'):subject.consume(row,**kw)
        row,kw=fixture(True);row['source_surface_columns'][0]['before'][0]['tile']=2
        with self.assertRaisesRegex(ValueError,'HALF'):subject.consume(row,**kw)
        row,kw=fixture();row['source_surface_columns'][0]['before'][0]['tile']=999
        with self.assertRaisesRegex(ValueError,'absent from source'):subject.consume(row,**kw)

    def test_actual_step_requires_explicit_measured_shape(self):
        row,kw=fixture();row['layout']=kw['grid']=[['g3q/1-1-1-1']]
        row['actual_map_layout']={'w':1,'h':1,'pieces':[{'x':0,'y':0,'w':1,'h':1,'dx':0,'dy':0,'dz':0}],
            'pieceOf':[0],'levels':[0],'ramps':{'0':{'x':0,'y':0,'dir':'UP','rise':3}},'decks':{}}
        for q in row['source_surface_columns']:
            q['source_tile']=q['before_effective_floor']=q['after_effective_floor']=1;q['ramp']=row['actual_map_layout']['ramps']['0']
            q['before'][0].update(tile=1,code_value=1)
            by=2 if q['z']==0 else 0
            q['before'].append({'y':by,'code_kind':12,'code_value':9 if q['z']==0 else 5,'visible':True,'palette':'BAKED',
                'shape_key':f"cell:0|1,1,1,1|1|{q['x']},{by},{q['z']}|v=g0_stair_n_k24"})
        result=subject.consume(row,**kw);self.assertEqual(8,len(result['fragments']))
        row['source_surface_columns'][0]['before'][1]['code_value']=1
        with self.assertRaisesRegex(ValueError,'STEP'):subject.consume(row,**kw)

    def test_template_has_no_fallback_without_exact_caller_validation(self):
        row,kw=fixture();row['source_surface_columns'][0]['before'].append({'y':0,'code_kind':7,
            'code_value':1,'tile':1,'slot_shape':'FULL','visible':True,
            'shape_key':'tmpl:0|0123456789abcdef|0,0,0','palette':'BAKED'})
        with self.assertRaisesRegex(ValueError,'payload validator'):subject.consume(row,**kw)
        result=subject.consume(row,**kw,template_validator=lambda shape,q,b:shape)
        self.assertEqual('tmpl:0|0123456789abcdef|0,0,0',result['fragments'][1]['key'])

    def test_truncated_raised_floor_is_rejected(self):
        row,kw=fixture();row['source_surface_columns'][0]['before']=[]
        with self.assertRaisesRegex(ValueError,'floor'):subject.consume(row,**kw)
        row,kw=fixture();row['actual_map_layout']={'w':1,'h':1,'pieces':[{'x':0,'y':0,'w':1,'h':1,'dx':0,'dy':0,'dz':0}],
            'pieceOf':[0],'levels':[36],'ramps':{},'decks':{}}
        for q in row['source_surface_columns']:
            q['feet']=36;q['before'][0]['y']=35
        result=subject.consume(row,**kw);self.assertEqual([35,36],result['y_range'])
        row['source_surface_columns'][0]['before'][0]['y']=31
        with self.assertRaisesRegex(ValueError,'floor'):subject.consume(row,**kw)

    def test_actual_shoreline_preserves_all_visible_surfaces_and_invisible_audits(self):
        folder=gallery.ROOT/'local/model-gallery/emerald-pass-study/shore-bank-repairs';path=folder/'surface-runtime-proof.json'
        if not path.exists():self.skipTest('requires authorized local source proof')
        art=gallery.EmeraldArt(gallery.ROOT/'local/minecraft/kantobridge/export/emerald')
        for row in gallery.read_json(path)['occurrences']:
            source=art.maps[row['map']]
            for revision,snapshot in [('before','fitted-surface-baseline.json'),('after','fitted-surface-current.json')]:
                result=subject.consume(row,source=source,raw=base64.b64decode(source['cells']),grid=art.map_cells(source),
                    revision=revision,table=gallery.read_json(folder/snapshot),model_hash=gallery.model_hash)
                expected=sum(b['visible'] for q in row['source_surface_columns'] for b in q[revision])
                self.assertEqual(expected,len(result['fragments']))
                self.assertEqual(sum(not b['visible'] for q in row['source_surface_columns'] for b in q[revision]),len(result['audit']))
                self.assertEqual(-1,result['y_range'][0])


if __name__=='__main__':unittest.main()
