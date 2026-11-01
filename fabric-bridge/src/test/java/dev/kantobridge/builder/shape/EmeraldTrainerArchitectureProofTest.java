package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import com.google.gson.*;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact control-wall, portal and lift patterns; source WARP cells remain passable. */
class EmeraldTrainerArchitectureProofTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static JsonArray templates(ShapePlanner.MapShapes plan,int x,int y,int w,int d){
        JsonArray result=new JsonArray();for(var t:plan.templates())if(t.x0()<2*(x+w)&&t.y0()<2*(y+d)&&t.x0()+t.entry().get("w").getAsInt()>2*x&&t.y0()+t.entry().get("d").getAsInt()>2*y){
            JsonObject value=new JsonObject();value.addProperty("sig",t.sig());value.addProperty("x0",t.x0());value.addProperty("y0",t.y0());value.add("entry",t.entry());result.add(value);
        }return result;
    }

    @Test void eightExactPatternsKeepAllFourteenSourcePortalsAndUnrelatedColumns()throws Exception{
        Path folder=STUDY.resolve("trainer-hill-architecture");var source=EmeraldSourceStackFollowupsTest.source();var rooms=Headroom.rooms(source.scene(),source.probe(),9);
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));var live=read(EmeraldTerrainInterfaceProofTest.LIVE);var patch=read(folder.resolve("updates.json"));var current=EmeraldTerrainInterfaceProofTest.overlay(live,patch);
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);
        Map<String,SceneMap> maps=new HashMap<>();Map<String,ShapePlanner.MapShapes> old=new HashMap<>(),prior=new HashMap<>(),now=new HashMap<>();Map<String,Set<Integer>> claimed=new HashMap<>();JsonArray rows=new JsonArray();int warps=0;
        for(var value:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            var group=value.getAsJsonObject();String id=group.get("id").getAsString();int d=group.getAsJsonArray("layout").size(),w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size();int height=patch.getAsJsonObject("buildings").getAsJsonObject(id).get("height").getAsInt()/8;
            for(var place:group.getAsJsonArray("occurrences")){
                var at=place.getAsJsonObject();String name=at.get("map").getAsString();int x=at.get("x").getAsInt(),y=at.get("y").getAsInt();SceneMap map=maps.computeIfAbsent(name,n->source.scene().map(n).withHeadroom(rooms.get(n)));
                byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var objects=List.copyOf(map.objects());var sites=List.copyOf(map.swapSites());
                var before=old.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true));var reference=prior.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,live,true));var after=now.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true));
                assertTrue(ShapeHeights.headroom(map)>=height,id+" actual room height");var matches=after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).toList();assertEquals(1,matches.size(),id+" at "+name);assertEquals(w*2,matches.getFirst().entry().get("w").getAsInt());assertEquals(d*2,matches.getFirst().entry().get("d").getAsInt());
                for(int z=0;z<d;z++)for(int xx=0;xx<w;xx++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x+xx,y+z,dx,dz);claimed.computeIfAbsent(name,n->new HashSet<>()).add(i);assertNotNull(after.open()[i]);assertEquals(ShapeKey.Kind.TMPL,after.open()[i][0].kind());
                    if(map.kind(x+xx,y+z)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals((1<<height)-1,after.decor()[i]);if(dx==0&&dz==0)warps++;}
                    else{assertEquals(CellKind.SOLID,map.kind(x+xx,y+z));assertEquals(height,after.blocks()[i]);}
                }
                var row=rectangle(map,x,y,w,d,before,after);assertEquals(group.get("layout"),row.get("layout"));row.addProperty("id",id);row.add("source_floor_columns",sourceFloors(map,x,y,w,d,before,after,null,null));row.add("current_templates",templates(after,x,y,w,d));rows.add(row);
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(objects,map.objects());assertEquals(sites,map.swapSites());
            }
        }
        Set<String> edited=patch.getAsJsonObject("cells").keySet();int unrelated=0;
        for(var entry:maps.entrySet()){
            var map=entry.getValue();var a=prior.get(entry.getKey());var b=now.get(entry.getKey());for(int z=0;z<map.h();z++)for(int x=0;x<map.w();x++){
                String key=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,z*2),map.tile(x*2+1,z*2),map.tile(x*2,z*2+1),map.tile(x*2+1,z*2+1)});
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,z,dx,dz);if(!edited.contains(key)&&!claimed.get(entry.getKey()).contains(i)){EmeraldTerrainInterfaceProofTest.equalColumn(a,b,i);unrelated++;}}
            }
        }
        var map=maps.get("EM_TRAINER_HILL_ENTRANCE");var context=rectangle(map,10,4,9,7,old.get(map.id()),now.get(map.id()));context.addProperty("id","emerald_trainer_hill_furniture_east_counter");context.add("source_floor_columns",sourceFloors(map,10,4,9,7,old.get(map.id()),now.get(map.id()),null,null));context.add("current_templates",templates(now.get(map.id()),10,4,9,7));JsonArray contexts=new JsonArray();contexts.add(context);
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("loaded_live_sha256",sha(Files.readAllBytes(EmeraldTerrainInterfaceProofTest.LIVE)));proof.addProperty("source_warp_cells",warps);proof.addProperty("unrelated_quarters_checked",unrelated);proof.add("occurrences",rows);
        EmeraldSourceStackFollowupsTest.save("trainer-hill-architecture/runtime-diagnostic.json",proof);assertEquals(14,rows.size());assertEquals(9,warps);assertEquals(8,patch.getAsJsonObject("buildings").size());
        EmeraldSourceStackFollowupsTest.save("trainer-hill-architecture/runtime-proof.json",proof);proof.add("occurrences",contexts);EmeraldSourceStackFollowupsTest.save("trainer-hill-architecture/context-runtime-proof.json",proof);
    }
}
