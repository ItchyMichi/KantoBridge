package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact 22 source beds: only their art changes; gameplay and the berry/story paths remain identical. */
class EmeraldBerrySoilProofTest {
    final String priorOutdoor=System.getProperty(OutdoorLevels.PROPERTY),priorChain=System.getProperty(MapChain.PROPERTY);
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);
        if(priorOutdoor==null)System.clearProperty(OutdoorLevels.PROPERTY);else System.setProperty(OutdoorLevels.PROPERTY,priorOutdoor);
        if(priorChain==null)System.clearProperty(MapChain.PROPERTY);else System.setProperty(MapChain.PROPERTY,priorChain);}

    @Test void allTwentyTwoBedsPreserveActualWorldPlanGameplayAndObjects()throws Exception{
        Path folder=STUDY.resolve("soil-native-appearance"),live=EmeraldTerrainInterfaceProofTest.LIVE;
        JsonObject diagnostic=read(folder.resolve("surface-runtime-proof.json")),original=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        byte[] liveBytes=Files.readAllBytes(live);String liveHash=sha(liveBytes);JsonObject base=JsonParser.parseString(new String(liveBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject patch=read(folder.resolve("additions.json")),candidate=EmeraldTerrainInterfaceProofTest.overlay(base,patch);
        Path inputs=folder.resolve("input-tables");Files.createDirectories(inputs);Files.write(inputs.resolve(liveHash+".json"),liveBytes);
        byte[] candidateBytes=new GsonBuilder().setPrettyPrinting().create().toJson(candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(inputs.resolve(sha(candidateBytes)+".json"),candidateBytes);
        List<String> maps=new ArrayList<>();for(var item:diagnostic.getAsJsonArray("occurrences")){String name=item.getAsJsonObject().get("map").getAsString();if(!maps.contains(name))maps.add(name);}
        System.setProperty(OutdoorLevels.PROPERTY,"all");System.setProperty(MapChain.PROPERTY,"false");
        ExportBundle bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        var oldWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,original,maps);var beforeWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,base,maps);var afterWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,candidate,maps);
        var beforeRender=EmeraldBridgeSurfaceProofTest.Render.of(beforeWorld);var afterRender=EmeraldBridgeSurfaceProofTest.Render.of(afterWorld);
        assertEquals(new Gson().toJson(beforeWorld.storyCells()),new Gson().toJson(afterWorld.storyCells()),"actual story entries and state art remain exact");
        JsonArray rows=new JsonArray(),reports=new JsonArray();int total=0,matched=0,berries=0;
        for(String name:maps){
            var map=afterWorld.scene().map(name);var before=beforeWorld.shapes().shapes(name);var after=afterWorld.shapes().shapes(name);var old=oldWorld.shapes().shapes(name);var placed=afterWorld.map(name);var levels=placed.layout();
            assertEquals(beforeWorld.map(name).placement(),placed.placement());assertEquals(new Gson().toJson(beforeWorld.map(name).layout()),new Gson().toJson(levels));
            assertArrayEquals(beforeWorld.scene().map(name).kinds(),map.kinds());assertArrayEquals(beforeWorld.scene().map(name).tags(),map.tags());
            assertArrayEquals(beforeWorld.scene().map(name).tiles(),map.tiles());assertEquals(beforeWorld.scene().map(name).objects(),map.objects());assertEquals(beforeWorld.scene().map(name).swapSites(),map.swapSites());
            List<Templates.Placed> added=after.templates().stream().filter(t->t.entry().has("hull")&&t.entry().getAsJsonObject("hull").has("box")&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").has("berrySoil")).toList();
            assertEquals(before.templates().size()+added.size(),after.templates().size());boolean[] claimed=new boolean[map.artCols()*map.artRows()];
            for(var t:added){
                int w=t.entry().get("w").getAsInt(),d=t.entry().get("d").getAsInt();JsonObject spec=null;
                for(var e:patch.getAsJsonObject("buildings").entrySet()){var v=e.getValue().getAsJsonObject();JsonArray flat=new JsonArray();for(var line:v.getAsJsonArray("tiles"))for(var tile:line.getAsJsonArray())flat.add(tile);
                    if(v.getAsJsonArray("tiles").size()==d&&v.getAsJsonArray("tiles").get(0).getAsJsonArray().size()==w&&t.entry().getAsJsonArray("tiles").equals(flat)&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").getAsJsonArray("parts").equals(v.getAsJsonArray("parts"))){assertNull(spec,"one exact source pattern per native template");spec=v;}}
                assertNotNull(spec);Set<String> terrain=new HashSet<>();for(var e:spec.getAsJsonArray("terrain")){var v=e.getAsJsonArray();terrain.add(v.get(0).getAsInt()+","+v.get(1).getAsInt());}
                JsonObject row=rectangle(map,t.x0()/2,t.y0()/2,w/2,d/2,old,after);row.addProperty("id",spec.get("id").getAsString());row.addProperty("carrier_reference_table_sha256",liveHash);
                row.add("source_floor_columns",sourceFloors(map,t.x0()/2,t.y0()/2,w/2,d/2,old,after,levels,null));JsonArray reference=new JsonArray(),objects=new JsonArray();
                for(var object:map.objects())if(object.x()>=t.x0()/2&&object.x()<(t.x0()+w)/2&&object.y()>=t.y0()/2&&object.y()<(t.y0()+d)/2)objects.add(new Gson().toJsonTree(object));row.add("scene_objects",objects);
                for(int z=0;z<d;z++)for(int x=0;x<w;x++){
                    int i=(t.y0()+z)*map.artCols()+t.x0()+x;boolean own=!terrain.contains(x/2+","+z/2);claimed[i]|=own;
                    JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("blocks",before.blocks()[i]);c.addProperty("decor",before.decor()[i]);c.addProperty("floor",before.floor()[i]);c.addProperty("feet",placed.level((t.x0()+x)/2,(t.y0()+z)/2));reference.add(c);
                    assertEquals(own?before.blocks()[i]:0,t.entry().getAsJsonObject("hull").getAsJsonArray("solid").get(z*w+x).getAsInt());assertEquals(0,t.entry().getAsJsonObject("hull").getAsJsonArray("air").get(z*w+x).getAsInt());
                    if(own){int cx=(t.x0()+x)/2,cz=(t.y0()+z)/2;assertEquals(1,map.objects().stream().filter(o->o.x()==cx&&o.y()==cz&&o.sprite().equals("OBJ_EVENT_GFX_BERRY_TREE")).count());
                        var a=EmeraldBridgeSurfaceProofTest.column(beforeRender,name,cx,cz,x%2,z%2);var b=EmeraldBridgeSurfaceProofTest.column(afterRender,name,cx,cz,x%2,z%2);assertEquals(collisionCodes(a),collisionCodes(b),"actual full/decor/story carriers at "+name+":"+cx+":"+cz);if(x%2==0&&z%2==0)berries++;}
                }
                row.add("current_carrier_reference",reference);JsonArray templates=new JsonArray();JsonObject p=new JsonObject();p.addProperty("sig",t.sig());p.addProperty("x0",t.x0());p.addProperty("y0",t.y0());p.add("entry",t.entry());templates.add(p);row.add("current_templates",templates);rows.add(row);matched++;
            }
            for(int i=0;i<claimed.length;i++){assertEquals(before.blocks()[i],after.blocks()[i],name+" blocks "+i);assertEquals(before.decor()[i],after.decor()[i],name+" decor "+i);assertEquals(before.floor()[i],after.floor()[i],name+" floor "+i);if(!claimed[i])EmeraldTerrainInterfaceProofTest.equalColumn(before,after,i);total++;}
            JsonObject report=new JsonObject();report.addProperty("map",name);report.addProperty("quarters_checked",claimed.length);report.addProperty("new_templates",added.size());report.addProperty("before_templates",before.templates().size());report.addProperty("current_templates",after.templates().size());
            report.addProperty("before_preserve_carriers",before.templates().stream().filter(t->t.entry().has("hull")&&t.entry().getAsJsonObject("hull").has("box")&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").has("preserveCarriers")).count());
            report.addProperty("current_preserve_carriers",after.templates().stream().filter(t->t.entry().has("hull")&&t.entry().getAsJsonObject("hull").has("box")&&t.entry().getAsJsonObject("hull").getAsJsonObject("box").has("preserveCarriers")).count());reports.add(report);
        }
        JsonObject diagnosticResult=new JsonObject();diagnosticResult.addProperty("matched",matched);diagnosticResult.addProperty("berries",berries);diagnosticResult.add("maps",reports.deepCopy());diagnosticResult.add("occurrences",rows.deepCopy());JsonArray missing=new JsonArray();
        for(var v:diagnostic.getAsJsonArray("occurrences")){var r=v.getAsJsonObject();boolean found=false;for(var value:rows){var a=value.getAsJsonObject();if(a.get("map").equals(r.get("map"))&&a.get("x").equals(r.get("x"))&&a.get("y").equals(r.get("y")))found=true;}if(!found)missing.add(r.deepCopy());}
        diagnosticResult.addProperty("carrier_reference_table_sha256",liveHash);diagnosticResult.addProperty("candidate_table_sha256",sha(candidateBytes));diagnosticResult.add("unmatched_source_contexts",missing);EmeraldSourceStackFollowupsTest.save("soil-native-appearance/claim-diagnostic.json",diagnosticResult);
        assertEquals(22,matched);assertEquals(67,berries);assertEquals(liveHash,sha(Files.readAllBytes(live)));
        Files.write(folder.resolve("fitted-carrier-reference.json"),liveBytes);Files.writeString(folder.resolve("fitted-proof-current.json"),new GsonBuilder().setPrettyPrinting().create().toJson(candidate));
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(Files.readAllBytes(folder.resolve("fitted-proof-current.json"))));proof.addProperty("carrier_reference_table_sha256",liveHash);proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("additions.json"))));
        proof.addProperty("outdoor_levels","all");proof.addProperty("chain_levels",false);proof.addProperty("landscape_override",false);proof.addProperty("full_map_quarters_checked",total);proof.addProperty("berry_source_positions",berries);proof.addProperty("actual_story_table_unchanged",true);proof.add("maps",reports);proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("soil-native-appearance/runtime-proof.json",proof);
    }
    static JsonArray collisionCodes(JsonArray input){JsonArray out=new JsonArray();for(var e:input){var b=e.getAsJsonObject();int kind=b.get("code_kind").getAsInt();JsonObject v=new JsonObject();v.add("y",b.get("y"));v.addProperty("kind",kind==1||kind==7?1:kind);v.add("value",b.get("code_value"));out.add(v);}return out;}
}
