package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import static dev.kantobridge.builder.shape.EmeraldTrainerArchitectureProofTest.templates;
import com.google.gson.*;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Native source contexts for the Claude continuation of the Emerald model pass.
 *
 * <p>Every study folder holding {@code claude-native.json} ({@code requests}: id, map, x, y, w, d, layout) and an
 * optional candidate {@code updates.json} is planned three times on its real maps (original baseline, live table,
 * live + candidate). Columns of cells the candidate does not edit must stay byte-identical; with
 * {@code keep_collision}, edited cells must also keep their carrier height and decoration. Each request becomes a
 * source rectangle for gallery native-context exhibits ({@code runtime-proof.json}).
 */
class EmeraldClaudeNativeContextTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}

    @Test void claudeNativeContextsKeepUnrelatedColumnsAndRecordSourceRectangles()throws Exception{
        List<Path> folders;
        try(var list=Files.list(STUDY)){folders=list.filter(p->Files.exists(p.resolve("claude-native.json"))).sorted().toList();}
        assumeTrue(!folders.isEmpty(),"no Claude native-context requests");
        var source=EmeraldSourceStackFollowupsTest.source();
        Path baselinePath=ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json");
        var baseline=read(baselinePath);var live=read(EmeraldTerrainInterfaceProofTest.LIVE);
        Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        List<Executable> checks=new ArrayList<>();
        String liveSha=sha(Files.readAllBytes(EmeraldTerrainInterfaceProofTest.LIVE));
        for(Path folder:folders){
            JsonObject config=read(folder.resolve("claude-native.json"));
            Path updates=folder.resolve("updates.json");
            // A proof already planned for exactly this candidate, live table and request file is current: skip it
            // (each folder plans its maps three times over the full table; the test heap is finite).
            Path old=folder.resolve("runtime-proof.json");
            if(Files.exists(old)){
                JsonObject prev=read(old);
                String want=Files.exists(updates)?sha(Files.readAllBytes(updates)):null;
                String cfg=sha(Files.readAllBytes(folder.resolve("claude-native.json")));
                if(prev.has("loaded_live_sha256")&&liveSha.equals(prev.get("loaded_live_sha256").getAsString())
                        &&prev.has("request_sha256")&&cfg.equals(prev.get("request_sha256").getAsString())
                        &&(want==null?(!prev.has("candidate_updates_sha256")||prev.get("candidate_updates_sha256").isJsonNull())
                            :prev.has("candidate_updates_sha256")&&want.equals(prev.get("candidate_updates_sha256").getAsString())))continue;
            }
            // Hash exactly the bytes planned: a candidate rewritten during the run must not be recorded as proved.
            byte[] patchBytes=Files.exists(updates)?Files.readAllBytes(updates):null;
            JsonObject patch=patchBytes!=null?JsonParser.parseString(new String(patchBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject()
                :JsonParser.parseString("{\"cells\":{},\"buildings\":{}}").getAsJsonObject();
            var current=EmeraldTerrainInterfaceProofTest.overlay(live,patch);
            String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";
            Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);
            Set<String> edited=patch.getAsJsonObject("cells").keySet();
            boolean keepCollision=config.has("keep_collision")&&config.get("keep_collision").getAsBoolean();
            // Handing wall cells back to native columns re-measures their whole wall run, so unedited native
            // cells of that run change height by design: with allow_native_reflow they are recorded, not refused.
            boolean reflow=config.has("allow_native_reflow")&&config.get("allow_native_reflow").getAsBoolean();
            JsonArray reflowed=new JsonArray();
            Map<String,List<JsonObject>> byMap=new TreeMap<>();
            for(JsonElement v:config.getAsJsonArray("requests"))byMap.computeIfAbsent(v.getAsJsonObject().get("map").getAsString(),k->new ArrayList<>()).add(v.getAsJsonObject());
            JsonArray rows=new JsonArray(),routes=new JsonArray();int unrelated=0;
            for(var entry:byMap.entrySet()){
                SceneMap map=source.scene().map(entry.getKey());Integer room=rooms.get(entry.getKey());if(room!=null)map=map.withHeadroom(room);
                SceneMap m=map;byte[] kinds=m.kinds().clone();int[] tiles=m.tiles().clone();
                var before=EmeraldSourceStackFollowupsTest.plan(m,source,baseline,true);
                var prior=EmeraldSourceStackFollowupsTest.plan(m,source,live,true);
                var after=EmeraldSourceStackFollowupsTest.plan(m,source,current,true);
                for(int y=0;y<m.h();y++)for(int x=0;x<m.w();x++){
                    String key=FittedShapes.key(m.atlas(),new int[]{m.tile(x*2,y*2),m.tile(x*2+1,y*2),m.tile(x*2,y*2+1),m.tile(x*2+1,y*2+1)});
                    boolean changed=edited.contains(key);JsonArray columns=new JsonArray();
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        int i=index(m,x,y,dx,dz);
                        if(!changed){
                            unrelated++;String where=m.id()+" "+x+","+y+" "+key;
                            if(reflow){
                                if(prior.blocks()[i]!=after.blocks()[i]||!Arrays.equals(prior.open()[i],after.open()[i])){JsonObject r=new JsonObject();r.addProperty("map",m.id());r.addProperty("x",x);r.addProperty("y",y);r.addProperty("key",key);r.addProperty("quarter",dz*2+dx);r.addProperty("before_blocks",prior.blocks()[i]);r.addProperty("after_blocks",after.blocks()[i]);reflowed.add(r);}
                                checks.add(()->assertEquals(prior.decor()[i],after.decor()[i],"decor "+where));
                            }else checks.add(()->{try{EmeraldTerrainInterfaceProofTest.equalColumn(prior,after,i);}catch(AssertionError e){throw new AssertionError(where+": "+e.getMessage(),e);}});
                            continue;
                        }
                        JsonObject q=new JsonObject();q.addProperty("before_blocks",prior.blocks()[i]);q.addProperty("after_blocks",after.blocks()[i]);
                        q.addProperty("before_decor",prior.decor()[i]);q.addProperty("after_decor",after.decor()[i]);
                        q.addProperty("before_shapes",Arrays.toString(prior.open()[i]));q.addProperty("after_shapes",Arrays.toString(after.open()[i]));columns.add(q);
                        if(keepCollision){String where=m.id()+" "+x+","+y;checks.add(()->assertEquals(prior.blocks()[i],after.blocks()[i],"collision "+where));checks.add(()->assertEquals(prior.decor()[i],after.decor()[i],"decor "+where));}
                    }
                    if(changed){JsonObject route=new JsonObject();route.addProperty("key",key);route.addProperty("map",m.id());route.addProperty("x",x);route.addProperty("y",y);route.addProperty("kind",m.kind(x,y).name());route.add("columns",columns);routes.add(route);}
                }
                for(JsonObject spec:entry.getValue()){
                    int x=spec.get("x").getAsInt(),y=spec.get("y").getAsInt(),w=spec.get("w").getAsInt(),d=spec.get("d").getAsInt();
                    JsonObject row=rectangle(m,x,y,w,d,before,after);
                    if(spec.has("layout"))assertEquals(spec.get("layout"),row.get("layout"),"request layout "+spec.get("id"));
                    row.addProperty("id",spec.get("id").getAsString());
                    row.add("source_floor_columns",sourceFloors(m,x,y,w,d,before,after,null,null));
                    row.add("current_templates",templates(after,x,y,w,d));rows.add(row);
                }
                assertArrayEquals(kinds,m.kinds());assertArrayEquals(tiles,m.tiles());
            }
            JsonObject proof=new JsonObject();
            proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(baselinePath)));
            proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            proof.addProperty("candidate_updates_sha256",patchBytes!=null?sha(patchBytes):null);
            proof.addProperty("loaded_live_sha256",liveSha);
            proof.addProperty("request_sha256",sha(Files.readAllBytes(folder.resolve("claude-native.json"))));
            proof.addProperty("unrelated_quarters_checked",unrelated);proof.add("occurrences",rows);proof.add("source_routes",routes);
            proof.add("native_reflow",reflowed);
            EmeraldSourceStackFollowupsTest.save(folder.getFileName()+"/runtime-proof.json",proof);
        }
        assertAll(checks);
    }
}
