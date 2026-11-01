package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A rejected carrier claim cannot hide a separate valid object or a terrain-preserving building. */
class FittedCarrierCompositionTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static JsonObject model(String letters,boolean carrier){
        JsonObject b=new JsonObject();b.addProperty("atlas",ShapeFixtures.ATLAS);b.addProperty("height",48);b.addProperty("preserveTerrain",true);
        if(carrier)b.addProperty("preserveCarriers",true);JsonArray tiles=new JsonArray();
        for(int y=0;y<2;y++){JsonArray row=new JsonArray();for(char c:letters.toCharArray()){row.add(ShapeFixtures.tile(c));row.add(ShapeFixtures.tile(c));}tiles.add(row);}b.add("tiles",tiles);
        b.add("terrain",JsonParser.parseString(carrier?"[]":"[[1,0]]"));
        b.add("parts",JsonParser.parseString("[{\"shape\":\"box\",\"x\":[0,16],\"z\":[0,16],\"y\":[0,4],\"paint\":{\"front\":[0,0,1,1],\"side\":[0,0,1,1],\"top\":[0,0,1,1],\"back\":[0,0,1,1]}}]"));return b;
    }
    static ShapePlanner.MapShapes plan(SceneMap map,List<JsonObject> models,Map<String,Integer> tally){
        JsonObject root=new JsonObject();JsonArray values=new JsonArray();models.forEach(values::add);root.add("buildings",values);List<FittedShapes.Building> parsed=new ArrayList<>();FittedShapes.parseBuildings(root.toString(),parsed);
        FittedShapes.useTablesForTest(Map.of("emerald",new FittedShapes.Table(Map.of(),Map.of(),Map.of(),Set.of(),Map.of(),parsed)));
        String game=FittedShapes.enter("emerald");try{return ShapePlanner.planMap(map,ShapeFixtures.TIER_B,0,ShapeParams.p2(),null,null,tally);}finally{FittedShapes.leave(game);}
    }
    static void equalOutsideFirstCell(ShapePlanner.MapShapes a,ShapePlanner.MapShapes b,SceneMap map){
        for(int i=0;i<a.blocks().length;i++)if(i%map.artCols()>=2){assertEquals(a.blocks()[i],b.blocks()[i]);assertEquals(a.decor()[i],b.decor()[i]);assertEquals(a.floor()[i],b.floor()[i]);assertEquals(a.shapeMask()[i],b.shapeMask()[i]);assertArrayEquals(a.open()[i],b.open()[i]);}
    }
    @Test void validCarrierComposesWithExistingTerrainModelAndLeavesItExact(){
        var map=ShapeFixtures.surface("MIXED","AA..CCDD","AA..CCDD");var terrain=model("CD",false);var carrier=model("A",true);
        var before=plan(map,List.of(terrain),new TreeMap<>());var after=plan(map,List.of(terrain,carrier),new TreeMap<>());
        assertEquals(1,before.templates().size());assertEquals(2,after.templates().size());assertEquals(before.templates().getFirst(),after.templates().getFirst());
        assertArrayEquals(before.blocks(),after.blocks());assertArrayEquals(before.decor(),after.decor());assertArrayEquals(before.floor(),after.floor());equalOutsideFirstCell(before,after,map);
        assertEquals(ShapeKey.Kind.TMPL,after.open()[0][0].kind());
    }
    @Test void refusedWarpClaimCannotSuppressValidClaimOrAlterItsNativeSource(){
        var map=ShapeFixtures.surface("DISJOINT","AA..BB..","AA..BB..");map.tags()[2]=CellTag.WARP.bit();var a=model("A",true);var b=model("B",true);
        var ordinary=plan(map,List.of(),new TreeMap<>());var accepted=plan(map,List.of(a),new TreeMap<>());Map<String,Integer> tally=new TreeMap<>();var mixed=plan(map,List.of(a,b),tally);
        assertEquals(1,accepted.templates().size());assertEquals(accepted.templates(),mixed.templates());assertEquals(1,tally.get("fitted_native_carriers_refused"));
        assertArrayEquals(accepted.blocks(),mixed.blocks());assertArrayEquals(accepted.decor(),mixed.decor());assertArrayEquals(accepted.floor(),mixed.floor());
        for(int i=0;i<mixed.open().length;i++)assertArrayEquals(accepted.open()[i],mixed.open()[i]);equalOutsideFirstCell(ordinary,mixed,map);
        assertEquals(CellTag.WARP.bit(),map.tags(2,0));
    }
}
