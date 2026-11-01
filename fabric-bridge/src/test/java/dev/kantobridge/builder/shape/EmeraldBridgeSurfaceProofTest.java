package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.*;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact WorldPlan surfaces and shape carriers; no inferred floor cubes over water or bridge air. */
class EmeraldBridgeSurfaceProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT;
    static final Path OUT=EmeraldSourceStackFollowupsTest.STUDY.resolve("bridge-native-surfaces");
    static final String MAP="EM_ROUTE110";
    static final int[][] RECTANGLES={{18,5,16,9},{24,20,7,5},{8,28,24,7}};
    final String priorOutdoor=System.getProperty(OutdoorLevels.PROPERTY),priorChain=System.getProperty(MapChain.PROPERTY);

    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);
        if(priorOutdoor==null)System.clearProperty(OutdoorLevels.PROPERTY);else System.setProperty(OutdoorLevels.PROPERTY,priorOutdoor);
        if(priorChain==null)System.clearProperty(MapChain.PROPERTY);else System.setProperty(MapChain.PROPERTY,priorChain);}

    static WorldPlan plan(ExportBundle bundle,JsonObject table)throws Exception{
        return plan(bundle,table,List.of(MAP));
    }

    static WorldPlan plan(ExportBundle bundle,JsonObject table,List<String> maps)throws Exception{
        String text=table.toString();var cells=new TreeMap<String,FittedShapes.Entry>();var stacks=new TreeMap<String,String>();
        var layers=new TreeMap<String,List<FittedShapes.Layer>>();var aliases=new TreeMap<String,Set<String>>();
        var seats=new HashSet<String>();var walks=new HashSet<String>();var counters=new HashSet<String>();var stackFits=new HashSet<String>();
        var buildings=new ArrayList<FittedShapes.Building>();
        FittedShapes.parse(text,cells);FittedShapes.parseStacks(text,stacks);FittedShapes.parseLayers(text,layers);
        FittedShapes.parseAlsoStacks(text,aliases);FittedShapes.parseSeats(text,seats);FittedShapes.parseFlagged(text,"walk",walks);
        FittedShapes.parseCounters(text,counters);FittedShapes.parseStackFits(text,stackFits);FittedShapes.parseBuildings(text,buildings);
        FittedShapes.useTablesForTest(Map.of("emerald",new FittedShapes.Table(cells,Map.of(),stacks,seats,layers,buildings,walks,aliases,counters,stackFits)));
        return WorldPlanner.plan(bundle,new WorldPlanner.Options(maps,0,0,-64,true,Set.of()),
            new RegionBuild("hoenn",1,"emerald",new SlotMap.Range(0,SlotMap.familiesFor("gen3")-1),null,"hoenn_"));
    }

    record Render(WorldPlan plan,ChunkCodesView view,Map<String,SlotMap.Key> slots) {
        static Render of(WorldPlan plan){
            Map<String,SlotMap.Key> slots=new HashMap<>();
            plan.slotMap().slots().forEach((key,value)->slots.put(key.shape().name()+":"+value.index(),key));
            return new Render(plan,new ChunkCodesView(plan),slots);
        }
    }

    static JsonArray column(Render rendered,int cx,int cz,int dx,int dz){
        return column(rendered,MAP,cx,cz,dx,dz);
    }

    static JsonArray column(Render rendered,String mapName,int cx,int cz,int dx,int dz){
        WorldPlan plan=rendered.plan();PlacedMap placed=plan.map(mapName);var p=placed.placement();
        int[] origin=WorldPlan.cellOrigin(p,placed.layout(),cx,cz);int x=origin[0]+dx,z=origin[2]+dz;
        JsonArray out=new JsonArray();
        // The actual map's surface volume is ground..ground+32. Include its
        // lowest claimed world floor even when the map is lifted by a seam.
        int low=placed.interior()?p.y()-1:Math.min(WorldPlan.SURFACE_MIN_Y,p.y()-1);
        int high=placed.interior()?p.y()+(WorldPlan.openSky(p)?WorldPlan.SKY_TOP:WorldPlan.ceilingTop(placed.layout(),cx,cz)):
            Math.max(WorldPlan.SURFACE_MAX_Y,p.y()+placed.level(cx,cz)+WorldPlan.SKY_TOP+1);
        for(int y=low;y<=high;y++){
            int code=rendered.view().code(x,y,z),shape=plan.shapes().map().shapeAt(x,y,z);
            if(code==BlockCode.AIR||code==BlockCode.UNOWNED){assertEquals(0,shape,"shape without actual carrier");continue;}
            int kind=BlockCode.kind(code);JsonObject b=new JsonObject();b.addProperty("y",y-p.y());b.addProperty("code_kind",kind);b.addProperty("code_value",BlockCode.value(code));
            if(kind==BlockCode.KIND_FULL||kind==BlockCode.KIND_HALF||kind==BlockCode.KIND_SHAPE){
                SlotMap.Shape slotShape=kind==BlockCode.KIND_HALF?SlotMap.Shape.HALF:SlotMap.Shape.FULL;
                SlotMap.Key slot=rendered.slots().get(slotShape.name()+":"+BlockCode.value(code));assertNotNull(slot,"actual block code resolves to a source slot");
                int tile=QuarterPages.quarter(slot.atlas(),slot.tile());assertTrue(tile>=0);assertEquals("BAKED",slot.palette());
                b.addProperty("tile",tile);b.addProperty("slot_shape",slotShape.name());
            }
            if(shape>0){
                ShapeKey key=plan.shapes().index().key(shape);assertNotNull(key);assertEquals("g3q",plan.shapes().index().atlas(key.atlas()));
                b.addProperty("shape_key",key.toString());b.addProperty("palette",plan.shapes().index().palette(shape));
            }
            // STEP/EDGE/DECOR and story blocks have no ordinary visible mesh;
            // their shape-map key, when present, is the actual visible art.
            boolean plain=kind==BlockCode.KIND_FULL||kind==BlockCode.KIND_HALF||kind==BlockCode.KIND_SHAPE;
            boolean shapeCarrier=plain||kind==BlockCode.KIND_DECOR||kind==BlockCode.KIND_LEDGE||kind==BlockCode.KIND_STEP||kind==BlockCode.KIND_SEAT;
            b.addProperty("visible",plain||(shape>0&&shapeCarrier));
            if(BlockCode.isStory(code))b.addProperty("story_key",plan.storyCells().entries().get(BlockCode.storyIndex(code)).key());
            out.add(b);
        }
        return out;
    }

    @Test void originalAndCurrentRoute110RenderExactDeckWaterRampAndCarrierColumns()throws Exception{
        Path export=ROOT.resolve("local/minecraft/kantobridge/export/emerald");assumeTrue(Files.exists(export.resolve("index.json")));
        System.setProperty(OutdoorLevels.PROPERTY,"all");System.setProperty(MapChain.PROPERTY,"false");
        byte[] oldBytes=Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        byte[] nowBytes=Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));
        JsonObject old=JsonParser.parseString(new String(oldBytes,StandardCharsets.UTF_8)).getAsJsonObject(),now=JsonParser.parseString(new String(nowBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        ExportBundle bundle=ExportBundle.open(export);Render before=Render.of(plan(bundle,old)),after=Render.of(plan(bundle,now));
        var map=after.plan().scene().map(MAP);var built=G3Built.map(bundle,MAP);var a=before.plan().map(MAP);var b=after.plan().map(MAP);
        byte[] rawMapBytes=Files.readAllBytes(export.resolve("g3map/"+MAP+".json"));
        var rawMap=JsonParser.parseString(new String(rawMapBytes,StandardCharsets.UTF_8)).getAsJsonObject();byte[] rawCells=Base64.getDecoder().decode(rawMap.get("cells").getAsString());
        assertNotNull(a);assertNotNull(b);assertEquals(new Gson().toJson(a.layout()),new Gson().toJson(b.layout()));
        assertEquals(a.placement(),b.placement());assertArrayEquals(before.plan().scene().map(MAP).kinds(),map.kinds());assertArrayEquals(before.plan().scene().map(MAP).tags(),map.tags());
        assertEquals(before.plan().scene().map(MAP).swapSites(),map.swapSites());
        var oldShapes=before.plan().shapes().shapes(MAP);var newShapes=after.plan().shapes().shapes(MAP);JsonArray rows=new JsonArray();int quarters=0,decks=0,ramps=0,water=0;
        for(int[] r:RECTANGLES){
            int x0=r[0],z0=r[1],w=r[2],d=r[3];
            JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x0,z0,w,d,oldShapes,newShapes);
            row.addProperty("width",w);row.addProperty("depth",d);row.addProperty("surface_evidence_mode","worldplan-route110-v1");
            JsonArray templateProof=new JsonArray();for(var template:newShapes.templates()){
                int tw=template.entry().get("w").getAsInt(),td=template.entry().get("d").getAsInt();
                if(template.x0()<2*(x0+w)&&template.x0()+tw>2*x0&&template.y0()<2*(z0+d)&&template.y0()+td>2*z0){
                    JsonObject entry=new JsonObject();entry.addProperty("sig",template.sig());entry.addProperty("x0",template.x0());entry.addProperty("y0",template.y0());entry.add("entry",template.entry());templateProof.add(entry);
                }
            }row.add("current_templates",templateProof);
            row.add("actual_map_layout",new Gson().toJsonTree(b.layout()));JsonArray columns=new JsonArray();
            for(int z=0;z<d*2;z++)for(int x=0;x<w*2;x++){
                int cx=x0+x/2,cz=z0+z/2,tx=x0*2+x,tz=z0*2+z,i=tz*map.artCols()+tx;MapLayout layout=b.layout();
                assertNotNull(layout);var piece=layout.pieceAt(cx,cz);assertNotNull(piece);assertEquals(0,piece.dx());assertEquals(0,piece.dy());assertEquals(0,piece.dz());
                JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("feet",b.level(cx,cz));c.addProperty("source_tile",map.tile(tx,tz));
                c.addProperty("kind",map.kind(cx,cz).name());c.addProperty("raw_collision",Byte.toUnsignedInt(rawCells[(cz*rawMap.get("w").getAsInt()+cx)*4+2]));
                c.addProperty("built_collision",built.coll()[built.index(cx,cz)]);c.addProperty("model_kind",b.model().kind(cx,cz).name());
                c.addProperty("source_elevation",built.elev()[built.index(cx,cz)]);c.addProperty("source_tags",map.tags()[cz*map.w()+cx]);
                c.add("ramp",new Gson().toJsonTree(layout.ramp(cx,cz)));c.add("deck",new Gson().toJsonTree(layout.deck(cx,cz)));
                c.addProperty("before_effective_floor",oldShapes.floor()[i]>=0?oldShapes.floor()[i]:map.tile(tx,tz));c.addProperty("after_effective_floor",newShapes.floor()[i]>=0?newShapes.floor()[i]:map.tile(tx,tz));
                c.add("before",column(before,cx,cz,x%2,z%2));c.add("after",column(after,cx,cz,x%2,z%2));columns.add(c);quarters++;
                if(layout.deck(cx,cz)!=null)decks++;if(layout.ramp(cx,cz)!=null)ramps++;if(map.kind(cx,cz)==dev.kantobridge.scene.CellKind.WATER)water++;
            }
            row.add("source_surface_columns",columns);row.addProperty("source_semantics_unchanged",true);rows.add(row);
        }
        assertEquals(1388,quarters);
        JsonObject proof=new JsonObject();proof.addProperty("purpose","Exact WorldPlan block-code, native art and shape-map evidence for complete Route110 bridge contexts; no authoring or automatic visual approval");
        proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(oldBytes));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(nowBytes));
        proof.addProperty("source_map_sha256",EmeraldInteriorSourceProofTest.sha(rawMapBytes));proof.addProperty("source_index_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(export.resolve("index.json"))));
        proof.addProperty("outdoor_levels","all");proof.addProperty("chain_levels",false);proof.addProperty("datum","Map-local base; only Route110 placed, no claim of regional seam-chain absolute elevation");
        proof.addProperty("source_quarters",quarters);proof.addProperty("deck_quarters",decks);proof.addProperty("ramp_quarters",ramps);proof.addProperty("water_quarters",water);proof.add("occurrences",rows);
        Files.createDirectories(OUT);Files.write(OUT.resolve("fitted-baseline.json"),oldBytes);Files.write(OUT.resolve("fitted-current.json"),nowBytes);EmeraldSourceStackFollowupsTest.save("bridge-native-surfaces/runtime-proof.json",proof);
    }
}
