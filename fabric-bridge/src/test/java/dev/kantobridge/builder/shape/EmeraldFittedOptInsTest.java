package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Explicit opt-ins cannot replace unrelated native columns or legacy game behavior. */
class EmeraldFittedOptInsTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static String key(char c){int n=ShapeFixtures.tile(c);return FittedShapes.key(ShapeFixtures.ATLAS,new int[]{n,n,n,n});}
    static Map<String,FittedShapes.Entry> entries(){
        Map<String,FittedShapes.Entry> out=new HashMap<>();
        for(char c:"ABCUV".toCharArray()){byte[] bits=new byte[FittedShapes.VOXEL_BYTES];bits[0]=1;out.put(key(c),new FittedShapes.Entry("voxels",List.of(ShapeFixtures.tile(c)),bits));}
        return out;
    }
    record Result(SceneMap map,ShapeKey[][] keys,int[] blocks,int[] mask,List<Units.Cell> units) {}
    static Result pair(String game,boolean flag,boolean alias,String obstacle){
        SceneMap map=ShapeFixtures.surface("ALIAS","AA..UU","AA..UU","BB..VV","BB..VV","CC....","CC....");
        int size=map.artCols()*map.artRows();ShapeKey[][] keys=new ShapeKey[size][];int[] blocks=new int[size],mask=new int[size],floor=new int[size],decor=new int[size];boolean[] held=new boolean[size];
        for(int y=0;y<map.artRows();y++)for(int x=0;x<map.artCols();x++)if(map.kindAtTile(x,y)==CellKind.SOLID){
            int i=y*map.artCols()+x,n=map.tile(x,y);ShapeKey cube=ShapeKey.cube(0,n,n,n,n,n,n);keys[i]=new ShapeKey[]{cube,cube,cube};blocks[i]=3;mask[i]=7;
        }
        Map<String,FittedShapes.Entry> cells=entries();Map<String,List<FittedShapes.Layer>> layers=new HashMap<>();Set<String> walks=new HashSet<>();
        Map<String,String> stacks=new HashMap<>(Map.of(key('A'),key(alias?'C':'B'),key('U'),key('C')));
        if(obstacle.equals("layer"))layers.put(key('B'),List.of(new FittedShapes.Layer(new byte[FittedShapes.VOXEL_BYTES],null)));
        if(obstacle.equals("missing"))cells.remove(key('B'));
        if(obstacle.equals("claimed"))held[0]=true;
        if(obstacle.equals("walk"))walks.add(key('A'));
        if(obstacle.equals("continuing"))stacks.put(key('B'),key('C'));
        if(obstacle.equals("decor"))decor[0]=1;
        FittedShapes.Table table=new FittedShapes.Table(cells,Map.of(),stacks,Set.of(),layers,List.of(),walks,
                Map.of(key('A'),alias?Set.of(key('B')):Set.of(),key('U'),Set.of(key('V'))),Set.of(),flag?Set.of(key('A')):Set.of());
        FittedShapes.useTablesForTest(Map.of(game,table));String previous=FittedShapes.enter(game);List<Units.Cell> units=new ArrayList<>();
        try{Units.plan(map,ShapeFixtures.TIER_B,0,keys,blocks,mask,floor,decor,units,null,null,new TreeMap<>(),held);}
        finally{FittedShapes.leave(previous);}
        return new Result(map,keys,blocks,mask,units);
    }
    @Test void onlyExplicitMatchedAliasPairReplacesTheNativeRoofColumns(){
        Result result=pair("emerald",true,true,"");int base=2*result.map().artCols();
        assertNull(result.keys()[0]);assertEquals(0,result.blocks()[0]);assertEquals(4,result.blocks()[base]);
        assertEquals(ShapeFixtures.tile('B'),result.keys()[base][0].number(0));assertEquals(ShapeFixtures.tile('A'),result.keys()[base][2].number(0));
        assertEquals(3,result.blocks()[4]);assertEquals(3,result.blocks()[base+4]);assertEquals(7,result.mask()[4]);
        assertEquals(CellKind.SOLID,result.map().kind(0,0));assertEquals(CellKind.SOLID,result.map().kind(0,1));
    }
    @Test void unflaggedPrimaryOnlyOtherGamesAndUnsafePairsKeepNativeColumns(){
        for(String game:List.of("blue","crystal")){Result r=pair(game,true,true,"");assertEquals(3,r.blocks()[0]);assertEquals(3,r.blocks()[2*r.map().artCols()]);}
        for(boolean alias:List.of(false,true)){Result r=pair("emerald",!alias,alias,"");assertEquals(3,r.blocks()[0]);assertEquals(3,r.blocks()[2*r.map().artCols()]);}
        for(String obstacle:List.of("claimed","missing","walk","continuing","decor")){
            Result r=pair("emerald",true,true,obstacle);assertEquals(3,r.blocks()[0],obstacle);assertEquals(3,r.blocks()[2*r.map().artCols()],obstacle);
        }
        Result layered=pair("emerald",true,true,"layer");assertEquals(3,layered.blocks()[0]);assertNotNull(layered.keys()[0]);
        // A layered base remains its normal independent layered model, never a truncated pair.
        assertEquals(4,layered.blocks()[2*layered.map().artCols()]);
    }
    @Test void stackFitAcceptsOnlyBooleanTrueOnVoxelEntries(){
        Set<String> flags=new HashSet<>();FittedShapes.parseStackFits("""
            {"cells":{"yes":{"class":"voxels","stackFit":true},"false":{"class":"voxels","stackFit":false},
             "text":{"class":"voxels","stackFit":"true"},"number":{"class":"voxels","stackFit":1},
             "other":{"class":"columns","stackFit":true}}}
            """,flags);assertEquals(Set.of("yes"),flags);
    }
    @Test void interiorTemplatesRequireBooleanOptInAndEmerald(){
        int n=ShapeFixtures.tile('A');SceneMap map=ShapeFixtures.interior("ROOM","AA","AA");
        for(String flag:List.of("true","false","\"true\"","1")){
            String json="{\"buildings\":[{\"atlas\":\""+ShapeFixtures.ATLAS+"\",\"tiles\":[["+n+","+n+"],["+n+","+n+"]],\"height\":16,\"terrain\":[],\"parts\":[],\"interior\":"+flag+"}]}";
            List<FittedShapes.Building> buildings=new ArrayList<>();FittedShapes.parseBuildings(json,buildings);assertEquals(1,buildings.size());
            for(String game:List.of("emerald","blue","crystal")){
                FittedShapes.useTablesForTest(Map.of(game,new FittedShapes.Table(Map.of(),Map.of(),Map.of(),Set.of(),Map.of(),buildings)));
                String previous=FittedShapes.enter(game);
                try{var claims=FittedBuildings.claims(map,new boolean[4],new TreeMap<>(),FittedBuildings.index());assertEquals(game.equals("emerald")&&flag.equals("true")?1:0,claims.size());}
                finally{FittedShapes.leave(previous);}
            }
        }
    }
}
