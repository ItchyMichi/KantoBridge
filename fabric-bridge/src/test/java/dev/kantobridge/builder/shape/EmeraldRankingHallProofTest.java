package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import static dev.kantobridge.builder.shape.EmeraldTrainerArchitectureProofTest.templates;
import com.google.gson.*;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact Ranking Hall source placement; chair aliases and case preserve native gameplay. */
class EmeraldRankingHallProofTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void chairsTerminalsAndCaseKeepNativeFloorsAndAllUnrelatedColumns()throws Exception{
        Path folder=STUDY.resolve("frontier-ranking");var source=EmeraldSourceStackFollowupsTest.source();
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));var live=read(EmeraldTerrainInterfaceProofTest.LIVE);var patch=read(folder.resolve("updates.json"));var current=EmeraldTerrainInterfaceProofTest.overlay(live,patch);
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);
        var map=source.scene().map("EM_BATTLE_FRONTIER_RANKING_HALL").withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get("EM_BATTLE_FRONTIER_RANKING_HALL"));
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var objects=List.copyOf(map.objects());var sites=List.copyOf(map.swapSites());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var prior=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
        Set<String> edited=patch.getAsJsonObject("cells").keySet(),found=new HashSet<>();JsonArray rows=new JsonArray(),routes=new JsonArray();List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();int unrelated=0;
        for(int y=0;y<map.h();y++)for(int x=0;x<map.w();x++){
            String key=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,y*2),map.tile(x*2+1,y*2),map.tile(x*2,y*2+1),map.tile(x*2+1,y*2+1)});boolean changed=edited.contains(key);if(changed)found.add(key);
            JsonObject route=new JsonObject();route.addProperty("key",key);route.addProperty("x",x);route.addProperty("y",y);route.addProperty("kind",map.kind(x,y).name());JsonArray columns=new JsonArray();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int i=index(map,x,y,dx,dz);JsonObject q=new JsonObject();q.addProperty("before_blocks",prior.blocks()[i]);q.addProperty("after_blocks",after.blocks()[i]);q.addProperty("before_decor",prior.decor()[i]);q.addProperty("after_decor",after.decor()[i]);q.addProperty("before_shapes",Arrays.toString(prior.open()[i]));q.addProperty("after_shapes",Arrays.toString(after.open()[i]));columns.add(q);
                checks.add(()->assertEquals(prior.blocks()[i],after.blocks()[i],"collision "+i));checks.add(()->assertEquals(prior.decor()[i],after.decor()[i],"decor "+i));
                if(!changed){checks.add(()->EmeraldTerrainInterfaceProofTest.equalColumn(prior,after,i));unrelated++;}
            }
            if(changed){route.add("columns",columns);routes.add(route);}
        }
        for(var value:JsonParser.parseString(Files.readString(folder.resolve("assembly-requests.json"))).getAsJsonArray()){
            var spec=value.getAsJsonObject();int x=spec.get("x").getAsInt(),y=spec.get("y").getAsInt(),w=spec.get("w").getAsInt(),d=spec.get("d").getAsInt();var row=rectangle(map,x,y,w,d,before,after);assertEquals(spec.get("layout"),row.get("layout"));row.addProperty("id",spec.get("id").getAsString());row.add("source_floor_columns",sourceFloors(map,x,y,w,d,before,after,null,null));row.add("current_templates",templates(after,x,y,w,d));rows.add(row);
        }
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("loaded_live_sha256",sha(Files.readAllBytes(EmeraldTerrainInterfaceProofTest.LIVE)));proof.addProperty("unrelated_quarters_checked",unrelated);proof.add("occurrences",rows);proof.add("source_routes",routes);EmeraldSourceStackFollowupsTest.save("frontier-ranking/runtime-diagnostic.json",proof);
        assertAll(checks);assertEquals(18,found.size());assertEquals(9,rows.size());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(objects,map.objects());assertEquals(sites,map.swapSites());
        assertEquals(1,after.templates().stream().filter(t->t.x0()==50&&t.y0()==16).count(),"complete source case accepted");
        EmeraldSourceStackFollowupsTest.save("frontier-ranking/runtime-proof.json",proof);
    }
}
