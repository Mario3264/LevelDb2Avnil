package com.ljyloo.PE2PC;

import java.util.*;

import com.mojang.nbt.CompoundTag;
import com.mojang.nbt.ListTag;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.OldDataLayer;

class ConvertSource {
	
	//Data2D
	private final static int HEIGHTMAP_LENGTH = 256;
	private final static int BIOMEDATA_LENGTH = 256;
	//SubChunk
	private final static int DATALAYER_BITS = 4;
	private final static int BLOCKDATA_BYTES = 4096;
	private final static int METADATA_BYTES = 2048;
	private final static int SKYLIGHTDATA_BYTES = 2048;
	private final static int BLOCKLIGHTDATA_BYTES = 2048;
	
	private Chunk current;
	private HashMap<String, Chunk> comChunks = new HashMap<>();
	
	public Chunk createChunkIfNotExists(int xPos, int zPos){
		String comKey = xPos+","+zPos;
		if (!comChunks.containsKey(comKey)){
			//System.out.println("New comChunks");
			Chunk chunk = this.new Chunk(xPos, zPos);
			
			comChunks.put(comKey, chunk);
			return chunk;
		}else{
			return comChunks.get(comKey);
		}
	}
	
	public CompoundTag createSectionIfNotExists(int chunkY){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		else if(chunkY < 0){
			throw new IllegalArgumentException("chunkY < 0");
		}
		
		CompoundTag section;
		@SuppressWarnings("unchecked")
		ListTag<CompoundTag> sections = (ListTag<CompoundTag>)current.level.getList("Sections");
		
		for(int i=0;i<sections.size();i++){
			byte y = sections.get(i).getByte("Y");
			if(chunkY == y){
				section = sections.get(i);
				return section;
			}
		}
		
		//Create new empty section
		section = new CompoundTag();
		section.putByte("Y", (byte)(chunkY & 0xFF));
		section.putByteArray("Blocks", new byte[BLOCKDATA_BYTES]);
		section.putByteArray("Data", new byte[METADATA_BYTES]);
		section.putByteArray("SkyLight", new byte[SKYLIGHTDATA_BYTES]);
		section.putByteArray("BlockLight", new byte[BLOCKLIGHTDATA_BYTES]);
		
		sections.add(section);
		
		return section;
	}
	
	public void setCurrent(int xPos, int zPos){
		this.current = createChunkIfNotExists(xPos, zPos);
	}
	
	/*
	 * Convert HeightMap and Biomes
	 * 
	 * Not sure every biome are shared by PC and PE.
	 * So I just store them directly.
	 */
	public void convertData2D(byte[] value ){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		//Convert HeightMap
		byte[] heightData = new byte[HEIGHTMAP_LENGTH<<1];
		
		int offset = 0;
		System.arraycopy(value, offset, heightData, 0, heightData.length);
		offset += heightData.length;
		
		// byte array to int array
		int[] height = new int[256];
		
		for(int i=0;i<HEIGHTMAP_LENGTH;i++){
			//(i+1)*2-1 = 2*i+1 (use 2*i , 2*i+1)
			height[i] = heightData[2*i+1]<<8 | heightData[2*i];
		}
		
		current.level.putIntArray("HeightMap",height);
		
		//Convert Biomes
		byte[] biomes = new byte[BIOMEDATA_LENGTH];
		
		System.arraycopy(value, offset, biomes, 0, biomes.length);
		offset += biomes.length;
		
		current.level.putByteArray("Biomes", biomes);

	}
	
	/*
	 * Convert everything about subChunk but block light and sky light
	 */
	public void convertSubChunk(int chunkY , byte[] value){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		CompoundTag section = createSectionIfNotExists(chunkY);
		
		//BlockID, BlockData
		byte[] oldBlock = new byte[BLOCKDATA_BYTES];
		OldDataLayer oldMeta = new OldDataLayer(METADATA_BYTES << 1, DATALAYER_BITS);
		
		//Get data
		int offset = 1;
		System.arraycopy(value, offset, oldBlock, 0, oldBlock.length);
		offset += oldBlock.length;
		System.arraycopy(value, offset, oldMeta.data, 0, oldMeta.data.length);
		offset += oldMeta.data.length;
		
		//Converted array and DataLayers
		byte[] block = new byte[BLOCKDATA_BYTES];
		
		DataLayer meta = new DataLayer(METADATA_BYTES << 1, DATALAYER_BITS);
		
		//XZY -> YZX
		for(byte x=0;x<16;x++){
			for(byte y=0;y<16;y++){
				for(byte z=0;z<16;z++){
					byte[] converted = 
							UnsharedData.blockFilter(oldBlock[ (x << 8) | ( z << 4) | y], (byte)(oldMeta.get(x, y, z) & 0xff));

					block[(y << 8) | (z << 4) | x] = converted[0];
					meta.set(x, y, z, converted[1]);
				}
			}
		}
		
		//Light data 
		// will be recalculated by Minecraft.
		
		//Put tag
		section.putByteArray("Blocks", block);
		section.putByteArray("Data", meta.data);
		
		current.legacy = false;
	}
	
	public void convertLegacyTerrain(byte[] value){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		if(!current.legacy) return;
		
		//Get the full chunk data
		byte[][] oldData = {
				new byte[BLOCKDATA_BYTES<<3],//Block ID
				new byte[METADATA_BYTES<<3],//Block Data
				new byte[SKYLIGHTDATA_BYTES<<3],//Sky Light
				new byte[BLOCKLIGHTDATA_BYTES<<3]//Block Light
		};
		
		int offset = 0;
		for(byte[] array : oldData) {
			System.arraycopy(value, offset, array, 0, array.length);
			offset += array.length;
		}
		
		//Converted array and DataLayer
		byte[] chunkBlock = new byte[BLOCKDATA_BYTES<<3];
		byte[] chunkMeta = new byte[METADATA_BYTES<<3];
		byte[] chunkSkylight = new byte[SKYLIGHTDATA_BYTES<<3];
		byte[] chunkBlocklight = new byte[BLOCKLIGHTDATA_BYTES<<3];
		
		//height map
		int[] heightData = new int[HEIGHTMAP_LENGTH];
		
		//Convert full chunk
		//XZY -> YZX
		for(int x=0;x<16;x++) {
			for(int z=0;z<16;z++) {
				boolean highest = true;
				for(int y=127;y>=0;y--) {
					
					boolean part = (x%2 == 1);
					boolean oldPart = (y%2 == 1);
					int pos = (y<<8) | (z<<4) | x;
					int oldPos = (x<<11) | (z<<7) | y;
					
					//Get nibble Data
					int meta,skylight,blocklight;
					if(oldPart) {
						meta = oldData[1][oldPos/2] >> 4;
						skylight = oldData[2][oldPos>>1] >> 4;
						blocklight = oldData[3][oldPos>>1] >> 4;
					}else {
						meta = oldData[1][oldPos/2] & 0xf;
						skylight = oldData[2][oldPos>>1] & 0xf;
						blocklight = oldData[3][oldPos>>1] & 0xf;
					}
					
					byte[] block = UnsharedData.blockFilter(oldData[0][oldPos], (byte)meta);
					
					//Store Nibble Data
					if(part) {
						chunkMeta[pos>>1] = (byte)((chunkMeta[pos>>1] & 0x0f) | (block[1] << 4));
						chunkSkylight[pos>>1] = (byte)((chunkSkylight[pos>>1] & 0x0f) | (skylight << 4));
						chunkBlocklight[pos>>1] = (byte)((chunkBlocklight[pos>>1] & 0x0f) | (blocklight << 4));
					}else {
						chunkMeta[pos>>1] = (byte)((chunkMeta[pos>>1] & 0xf0) | (block[1] & 0x0f));
						chunkSkylight[pos>>1] = (byte)((chunkSkylight[pos>>1] & 0xf0) | (skylight & 0x0f));
						chunkBlocklight[pos>>1] = (byte)((chunkBlocklight[pos>>1] & 0xf0) | (blocklight & 0x0f));
					}
					
					//Store Block ID
					chunkBlock[pos] = block[0];
					
					//check highest
					if(highest) {
						if(chunkBlock[pos] != 0) {
							heightData[(x<<4)|z] = y;
							highest = false;
						}
					}
					
				}
			}
		}
		
		//Split
		for(int chunkY=0;chunkY<8;chunkY++) {
			byte[] blocks = new byte[BLOCKDATA_BYTES];
			System.arraycopy(chunkBlock, BLOCKDATA_BYTES*chunkY, blocks, 0, BLOCKDATA_BYTES);
			
			//find non-air
			boolean allAir = true;
			for(byte b:blocks) {
				if(b!=0) {
					allAir = false;
					break;
				}
			}
			
			if(allAir) continue;
			
			byte[] meta = Arrays.copyOfRange(chunkMeta, chunkY*METADATA_BYTES, (chunkY+1)*METADATA_BYTES);
			byte[] skylight = Arrays.copyOfRange(chunkSkylight, chunkY*SKYLIGHTDATA_BYTES, (chunkY+1)*SKYLIGHTDATA_BYTES);
			byte[] blocklight = Arrays.copyOfRange(chunkSkylight, chunkY*BLOCKLIGHTDATA_BYTES, (chunkY+1)*BLOCKLIGHTDATA_BYTES);
			
			CompoundTag section = createSectionIfNotExists(chunkY);
			section.putByteArray("Blocks", blocks);
			section.putByteArray("Data", meta);
			section.putByteArray("SkyLight", skylight);
			section.putByteArray("BlockLight", blocklight);
		}
		
		current.level.putIntArray("HeightMap",heightData);
		
	}
	
	public void convertData2DLegacy(byte[] value){}
	
	public void convertBlockEntity(byte[] value){}
	
	public void convertEntity(byte[] value){}
	
	public void convertSonw(byte[] value){}
	
	public int[] getCurrent(){
		return new int[]{current.level.getInt("xPos"), current.level.getInt("zPos")};
	}
	
	public List<CompoundTag> getConvertedChunks(){
		List<CompoundTag> com = new ArrayList<>();
		comChunks.values().iterator().forEachRemaining(x -> com.add(x.root));
		return com;
	}
	
	class Chunk{
		
		boolean legacy = true;
		
		CompoundTag root;
		CompoundTag level;
		
		public Chunk(int chunkX, int chunkZ){
			root = new CompoundTag();
			level = new CompoundTag();
			
			root.put("Level", level);
			level.putByte("LightPopulated", (byte)0);
			level.putByte("TerrainPopulated", (byte)1);
			level.putByte("V", (byte)1);
			level.putInt("xPos", chunkX);
			level.putInt("zPos", chunkZ);
			level.putLong("InhabitedTime", 0);
			level.putLong("LastUpdate", 0);
			
			ListTag<CompoundTag> sectionTags = new ListTag<CompoundTag>("Sections");
			level.put("Sections", sectionTags);
			
			/*
			level.putByteArray("Biomes", biomes);
			
			level.put("Entities", new ListTag<CompoundTag>("Entities"));
			
			level.put("TileEntities", new ListTag<CompoundTag>("TileEntities"));
			*/	
		}
	}
}
