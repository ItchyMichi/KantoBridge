package dev.kantobridge.builder.shape;

import com.google.gson.*;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapLayout;
import org.jetbrains.annotations.Nullable;

/** Exact source-level witness for independent parts on a stepped native forest. */
final class FittedCarrierLevels {
	private FittedCarrierLevels() { }
	static boolean valid(SceneMap map, Buildings.Claim claim, JsonObject box, @Nullable MapLayout layout) {
		int per=map.perCell(),w=claim.width()/per,d=claim.rows()/per,min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
		int[][] actual=new int[d][w];
		for(int z=0;z<d;z++)for(int x=0;x<w;x++){
			boolean own=claim.claimed()[z*per*claim.width()+x*per];
			for(int dz=0;dz<per;dz++)for(int dx=0;dx<per;dx++)if(claim.claimed()[(z*per+dz)*claim.width()+x*per+dx]!=own)return false;
			actual[z][x]=own?(layout==null?0:layout.level(claim.tx()/per+x,claim.ty()/per+z)):-1;
			if(own){min=Math.min(min,actual[z][x]);max=Math.max(max,actual[z][x]);}
		}
		if(!box.has("carrierLevels"))return min==max;
		JsonElement value=box.get("carrierLevels");if(!value.isJsonArray()||value.getAsJsonArray().size()!=d)return false;
		int[][] expected=new int[d][w];boolean zero=false;
		for(int z=0;z<d;z++){
			JsonElement line=value.getAsJsonArray().get(z);if(!line.isJsonArray()||line.getAsJsonArray().size()!=w)return false;
			for(int x=0;x<w;x++){
				JsonElement level=line.getAsJsonArray().get(x);
				if(actual[z][x]<0){if(!level.isJsonNull())return false;expected[z][x]=-1;continue;}
				if(!level.isJsonPrimitive()||!level.getAsJsonPrimitive().isNumber())return false;
				double n=level.getAsDouble();if(n<0||n>30||n!=Math.rint(n))return false;expected[z][x]=(int)n;zero|=n==0;
				// Existing flat-map behavior remains valid. Raised maps must match the
				// complete source witness up to one common vertical translation.
				if(min!=max&&actual[z][x]-min!=expected[z][x])return false;
			}
		}
		if(!zero||!box.has("parts")||!box.get("parts").isJsonArray()||box.getAsJsonArray("parts").isEmpty())return false;
		for(JsonElement part:box.getAsJsonArray("parts")){
			if(!part.isJsonObject())return false;double[] bounds=bounds(part.getAsJsonObject());if(bounds==null)return false;
			for(int z=0;z<d;z++)for(int x=0;x<w;x++){
				if(expected[z][x]<0)continue;
				if(x+1<w&&expected[z][x+1]>=0&&expected[z][x]!=expected[z][x+1]
						&&bounds[2]<16*(z+1)&&bounds[3]>16*z&&bounds[0]<16*(x+1)+1&&bounds[1]>16*(x+1)-1)return false;
				if(z+1<d&&expected[z+1][x]>=0&&expected[z][x]!=expected[z+1][x]
						&&bounds[0]<16*(x+1)&&bounds[1]>16*x&&bounds[2]<16*(z+1)+1&&bounds[3]>16*(z+1)-1)return false;
			}
		}
		return true;
	}
	private static @Nullable double[] bounds(JsonObject part) {
		try {
			if(part.has("carve")||!part.has("shape"))return null;
			String shape=part.get("shape").getAsString();double[] result;
			if(shape.equals("box")){
				var x=part.getAsJsonArray("x");var z=part.getAsJsonArray("z");if(x.size()!=2||z.size()!=2)return null;
				result=new double[]{x.get(0).getAsDouble(),x.get(1).getAsDouble(),z.get(0).getAsDouble(),z.get(1).getAsDouble()};
			}else if(shape.equals("cone")||shape.equals("dome")||shape.equals("cylinder")){
				var center=part.getAsJsonArray("center");if(center.size()!=2)return null;JsonElement radius=part.get("radius");
				if(radius.isJsonArray()&&radius.getAsJsonArray().size()!=2)return null;
				double rx=radius.isJsonArray()?radius.getAsJsonArray().get(0).getAsDouble():radius.getAsDouble(),rz=radius.isJsonArray()?radius.getAsJsonArray().get(1).getAsDouble():radius.getAsDouble();
				if(rx<=0||rz<=0)return null;double x=center.get(0).getAsDouble(),z=center.get(1).getAsDouble();result=new double[]{x-rx,x+rx,z-rz,z+rz};
			}else return null;
			for(double n:result)if(!Double.isFinite(n))return null;
			return result[0]<result[1]&&result[2]<result[3]?result:null;
		}catch(RuntimeException invalid){return null;}
	}
}
