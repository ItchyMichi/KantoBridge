package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.builder.shape.*;
import dev.kantobridge.world.ShapeIndex;
import java.util.*;
import org.junit.jupiter.api.Test;

class BerrySoilMeshTest {
    @Test void retainedUpperCollisionCarriersHaveValidEmptyMeshesInsteadOfCubeFallback(){
        JsonObject entry=JsonParser.parseString("""
            {"template":1,"atlas":0,"w":2,"d":2,"perCell":2,"tiles":[1,1,1,1],"doors":[],"ground":1,"outdoor":true,"columns":[[],[],[],[]],
             "hull":{"kind":"box","height":40,"solid":[5,5,5,5],"air":[0,0,0,0],"box":{"height":40,"roofRows":0,"roof":"flat","terrain":[],"bands":[],"preserveTerrain":true,"preserveCarriers":true,"berrySoil":true,
             "parts":[{"shape":"box","x":[0,16],"z":[0,16],"y":[0,4]}]}}}
            """).getAsJsonObject();
        String sig=Templates.signature(entry);entry.addProperty("sig",sig);JsonArray entries=new JsonArray();entries.add(entry);
        List<ShapeIndex.Use> uses=new ArrayList<>();for(int z=0;z<2;z++)for(int x=0;x<2;x++)for(int y=0;y<5;y++)uses.add(new ShapeIndex.Use(ShapeKey.tmpl(0,sig,x,y,z),"PAL_A"));
        ShapeParams params=ShapeParams.v7();var index=ShapeIndex.of(params.planner(),params.toJson(),List.of(BandsTest.ATLAS),List.of("PAL_A"),uses,entries);
        for(boolean retained:List.of(true,false)){
            var table=ShapeTable.build(index,BandsTest.art(true),ShapeTable.NO_OVERRIDES,ShapeOverrides.NONE,retained);
            for(int z=0;z<2;z++)for(int x=0;x<2;x++)for(int y=0;y<5;y++){
                var mesh=table.mesh(index.shapeId(ShapeKey.tmpl(0,sig,x,y,z),"PAL_A"));assertNotNull(mesh,"A missing mesh would render a false native cube");
                if(y==0)assertTrue(mesh.size()>0);else assertEquals(0,mesh.size());
            }
        }
    }
}
