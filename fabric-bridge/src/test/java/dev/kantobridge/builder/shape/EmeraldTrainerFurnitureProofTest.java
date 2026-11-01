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
import org.junit.jupiter.api.function.Executable;

/** Source-exact reception furniture, including genuine monitor stacks and native counter collision. */
class EmeraldTrainerFurnitureProofTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}

    @Test void receptionFurnitureUsesActualSourceRoutesAndKeepsCounterAndWalkGameplay()throws Exception{
        Path folder=STUDY.resolve("trainer-hill-furniture");var source=EmeraldSourceStackFollowupsTest.source();
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(EmeraldTerrainInterfaceProofTest.LIVE),current=live.deepCopy();
        JsonObject patch=read(folder.resolve("updates.json")).getAsJsonObject("cells");for(var e:patch.entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);
        SceneMap map=source.scene().map("EM_TRAINER_HILL_ENTRANCE").withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get("EM_TRAINER_HILL_ENTRANCE"));
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var objects=List.copyOf(map.objects());var sites=List.copyOf(map.swapSites());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
        assertTrue(ShapeHeights.headroom(map)>=4);JsonArray audit=new JsonArray(),rows=new JsonArray();List<Executable> checks=new ArrayList<>();Set<String> found=new HashSet<>();int counters=0,monitorTops=0;
        for(int y=0;y<map.h();y++)for(int x=0;x<map.w();x++){
            String key=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,y*2),map.tile(x*2+1,y*2),map.tile(x*2,y*2+1),map.tile(x*2+1,y*2+1)});
            boolean edited=patch.has(key);JsonObject entry=edited?patch.getAsJsonObject(key):null;
            boolean top=edited&&entry.has("on"),counter=edited&&entry.has("counter");if(counter)counters++;if(top&&x>=4&&x<=5&&y==7)monitorTops++;
            JsonObject record=new JsonObject();record.addProperty("key",key);record.addProperty("x",x);record.addProperty("y",y);record.addProperty("kind",map.kind(x,y).name());JsonArray columns=new JsonArray();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int i=index(map,x,y,dx,dz);JsonObject q=new JsonObject();q.addProperty("before_blocks",unchanged.blocks()[i]);q.addProperty("after_blocks",after.blocks()[i]);q.addProperty("before_decor",unchanged.decor()[i]);q.addProperty("after_decor",after.decor()[i]);q.addProperty("before_shapes",Arrays.toString(unchanged.open()[i]));q.addProperty("after_shapes",Arrays.toString(after.open()[i]));columns.add(q);
                if(!edited){checks.add(()->EmeraldTerrainInterfaceProofTest.equalColumn(unchanged,after,i));continue;}
                if(counter){assertEquals(CellKind.COUNTER,map.kind(x,y));checks.add(()->{assertEquals(1,after.blocks()[i]);assertEquals(2,after.decor()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());});}
                else if(map.kind(x,y)==CellKind.WALK){checks.add(()->{assertEquals(0,after.blocks()[i]);assertEquals(0,after.decor()[i]);assertNull(after.open()[i]);});}
                else if(top){checks.add(()->{assertEquals(0,after.blocks()[i]);assertNull(after.open()[i]);});}
                else{checks.add(()->{assertNotNull(after.open()[i]);assertTrue(after.blocks()[i]>=2);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());});}
            }
            if(edited){found.add(key);record.add("columns",columns);audit.add(record);}
        }
        for(int x=4;x<=5;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,8,dx,dz);checks.add(()->{assertEquals(4,after.blocks()[i]);assertEquals(4,after.open()[i].length);});}
        for(JsonElement v:JsonParser.parseString(Files.readString(folder.resolve("assembly-requests.json"))).getAsJsonArray()){
            JsonObject spec=v.getAsJsonObject();int x=spec.get("x").getAsInt(),y=spec.get("y").getAsInt(),w=spec.get("w").getAsInt(),d=spec.get("d").getAsInt();
            JsonObject row=rectangle(map,x,y,w,d,before,after);assertEquals(spec.get("layout"),row.get("layout"));row.addProperty("id",spec.get("id").getAsString());row.add("source_floor_columns",sourceFloors(map,x,y,w,d,before,after,null,null));rows.add(row);
        }
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("headroom",ShapeHeights.headroom(map));proof.add("occurrences",rows);proof.add("source_routes",audit);
        EmeraldSourceStackFollowupsTest.save("trainer-hill-furniture/runtime-diagnostic.json",proof);assertAll(checks);
        assertEquals(patch.keySet(),found);assertEquals(33,found.size());assertEquals(3,counters);assertEquals(2,monitorTops);assertEquals(9,rows.size());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(objects,map.objects());assertEquals(sites,map.swapSites());
        EmeraldSourceStackFollowupsTest.save("trainer-hill-furniture/runtime-proof.json",proof);
    }
}
