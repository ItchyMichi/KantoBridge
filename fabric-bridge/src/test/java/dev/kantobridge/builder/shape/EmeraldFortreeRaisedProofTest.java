package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Actual raised Route120: only two exact source claims change their visible shape. */
class EmeraldFortreeRaisedProofTest {
    final String priorOutdoor=System.getProperty(OutdoorLevels.PROPERTY),priorChain=System.getProperty(MapChain.PROPERTY);
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);
        if(priorOutdoor==null)System.clearProperty(OutdoorLevels.PROPERTY);else System.setProperty(OutdoorLevels.PROPERTY,priorOutdoor);
        if(priorChain==null)System.clearProperty(MapChain.PROPERTY);else System.setProperty(MapChain.PROPERTY,priorChain);}

    @Test void bothSteppedForestsPreserveEveryActualMapCarrier()throws Exception{
        Path folder=STUDY.resolve("fortree-raised-carriers"),live=EmeraldTerrainInterfaceProofTest.LIVE;
        Path export=ROOT.resolve("local/minecraft/kantobridge/export/emerald");String name="EM_ROUTE120";
        byte[] liveBytes=Files.readAllBytes(live),oldBytes=Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        String liveHash=sha(liveBytes);JsonObject base=JsonParser.parseString(new String(liveBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject original=JsonParser.parseString(new String(oldBytes,StandardCharsets.UTF_8)).getAsJsonObject(),patch=read(folder.resolve("updates.json"));
        JsonObject pre=read(folder.resolve("preconditions.json")),evidence=read(folder.resolve("source-level-evidence.json"));
        JsonObject candidate=base.deepCopy();int replacements=0;
        for(int i=0;i<base.getAsJsonArray("buildings").size();i++){
            var building=base.getAsJsonArray("buildings").get(i).getAsJsonObject();String id=building.get("id").getAsString();
            if(pre.has(id)){assertEquals(pre.get(id),building,"frozen model precondition");candidate.getAsJsonArray("buildings").set(i,patch.getAsJsonObject("buildings").get(id));replacements++;}
        }
        assertEquals(2,replacements);
        byte[] candidateBytes=(new GsonBuilder().setPrettyPrinting().create().toJson(candidate)+"\n").getBytes(StandardCharsets.UTF_8);
        Path inputs=folder.resolve("input-tables");Files.createDirectories(inputs);Files.write(inputs.resolve(liveHash+".json"),liveBytes);Files.write(inputs.resolve(sha(candidateBytes)+".json"),candidateBytes);
        System.setProperty(OutdoorLevels.PROPERTY,"all");System.setProperty(MapChain.PROPERTY,"false");
        ExportBundle bundle=ExportBundle.open(export);List<String> names=List.of(name);
        var oldWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,original,names);var beforeWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,base,names);var afterWorld=EmeraldBridgeSurfaceProofTest.plan(bundle,candidate,names);
        var oldRender=EmeraldBridgeSurfaceProofTest.Render.of(oldWorld);var beforeRender=EmeraldBridgeSurfaceProofTest.Render.of(beforeWorld);var afterRender=EmeraldBridgeSurfaceProofTest.Render.of(afterWorld);
        var map=afterWorld.scene().map(name);var before=beforeWorld.shapes().shapes(name);var after=afterWorld.shapes().shapes(name);var old=oldWorld.shapes().shapes(name);var placed=afterWorld.map(name);
        assertEquals(beforeWorld.map(name).placement(),placed.placement());assertEquals(new Gson().toJson(beforeWorld.map(name).layout()),new Gson().toJson(placed.layout()));
        assertEquals(new Gson().toJson(oldWorld.map(name).layout()),new Gson().toJson(placed.layout()));
        assertArrayEquals(beforeWorld.scene().map(name).kinds(),map.kinds());assertArrayEquals(beforeWorld.scene().map(name).tags(),map.tags());assertArrayEquals(beforeWorld.scene().map(name).tiles(),map.tiles());
        assertEquals(beforeWorld.scene().map(name).objects(),map.objects());assertEquals(beforeWorld.scene().map(name).swapSites(),map.swapSites());
        assertEquals(new Gson().toJson(beforeWorld.storyCells()),new Gson().toJson(afterWorld.storyCells()));
        var built=G3Built.map(bundle,name);byte[] rawBytes=Files.readAllBytes(export.resolve("g3map/"+name+".json"));JsonObject raw=JsonParser.parseString(new String(rawBytes,StandardCharsets.UTF_8)).getAsJsonObject();byte[] rawCells=Base64.getDecoder().decode(raw.get("cells").getAsString());
        boolean[] claimed=new boolean[map.artCols()*map.artRows()];JsonArray rows=new JsonArray();int count=0,claimedQuarters=0;
        for(var value:evidence.getAsJsonArray("groups")){
            JsonObject source=value.getAsJsonObject(),spec=patch.getAsJsonObject("buildings").getAsJsonObject(source.get("id").getAsString());
            int x0=source.get("x").getAsInt(),z0=source.get("y").getAsInt(),w=source.getAsJsonArray("layout").get(0).getAsJsonArray().size(),d=source.getAsJsonArray("layout").size();
            assertEquals(0,before.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==z0*2&&exactSource(t,spec)).count(),"uniform template must be refused at actual unequal feet");
            List<Templates.Placed> accepted=after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==z0*2&&exactSource(t,spec)).toList();assertEquals(1,accepted.size());var t=accepted.getFirst();count++;
            JsonObject row=rectangle(map,x0,z0,w,d,old,after);assertEquals(source.get("layout"),row.get("layout"));assertEquals(source.get("source_collisions"),row.get("source_collisions"));assertEquals(source.get("map_cells_sha256"),row.get("map_cells_sha256"));
            row.addProperty("id",source.get("id").getAsString());row.addProperty("width",w);row.addProperty("depth",d);row.addProperty("surface_evidence_mode","worldplan-environment-v1");row.add("actual_map_layout",new Gson().toJsonTree(placed.layout()));
            row.add("carrierLevels",spec.get("carrierLevels"));row.addProperty("carrier_reference_table_sha256",liveHash);row.addProperty("before_matching_templates",0);row.addProperty("current_matching_templates",1);
            Set<String> terrain=new HashSet<>();for(var e:spec.getAsJsonArray("terrain")){var p=e.getAsJsonArray();terrain.add(p.get(0).getAsInt()+","+p.get(1).getAsInt());}
            JsonArray columns=new JsonArray(),reference=new JsonArray();
            for(int z=0;z<d*2;z++)for(int x=0;x<w*2;x++){
                int cx=x0+x/2,cz=z0+z/2,tx=x0*2+x,tz=z0*2+z,i=tz*map.artCols()+tx;boolean own=!terrain.contains(x/2+","+z/2);claimed[i]|=own;if(own)claimedQuarters++;
                JsonObject q=new JsonObject();q.addProperty("x",x);q.addProperty("z",z);q.addProperty("feet",placed.level(cx,cz));q.addProperty("source_tile",map.tile(tx,tz));q.addProperty("kind",map.kind(cx,cz).name());q.addProperty("model_kind",placed.model().kind(cx,cz).name());
                q.addProperty("raw_collision",Byte.toUnsignedInt(rawCells[(cz*raw.get("w").getAsInt()+cx)*4+2]));q.addProperty("built_collision",built.coll()[built.index(cx,cz)]);q.addProperty("source_elevation",built.elev()[built.index(cx,cz)]);q.addProperty("source_tags",map.tags()[cz*map.w()+cx]);
                q.add("ramp",new Gson().toJsonTree(placed.layout().ramp(cx,cz)));q.add("deck",new Gson().toJsonTree(placed.layout().deck(cx,cz)));
                q.addProperty("before_effective_floor",old.floor()[i]>=0?old.floor()[i]:map.tile(tx,tz));q.addProperty("after_effective_floor",after.floor()[i]>=0?after.floor()[i]:map.tile(tx,tz));
                q.add("before",EmeraldBridgeSurfaceProofTest.column(oldRender,name,cx,cz,x%2,z%2));q.add("after",EmeraldBridgeSurfaceProofTest.column(afterRender,name,cx,cz,x%2,z%2));columns.add(q);
                JsonObject r=new JsonObject();r.addProperty("x",x);r.addProperty("z",z);r.addProperty("blocks",before.blocks()[i]);r.addProperty("decor",before.decor()[i]);r.addProperty("floor",before.floor()[i]);r.addProperty("feet",placed.level(cx,cz));reference.add(r);
                assertEquals(own?before.blocks()[i]:0,t.entry().getAsJsonObject("hull").getAsJsonArray("solid").get(z*w*2+x).getAsInt());assertEquals(0,t.entry().getAsJsonObject("hull").getAsJsonArray("air").get(z*w*2+x).getAsInt());
                if(own){assertEquals(source.getAsJsonArray("actual_source_feet").get(z/2).getAsJsonArray().get(x/2).getAsInt(),placed.level(cx,cz));
                    assertEquals(EmeraldBerrySoilProofTest.collisionCodes(EmeraldBridgeSurfaceProofTest.column(beforeRender,name,cx,cz,x%2,z%2)),EmeraldBerrySoilProofTest.collisionCodes(q.getAsJsonArray("after")),"actual gameplay carriers "+cx+":"+cz);}
            }
            row.add("source_surface_columns",columns);row.add("current_carrier_reference",reference);JsonArray templates=new JsonArray();
            for(var template:after.templates()){int tw=template.entry().get("w").getAsInt(),td=template.entry().get("d").getAsInt();if(template.x0()<2*(x0+w)&&template.x0()+tw>2*x0&&template.y0()<2*(z0+d)&&template.y0()+td>2*z0){JsonObject p=new JsonObject();p.addProperty("sig",template.sig());p.addProperty("x0",template.x0());p.addProperty("y0",template.y0());p.add("entry",template.entry());templates.add(p);}}
            row.add("current_templates",templates);rows.add(row);
        }
        assertEquals(2,count);assertEquals(before.templates().size()+2,after.templates().size());
        for(int i=0;i<claimed.length;i++){assertEquals(before.blocks()[i],after.blocks()[i],"collision "+i);assertEquals(before.decor()[i],after.decor()[i],"decor "+i);assertEquals(before.floor()[i],after.floor()[i],"floor "+i);if(!claimed[i])EmeraldTerrainInterfaceProofTest.equalColumn(before,after,i);}
        assertEquals(liveHash,sha(Files.readAllBytes(live)));Files.write(folder.resolve("fitted-carrier-reference.json"),liveBytes);Files.write(folder.resolve("fitted-surface-current.json"),candidateBytes);Files.write(folder.resolve("fitted-surface-baseline.json"),oldBytes);
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(oldBytes));proof.addProperty("current_table_sha256",sha(candidateBytes));proof.addProperty("carrier_reference_table_sha256",liveHash);proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("source_level_evidence_sha256",sha(Files.readAllBytes(folder.resolve("source-level-evidence.json"))));proof.addProperty("source_map_sha256",sha(rawBytes));
        proof.addProperty("outdoor_levels","all");proof.addProperty("chain_levels",false);proof.addProperty("landscape_override",false);proof.addProperty("full_map_quarters_checked",claimed.length);proof.addProperty("claimed_quarters",claimedQuarters);proof.addProperty("before_matching_templates",0);proof.addProperty("current_matching_templates",count);proof.addProperty("before_total_templates",before.templates().size());proof.addProperty("current_total_templates",after.templates().size());proof.addProperty("actual_story_table_unchanged",true);proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("fortree-raised-carriers/runtime-proof.json",proof);
    }
    static boolean exactSource(Templates.Placed t,JsonObject spec){JsonArray flat=new JsonArray();for(var row:spec.getAsJsonArray("tiles"))for(var tile:row.getAsJsonArray())flat.add(tile);return t.entry().getAsJsonArray("tiles").equals(flat);}
}
