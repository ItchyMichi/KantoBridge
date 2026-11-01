"""Verify the later bounded gallery adapter against the frozen raised Fortree package."""
from __future__ import annotations
import hashlib,importlib.util,json,sys
from tools import model_gallery as current_gallery
from tools.shape_fit.emerald_pass import review

ROOT=review.ROOT
FOLDER=ROOT/'local/model-gallery/emerald-pass-study/fortree-raised-carriers'

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def canonical(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def module(path,name):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

def main():
    manifest=review.read(FOLDER/'integration-manifest.json')
    for path,expected in manifest['files'].items():
        if digest(FOLDER/path)!=expected:raise ValueError('Frozen package drift: '+path)
    old_gallery=module(FOLDER/'frozen-code/model_gallery.py','frozen_fortree_gallery');old_gallery.ROOT=ROOT
    old_surface=module(FOLDER/'frozen-code/native_surfaces.py','frozen_fortree_surfaces')
    entities=review.read(FOLDER/'native-entities.json');ids={e['id'] for e in entities};table=review.read(FOLDER/'fitted-surface-current.json');baseline=review.read(review.BASELINE)
    art=current_gallery.EmeraldArt(ROOT/'local/minecraft/kantobridge/export/emerald');records=[]
    import tools.shape_fit.emerald_pass.native_surfaces as current_surface
    for label,t,definitions in [('current',table,entities),('baseline',baseline,current_gallery.baseline_entities(entities,digest(review.BASELINE)))]:
        try:
            sys.modules['tools.shape_fit.emerald_pass.native_surfaces']=old_surface
            prior={e['id']:e for e in old_gallery.catalog(t,definitions,art) if e['id'] in ids}
        finally:sys.modules['tools.shape_fit.emerald_pass.native_surfaces']=current_surface
        after={e['id']:e for e in current_gallery.catalog(t,definitions,art) if e['id'] in ids}
        if prior!=after or set(after)!=ids:raise ValueError('Frozen Fortree native catalog changed')
        records.append({'revision':label,'catalog_sha256':canonical(after),'byte_equivalent':True,'entries':len(after)})
    code=[]
    for path,expected in manifest['code_sources'].items():
        now=digest(ROOT/path)
        if now!=expected and path!='tools/model_gallery.py':raise ValueError('Unexpected frozen runtime code drift: '+path)
        code.append({'path':path,'frozen_sha256':expected,'current_sha256':now})
    paths=['tools/model_gallery.py','tools/tests/test_model_gallery_native_fragments.py','tools/tests/test_model_gallery_native_surfaces.py','fabric-bridge/src/main/java/dev/kantobridge/dev/GalleryGeometry.java','fabric-bridge/src/test/java/dev/kantobridge/dev/GalleryGeometryTest.java','local/model-gallery/emerald-pass-study/gallery-height-tests.xml','local/model-gallery/emerald-pass-study/gallery-height-single-final-tests.xml']
    result={'frozen_integration_manifest_sha256':digest(FOLDER/'integration-manifest.json'),'reason':'Validated native1×1 companions now survive ordinary single-cell dedup; actual source-surface contexts may reach64high within the previous163840placement-volume budget. Footprint/pad/32-chunk camera limits remain; source/proof validation unchanged.','compatibility_catalogs':records,'frozen_code_comparison':code,'validation':{'python_tests':69,'java_geometry_tests':7,'client_compiled':True},'files':{p:digest(ROOT/p) for p in paths},'minecraft_visual_approval':False}
    out=FOLDER.parent/'fortree-raised-compatibility.json';current_gallery.write_json(out,result);print(out.relative_to(ROOT).as_posix(),digest(out))

if __name__=='__main__':main()
