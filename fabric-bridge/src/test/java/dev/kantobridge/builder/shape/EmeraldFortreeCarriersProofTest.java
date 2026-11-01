package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Actual source maps: new forest art cannot change a single native collision, decor, floor or unrelated model. */
class EmeraldFortreeCarriersProofTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void allFiveForestsPreserveEveryMapCarrierAndEveryExcludedInterface()throws Exception{
        Path folder=STUDY.resolve("fortree-native-repairs"),live=EmeraldTerrainInterfaceProofTest.LIVE;
        var source=EmeraldSourceStackFollowupsTest.source();JsonObject original=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),base=read(live),patch=read(folder.resolve("additions.json")),candidate=EmeraldTerrainInterfaceProofTest.overlay(base,patch);
        String liveHash=sha(Files.readAllBytes(live));JsonArray rows=new JsonArray(),maps=new JsonArray();
        String[] names={"EM_ROUTE119","EM_ROUTE120"};int total=0;
        for(String name:names){
            SceneMap map=source.scene().map(name);var levels=EmeraldTerrainInterfaceProofTest.layout(name);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();
            var before=EmeraldSourceStackFollowupsTest.plan(map,source,base,true,levels);var after=EmeraldSourceStackFollowupsTest.plan(map,source,candidate,true,levels);var old=EmeraldSourceStackFollowupsTest.plan(map,source,original,true,levels);
            List<Templates.Placed> added=after.templates().stream().filter(t->t.entry().has("hull")&&t.entry().getAsJsonObject("hull").has("box")&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").has("preserveCarriers")).toList();
            assertEquals(name.equals("EM_ROUTE119")?3:2,added.size(),"A source profile refused an unsupported claim; do not broaden eligibility");assertEquals(before.templates().size()+added.size(),after.templates().size());
            boolean[] claimed=new boolean[map.artCols()*map.artRows()];
            for(var t:added){int w=t.entry().get("w").getAsInt(),d=t.entry().get("d").getAsInt();JsonObject spec=null;for(var item:patch.getAsJsonObject("buildings").entrySet()){var value=item.getValue().getAsJsonObject();if(value.getAsJsonArray("tiles").size()==d&&value.getAsJsonArray("tiles").get(0).getAsJsonArray().size()==w&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").getAsJsonArray("parts").equals(value.getAsJsonArray("parts")))spec=value;}assertNotNull(spec);Set<String> terrain=new HashSet<>();for(var e:spec.getAsJsonArray("terrain")){var p=e.getAsJsonArray();terrain.add(p.get(0).getAsInt()+","+p.get(1).getAsInt());}
                JsonObject row=rectangle(map,t.x0()/2,t.y0()/2,w/2,d/2,old,after);row.addProperty("id",spec.get("id").getAsString());row.addProperty("carrier_reference_table_sha256",liveHash);row.add("source_floor_columns",sourceFloors(map,t.x0()/2,t.y0()/2,w/2,d/2,old,after,levels,null));JsonArray reference=new JsonArray();
                for(int z=0;z<d;z++)for(int x=0;x<w;x++){int i=(t.y0()+z)*map.artCols()+t.x0()+x;boolean own=!terrain.contains(x/2+","+z/2);claimed[i]|=own;JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("blocks",before.blocks()[i]);c.addProperty("decor",before.decor()[i]);c.addProperty("floor",before.floor()[i]);c.addProperty("feet",levels==null?0:levels.level((t.x0()+x)/2,(t.y0()+z)/2));reference.add(c);
                    assertEquals(own?before.blocks()[i]:0,t.entry().getAsJsonObject("hull").getAsJsonArray("solid").get(z*w+x).getAsInt());assertEquals(0,t.entry().getAsJsonObject("hull").getAsJsonArray("air").get(z*w+x).getAsInt());}
                row.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("additions.json"))));row.add("current_carrier_reference",reference);JsonArray templates=new JsonArray();JsonObject p=new JsonObject();p.addProperty("sig",t.sig());p.addProperty("x0",t.x0());p.addProperty("y0",t.y0());p.add("entry",t.entry());templates.add(p);row.add("current_templates",templates);rows.add(row);
            }
            for(int i=0;i<claimed.length;i++){assertEquals(before.blocks()[i],after.blocks()[i],name+" collision "+i);assertEquals(before.decor()[i],after.decor()[i],name+" decor "+i);assertEquals(before.floor()[i],after.floor()[i],name+" floor "+i);if(!claimed[i])EmeraldTerrainInterfaceProofTest.equalColumn(before,after,i);total++;}
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());JsonObject report=new JsonObject();report.addProperty("map",name);report.addProperty("quarters_checked",claimed.length);report.addProperty("new_templates",added.size());maps.add(report);
        }
        assertEquals(liveHash,sha(Files.readAllBytes(live)));Files.copy(live,folder.resolve("fitted-carrier-reference.json"),StandardCopyOption.REPLACE_EXISTING);Files.writeString(folder.resolve("fitted-proof-current.json"),new GsonBuilder().setPrettyPrinting().create().toJson(candidate));
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(Files.readAllBytes(folder.resolve("fitted-proof-current.json"))));proof.addProperty("carrier_reference_table_sha256",liveHash);proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("additions.json"))));proof.addProperty("full_map_quarters_checked",total);proof.add("maps",maps);proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("fortree-native-repairs/runtime-proof.json",proof);
    }
}
