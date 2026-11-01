package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.G3Built;
import dev.kantobridge.builder.plan.G3Cells;
import dev.kantobridge.builder.plan.RaisedRegions;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapLayout;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact soil rectangles, retaining native perimeter floors and berry columns. */
class EmeraldSoilSourceProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT;
    static final Path OUT=EmeraldSourceStackFollowupsTest.STUDY.resolve("soil-perimeter-completion");
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void completeBedsKeepTheirActualSourceFloorAndColumnRoutes()throws Exception{
        assumeTrue(Files.exists(OUT.resolve("runtime-source-cases.json")));
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        JsonObject baseline=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        JsonObject live=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();
        for(var e:EmeraldSourceStackFollowupsTest.read(OUT.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(OUT.resolve("fitted-proof-current.json"),snapshot);
        JsonArray rows=new JsonArray();Map<String,ShapePlanner.MapShapes> olds=new HashMap<>(),lives=new HashMap<>(),news=new HashMap<>();
        for(JsonElement v:EmeraldSourceStackFollowupsTest.read(OUT.resolve("runtime-source-cases.json")).getAsJsonArray("groups")){
            JsonObject group=v.getAsJsonObject();int w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),d=group.getAsJsonArray("layout").size();
            for(JsonElement occurrence:group.getAsJsonArray("occurrences")){
                JsonObject p=occurrence.getAsJsonObject();String name=p.get("map").getAsString();int x0=p.get("x").getAsInt(),z0=p.get("y").getAsInt();SceneMap map=source.scene().map(name);
                var built=G3Built.map(bundle,name);var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);MapLayout layout=levels.layout();
                byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
                var before=olds.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout));
                var unchanged=lives.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,live,true,layout));
                var after=news.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout));
                JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x0,z0,w,d,before,after);assertEquals(group.get("layout"),row.get("layout"));row.addProperty("id",group.get("id").getAsString()+"_"+name.toLowerCase()+"_"+x0+"_"+z0);row.addProperty("raised_outcome",levels.outcome());
                JsonArray floors=new JsonArray(),audit=new JsonArray();
                for(int z=0;z<d*2;z++)for(int x=0;x<w*2;x++){
                    int tx=x0*2+x,tz=z0*2+z,i=tz*map.artCols()+tx,cx=tx/2,cz=tz/2;CellKind kind=map.kind(cx,cz);
                    assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);
                    assertTrue(Set.of(CellKind.WALK,CellKind.SOLID,CellKind.COUNTER,CellKind.LEDGE).contains(kind),kind.name());
                    JsonObject f=new JsonObject();f.addProperty("x",x);f.addProperty("z",z);f.addProperty("feet",(layout==null?0:layout.level(cx,cz)));f.addProperty("before_tile",before.floor()[i]>=0?before.floor()[i]:map.tile(tx,tz));f.addProperty("after_tile",after.floor()[i]>=0?after.floor()[i]:map.tile(tx,tz));floors.add(f);
                    JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("kind",kind.name());c.addProperty("raw_collision",built.coll()[built.index(cx,cz)]);c.addProperty("feet",(layout==null?0:layout.level(cx,cz)));c.addProperty("before_blocks",before.blocks()[i]);c.addProperty("after_blocks",after.blocks()[i]);c.addProperty("before_decor",before.decor()[i]);c.addProperty("after_decor",after.decor()[i]);c.addProperty("before_keys",Arrays.toString(EmeraldInteriorSourceProofTest.sourceColumn(before,map,tx,tz)));c.addProperty("after_keys",Arrays.toString(EmeraldInteriorSourceProofTest.sourceColumn(after,map,tx,tz)));audit.add(c);
                }
                row.add("source_floor_columns",floors);row.add("source_column_audit",audit);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());rows.add(row);
            }
        }
        assertTrue(rows.size()>=19);JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(OUT.resolve("updates.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("soil-perimeter-completion/runtime-proof.json",proof);
    }
}
