package org.mapdb;

import java.io.DataInput;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WAL shared between {@link StoreWAL} and {@link StoreAppend}
 */
public class WriteAheadLog {

    /** 2 byte store version*/
    protected static final int WAL_STORE_VERSION = 100;

    /** 4 byte file header */
    protected static final int WAL_HEADER = (0x8A77<<16) | WAL_STORE_VERSION;


    protected static final long WAL_SEAL = 8234892392398238983L;

    protected static final int I_EOF = 0;
    protected static final int I_LONG = 1;
    protected static final int I_BYTE_ARRAY = 2;
    protected static final int I_SKIP_MANY = 3;
    protected static final int I_SKIP_SINGLE = 4;
    protected static final int I_RECORD = 5;
    protected static final int I_TOMBSTONE = 6;



    protected final long featureBitMap;

    protected final int pointerOffsetBites=32;
    protected final long pointerOffsetMask = DataIO.fillLowBits(pointerOffsetBites);
    protected final int pointerSizeBites=16;
    protected final long pointerSizeMask = DataIO.fillLowBits(pointerSizeBites);
    protected final int pointerFileBites=16;
    protected final long pointerFileMask = DataIO.fillLowBits(pointerFileBites);

    public WriteAheadLog(String fileName, Volume.VolumeFactory volumeFactory, long featureBitMap) {
        this.fileName = fileName;
        this.volumeFactory = volumeFactory;
        this.featureBitMap = featureBitMap;
    }

    public WriteAheadLog(String fileName) {
        this(
                fileName,
                fileName==null? CC.DEFAULT_MEMORY_VOLUME_FACTORY:CC.DEFAULT_FILE_VOLUME_FACTORY,
                0L
        );
    }


    public void initFailedCloseFiles() {
        if(walRec!=null){
            for(Volume v:walRec){
                if(v!=null && !v.isClosed())
                    v.close();
            }
            walRec.clear();
        }
        if(volumes!=null){
            for(Volume v:volumes){
                if(v!=null && !v.isClosed())
                    v.close();
            }
            volumes.clear();
        }
    }

    public void close() {
        for(Volume v:walRec){
            v.close();
        }
        walRec.clear();


        for(Volume v:volumes){
            v.close();
        }
        volumes.clear();
        curVol = null;
    }

    public void seal() {
        long finalOffset = walOffset.get();
        curVol.ensureAvailable(finalOffset+1); //TODO overlap here
        //put EOF instruction
        curVol.putUnsignedByte(finalOffset, (0<<4) | (Long.bitCount(finalOffset)&15));
        curVol.sync();
        //put wal seal
        curVol.putLong(8, WAL_SEAL);
        curVol.sync();
    }

    public void startNextFile() {
        fileNum++;
        if (CC.ASSERT && fileNum != volumes.size())
            throw new DBException.DataCorruption();
        String filewal = getWalFileName(""+fileNum);
        Volume nextVol = volumeFactory.makeVolume(filewal, false, true);

        nextVol.ensureAvailable(16);

        nextVol.putInt(0, WAL_HEADER);
        nextVol.putLong(8, featureBitMap);

        walOffset.set(16);
        volumes.add(nextVol);

        curVol = nextVol;
    }



    public interface WALReplay{

        void beforeReplayStart();

        void writeLong(long offset, long value);

        void writeRecord(long recid, byte[] data);

        //TODO direct transfer: Volume vol, long volOffset, int length
        void writeByteArray(long offset, byte[] val);

        void beforeDestroyWAL();

        void writeTombstone(long recid);
    }

    /** does nothing */
    public static final WALReplay NOREPLAY = new WALReplay() {
        @Override
        public void beforeReplayStart() {
        }

        @Override
        public void writeLong(long offset, long value) {
        }

        @Override
        public void writeRecord(long recid, byte[] data) {
        }

        @Override
        public void writeByteArray(long offset, byte[] val) {
        }

        @Override
        public void beforeDestroyWAL() {
        }

        @Override
        public void writeTombstone(long recid) {
        }
    };


    final String fileName;
    final Volume.VolumeFactory volumeFactory;


    //TODO how to protect concurrrently file offset when file is being swapped?
    protected final AtomicLong walOffset = new AtomicLong();

    protected final List<Volume> volumes = Collections.synchronizedList(new ArrayList<Volume>());


    /** record WALs, store recid-record pairs. Created during compaction when memory allocator is not available */
    protected final List<Volume> walRec = Collections.synchronizedList(new ArrayList<Volume>());

    protected Volume curVol;

    protected int fileNum = -1;

    void open(WALReplay replay){
        //replay WAL files
        String wal0Name = getWalFileName("0");
        String walCompSeal = getWalFileName("c");
        boolean walCompSealExists =
                walCompSeal!=null &&
                        new File(walCompSeal).exists();

        if(walCompSealExists ||
                (wal0Name!=null &&
                        new File(wal0Name).exists())){
            //fill compaction stuff

            for(int i=0;;i++){
                String rname = getWalFileName("r"+i);
                if(!new File(rname).exists())
                    break;
                walRec.add(volumeFactory.makeVolume(rname, false, true));
            }


            //fill wal files
            for(int i=0;;i++){
                String wname = getWalFileName(""+i);
                if(!new File(wname).exists())
                    break;
                volumes.add(volumeFactory.makeVolume(wname, false, true));
            }

            replayWAL(replay);

            for(Volume v:walRec){
                v.close();
            }
            walRec.clear();
            volumes.clear();
        }

    }

    void replayWAL(WALReplay replay){
        replay.beforeReplayStart();

        file:for(Volume wal:volumes){
            if(wal.length()<16 || wal.getLong(8)!=WAL_SEAL) {
                break file;
                //TODO better handling for corrupted logs
            }

            long pos = 16;
            for(;;) {
                int checksum = wal.getUnsignedByte(pos++);
                int instruction = checksum>>>4;
                checksum = (checksum&15);
                if (instruction == I_EOF) {
                    //EOF
                    if((Long.bitCount(pos-1)&15) != checksum)
                        throw new InternalError("WAL corrupted");
                    continue file;
                } else if (instruction == I_LONG) {
                    //write long
                    long val = wal.getLong(pos);
                    pos += 8;
                    long offset = wal.getSixLong(pos);
                    pos += 6;
                    if(((1+Long.bitCount(val)+Long.bitCount(offset))&15)!=checksum)
                        throw new InternalError("WAL corrupted");
                    replay.writeLong(offset,val);
                } else if (instruction == I_BYTE_ARRAY) {
                    //write byte[]
                    int dataSize = wal.getUnsignedShort(pos);
                    pos += 2;
                    long offset = wal.getSixLong(pos);
                    pos += 6;
                    byte[] data = new byte[dataSize];
                    wal.getData(pos, data, 0, data.length);
                    pos += data.length;
                    if(((1+Integer.bitCount(dataSize)+Long.bitCount(offset)+sum(data))&15)!=checksum)
                        throw new InternalError("WAL corrupted");
                    replay.writeByteArray(offset,data);
                } else if (instruction == I_SKIP_MANY) {
                    //skip N bytes
                    int skipN = wal.getInt(pos - 1) & 0xFFFFFF; //read 3 bytes
                    if((Integer.bitCount(skipN)&15) != checksum)
                        throw new InternalError("WAL corrupted");
                    pos += 3 + skipN;
                } else if (instruction == I_SKIP_SINGLE) {
                    //skip single byte
                    if((Long.bitCount(pos-1)&15) != checksum)
                        throw new InternalError("WAL corrupted");
                } else if (instruction == I_RECORD) {
                    // read record
                    long recid = wal.getPackedLong(pos);
                    pos += recid >>> 60;
                    recid &= DataIO.PACK_LONG_RESULT_MASK;

                    long size = wal.getPackedLong(pos);
                    pos += size >>> 60;
                    size &= DataIO.PACK_LONG_RESULT_MASK;

                    if (size == 0) {
                        replay.writeRecord(recid, null);
                    } else {
                        size--; //zero is used for null
                        byte[] data = new byte[(int) size];
                        wal.getData(pos, data, 0, data.length);
                        pos += size;
                        replay.writeRecord(recid, data);
                    }
                }else if (instruction == I_TOMBSTONE){
                    long recid = wal.getPackedLong(pos);
                    pos += recid >>> 60;
                    recid &= DataIO.PACK_LONG_RESULT_MASK;
                    replay.writeTombstone(recid);
                }else{
                    throw new InternalError("WAL corrupted, unknown instruction");
                }

            }
        }

        replay.beforeDestroyWAL();

        //destroy old wal files
        for(Volume wal:volumes){
            if(!wal.isClosed()) {
                wal.truncate(0);
                wal.close();
            }
            wal.deleteFile();

        }
        fileNum = -1;
        curVol = null;
        volumes.clear();
    }

    protected String getWalFileName(String ext) {
        return fileName==null? null :
                fileName+".wal"+"."+ext;
    }


    public long getNumberOfFiles(){
        return volumes.size();
    }

    /**
     * Retrieve {@code DataInput} from WAL. This data were written by {@link WriteAheadLog#walPutByteArray(long, byte[], int, int)}
     *
     * @param walPointer pointer returned by {@link WriteAheadLog#walPutByteArray(long, byte[], int, int)}
     * @return DataInput
     */
    public DataInput walGetByteArray(long walPointer) {
        int arraySize = (int) ((walPointer >>> (pointerOffsetBites+pointerFileBites))&pointerSizeMask);
        int fileNum = (int) ((walPointer >>> (pointerOffsetBites)) & pointerFileMask);
        long dataOffset = (walPointer & pointerOffsetMask);

        Volume vol = volumes.get(fileNum);
        return vol.getDataInput(dataOffset, arraySize);
    }


    /**
     * Retrieve {@code byte[]} from WAL. This data were written by {@link WriteAheadLog#walPutByteArray(long, byte[], int, int)}
     *
     * @param walPointer pointer returned by {@link WriteAheadLog#walPutByteArray(long, byte[], int, int)}
     * @return DataInput
     */
    public byte[] walGetByteArray2(long walPointer) {
        int arraySize = (int) ((walPointer >>> (pointerOffsetBites+pointerFileBites))&pointerSizeMask);
        int fileNum = (int) ((walPointer >>> (pointerOffsetBites)) & pointerFileMask);
        long dataOffset = (walPointer & pointerOffsetMask);

        Volume vol = volumes.get(fileNum);
        byte[] ret = new byte[arraySize];
        vol.getData(dataOffset, ret, 0, arraySize);
        return ret;
    }

    //TODO return DataInput
    public byte[] walGetRecord(long walPointer) {
        int fileNum = (int) ((walPointer >>> (pointerOffsetBites)) & pointerFileMask);
        long dataOffset = (walPointer & pointerOffsetMask);

        Volume vol = volumes.get(fileNum);
        //skip instruction
        //TODO verify it is 7
        //TODO verify checksum
        dataOffset++;

        long recid = vol.getPackedLong(dataOffset);
        dataOffset += recid >>> 60;
        recid &= DataIO.PACK_LONG_RESULT_MASK;

        long size = vol.getPackedLong(dataOffset);
        dataOffset += size >>> 60;
        size &= DataIO.PACK_LONG_RESULT_MASK;

        if (size == 0) {
            return null;
        }else if(size==1){
            return new byte[0];
        }else {
            size--; //zero is used for null
            byte[] data = new byte[(int) size];
            vol.getData(dataOffset, data, 0, data.length);
            return data;
        }
    }


    /**
     * Puts instruction into WAL. It should write part of {@code byte[]} at given offset.
     * This value returns pointer to WAL, which can be used to retrieve data back with {@link WriteAheadLog#walGetByteArray(long)}.
     * Pointer is composed of file number, and offset in WAL file.
     *
     * @param offset where data will be written in main store, after WAL replay (6 bytes)
     * @param buf byte array of data
     * @param bufPos starting position within byte array
     * @param size number of bytes to take from byte array
     * @return
     */
    public long walPutByteArray(long offset, byte[] buf, int bufPos, int size){
        final int plusSize = +1+2+6+size;
        long walOffset2 = walOffset.getAndAdd(plusSize);

        if(hadToSkip(walOffset2, plusSize)){
            return walPutByteArray(offset,buf,bufPos,size);
        }

        curVol.ensureAvailable(walOffset2+plusSize);
        int checksum = 1+Integer.bitCount(size)+Long.bitCount(offset)+sum(buf,bufPos,size);
        checksum &= 15;
        curVol.putUnsignedByte(walOffset2, (I_BYTE_ARRAY << 4)|checksum);
        walOffset2+=1;
        if(CC.ASSERT && (size&0xFFFF)!=size)
            throw new AssertionError();
        curVol.putLong(walOffset2, ((long) size) << 48 | offset);
        walOffset2+=8;
        curVol.putData(walOffset2, buf,bufPos,size);

        if(CC.ASSERT && (size&pointerSizeMask)!=size)
            throw new AssertionError();
        if(CC.ASSERT && (fileNum&pointerFileMask)!=fileNum)
            throw new AssertionError();
        if(CC.ASSERT && (walOffset2&pointerOffsetMask)!=walOffset2)
            throw new AssertionError();

        long val = ((long)size)<<(pointerOffsetBites+pointerFileBites);
        val |= ((long)fileNum)<<(pointerOffsetBites);
        val |= walOffset2;

        return val;
    }

    public long walPutRecord(long recid, byte[] buf, int bufPos, int size){
        if(CC.ASSERT && buf==null && size!=0)
            throw new AssertionError();
        long sizeToWrite = buf==null?0:(size+1);
        final int plusSize = +1+ DataIO.packLongSize(recid)+DataIO.packLongSize(sizeToWrite)+size;
        long walOffset2 = walOffset.getAndAdd(plusSize);
        long startPos = walOffset2;

        if(hadToSkip(walOffset2, plusSize)){
            return walPutRecord(recid,buf,bufPos,size);
        }

        curVol.ensureAvailable(walOffset2+plusSize);
        int checksum = 1;//+Integer.bitCount(size)+Long.bitCount(recid)+sum(buf,bufPos,size);
        checksum &= 15;
        curVol.putUnsignedByte(walOffset2, (I_RECORD << 4)|checksum);
        walOffset2+=1;

        walOffset2+=curVol.putPackedLong(walOffset2, recid);
        walOffset2+=curVol.putPackedLong(walOffset2, sizeToWrite);

        if(buf!=null) {
            curVol.putData(walOffset2, buf, bufPos, size);
        }

        long val = ((long)fileNum)<<(pointerOffsetBites);
        val |= startPos;

        return val;
    }


    /**
     * Put 8 byte long into WAL.
     *
     * @param offset where data will be written in main store, after WAL replay (6 bytes)
     * @param value
     */
    protected void walPutLong(long offset, long value){
        final int plusSize = +1+8+6;
        long walOffset2 = walOffset.getAndAdd(plusSize);

        Volume curVol2 = curVol;

        //in case of overlap, put Skip Bytes instruction and try again
        if(hadToSkip(walOffset2, plusSize)){
            walPutLong(offset, value);
            return;
        }

        if(CC.ASSERT && offset>>>48!=0)
            throw new DBException.DataCorruption();
        curVol2.ensureAvailable(walOffset2+plusSize);
        int parity = 1+Long.bitCount(value)+Long.bitCount(offset);
        parity &=15;
        curVol2.putUnsignedByte(walOffset2, (I_LONG << 4)|parity);
        walOffset2+=1;
        curVol2.putLong(walOffset2, value);
        walOffset2+=8;
        curVol2.putSixLong(walOffset2, offset);
    }

    protected boolean hadToSkip(long walOffset2, int plusSize) {
        //does it overlap page boundaries?
        if((walOffset2>>>CC.VOLUME_PAGE_SHIFT)==(walOffset2+plusSize)>>>CC.VOLUME_PAGE_SHIFT){
            return false; //no, does not, all fine
        }

        //is there enough space for 4 byte skip N bytes instruction?
        while((walOffset2&StoreWAL.PAGE_MASK) >= StoreWAL.PAGE_SIZE-4 || plusSize<5){
            //pad with single byte skip instructions, until end of page is reached
            int singleByteSkip = (I_SKIP_SINGLE<<4)|(Long.bitCount(walOffset2)&15);
            curVol.putUnsignedByte(walOffset2++, singleByteSkip);
            plusSize--;
            if(CC.ASSERT && plusSize<0)
                throw new DBException.DataCorruption();
        }

        //now new page starts, so add skip instruction for remaining bits
        int val = (I_SKIP_MANY<<(4+3*8)) | (plusSize-4) | ((Integer.bitCount(plusSize-4)&15)<<(3*8));
        curVol.ensureAvailable(walOffset2 + 4);
        curVol.putInt(walOffset2, val);

        return true;
    }

    static int sum(byte[] data) {
        int ret = 0;
        for(byte b:data){
            ret+=b;
        }
        return Math.abs(ret);
    }

    static int sum(byte[] buf, int bufPos, int size) {
        int ret = 0;
        size+=bufPos;
        while(bufPos<size){
            ret+=buf[bufPos++];
        }
        return Math.abs(ret);
    }


}
