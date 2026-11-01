package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An explicit mixed-cliff model must not alter unrelated native columns or other games. */
class FittedTerrainPreservationTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}

    @Test void parserRequiresPartsTerrainAndBooleanTrueInEmeraldOnly(){
        for(String flag:List.of("true","false","\"true\"","1","null"))for(boolean terrain:List.of(false,true)){
            String json="{\"buildings\":[{\"atlas\":\"tilesets.fix\",\"tiles\":[[1,1],[1,1]],\"height\":16,\"parts\":[],\"terrain\":"+(terrain?"[[0,0]]":"[]")+",\"preserveTerrain\":"+flag+"}]}";
            List<FittedShapes.Building> buildings=new ArrayList<>();FittedShapes.parseBuildings(json,buildings);assertEquals(1,buildings.size());
            for(String game:List.of("emerald","blue","crystal")){String previous=FittedShapes.enter(game);
                try{assertEquals(game.equals("emerald")&&terrain&&flag.equals("true"),FittedShapes.preserveTerrainBuilding(buildings.get(0)));}
                finally{FittedShapes.leave(previous);}
            }
        }
        JsonObject legacy=new JsonObject();legacy.addProperty("preserveTerrain",true);legacy.add("terrain",JsonParser.parseString("[[0,0]]"));
        String previous=FittedShapes.enter("emerald");try{assertFalse(FittedShapes.preserveTerrainBuilding(new FittedShapes.Building("tilesets.fix",List.of(),legacy)));}finally{FittedShapes.leave(previous);}
    }

    @Test void restorationKeepsExactNativeProfileAndRefusesEveryProtectedCarrier(){
        SceneMap map=ShapeFixtures.surface("TERRAIN","AAAAAA..AAAA","AAAAAA..AAAA");int n=map.artCols()*map.artRows();
        ShapeKey old=ShapeKey.cube(0,1,2,3,4,5,6),changed=ShapeKey.cube(0,7,7,7,7,7,7);
        ShapeKey[][] oldKeys=new ShapeKey[n][],newKeys=new ShapeKey[n][];int[] oldBlocks=new int[n],newBlocks=new int[n],oldMask=new int[n],newMask=new int[n];
        for(int i=0;i<n;i++){oldKeys[i]=new ShapeKey[]{old,old,old};newKeys[i]=new ShapeKey[]{changed};oldBlocks[i]=3;newBlocks[i]=1;oldMask[i]=7;newMask[i]=1;}
        var source=new ShapePlanner.Columns(oldKeys,oldBlocks,oldMask);var result=new ShapePlanner.Columns(newKeys,newBlocks,newMask);boolean[] held=new boolean[n];
        held[1]=true;source.modelled()[2]=true;result.modelled()[3]=true;source.decor()[4]=1;
        source.keys()[5]=new ShapeKey[]{ShapeKey.cell(0,new int[]{1,1,1,1},1,0,0,0)};
        source.blocks()[8]=0;source.floor()[0]=27;source.hungMask()[0]=2;source.hungLeaves()[0]=4;source.hung()[0]=new ShapeKey[]{old};
        Map<String,Integer> tally=new TreeMap<>();var actual=ShapePlanner.preserveNativeTerrain(map,result,source,held,tally);
        assertArrayEquals(new ShapeKey[]{old,old,old},actual.keys()[0]);assertEquals(3,actual.blocks()[0]);assertEquals(7,actual.shapeMask()[0]);assertEquals(27,actual.floor()[0]);assertEquals(2,actual.hungMask()[0]);assertEquals(4,actual.hungLeaves()[0]);assertArrayEquals(source.hung()[0],actual.hung()[0]);
        for(int i:new int[]{1,2,3,4,5,6,7,8}){assertEquals(1,actual.blocks()[i],"protected column "+i);assertArrayEquals(new ShapeKey[]{changed},actual.keys()[i]);}
        assertTrue(tally.get("fitted_native_terrain_preserved")>0);assertEquals(CellKind.WALK,map.kindAtTile(6,0));
    }
}
