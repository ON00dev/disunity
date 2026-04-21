/*
 ** 2013 June 15
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.asset;

import info.ata4.io.DataReader;
import info.ata4.io.DataReaders;
import info.ata4.io.DataWriter;
import info.ata4.io.buffer.ByteBufferUtils;
import info.ata4.io.file.FileHandler;
import info.ata4.log.LogUtils;
import info.ata4.unity.rtti.ObjectData;
import info.ata4.unity.rtti.ObjectSerializer;
import info.ata4.unity.util.TypeTreeUtils;
import info.ata4.util.io.DataBlock;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.StandardOpenOption.READ;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FilenameUtils;


/**
 * Reader for Unity asset files.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetFile extends FileHandler {
    
    private static final Logger L = LogUtils.getLogger();
    
    private static final int METADATA_PADDING = 4096;
    
    // collection fields
    private final Map<Long, ObjectInfo> objectInfoMap = new LinkedHashMap<>();
    private final Map<Integer, BaseClass> typeTreeMap = new LinkedHashMap<>();
    private final List<FileIdentifier> externals = new ArrayList<>();
    private final List<ObjectData> objectList = new ArrayList<>();
    private final List<ObjectData> objectListBroken= new ArrayList<>();
    
    // struct fields
    private final VersionInfo versionInfo = new VersionInfo();
    private final AssetHeader header = new AssetHeader(versionInfo);
    private final ObjectInfoTable objectInfoStruct = new ObjectInfoTable(versionInfo, objectInfoMap);
    private final TypeTree typeTreeStruct = new TypeTree(versionInfo, typeTreeMap);
    private final FileIdentifierTable externalsStruct = new FileIdentifierTable(versionInfo, externals);
    
    // data block fields
    private final DataBlock headerBlock = new DataBlock();
    private final DataBlock objectInfoBlock = new DataBlock();
    private final DataBlock objectDataBlock = new DataBlock();
    private final DataBlock typeTreeBlock = new DataBlock();
    private final DataBlock externalsBlock = new DataBlock();
    
    // misc fields
    private ByteBuffer audioBuffer;
    
    @Override
    public void load(Path file) throws IOException {
        sourceFile = file;
        
        String fileName = file.getFileName().toString();
        String fileExt = FilenameUtils.getExtension(fileName);
        
        DataReader reader;
        
        // join split asset files before loading
        if (fileExt.startsWith("split")) {
            L.fine("Found split asset file");
            
            fileName = FilenameUtils.removeExtension(fileName);
            List<Path> parts = new ArrayList<>();
            int splitIndex = 0;

            // collect all files with .split0 to .splitN extension
            while (true) {
                String splitName = String.format("%s.split%d", fileName, splitIndex);
                Path part = file.resolveSibling(splitName);
                if (Files.notExists(part)) {
                    break;
                }
                
                L.log(Level.FINE, "Adding splinter {0}", part.getFileName());
                
                splitIndex++;
                parts.add(part);
            }
            
            // load all parts to one byte buffer
            reader = DataReaders.forByteBuffer(ByteBufferUtils.load(parts));
        } else {
            reader = DataReaders.forFile(file, READ);
        }
        
        // load audio buffer if existing        
        loadResourceStream(file.resolveSibling(fileName + ".streamingResourceImage"));
        loadResourceStream(file.resolveSibling(fileName + ".resS"));
        
        load(reader);
    }
    
    private void loadResourceStream(Path streamFile) throws IOException {
        if (Files.exists(streamFile)) {
            L.log(Level.FINE, "Found sound stream file {0}", streamFile.getFileName());
            audioBuffer = ByteBufferUtils.openReadOnly(streamFile);
        }
    }
      
    @Override
    public void load(DataReader in) throws IOException {
        try {
            loadHeader(in);

            if (header.version() >= 9) {
                in.order(header.endianness() == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            } else {
                long metadataStart = header.fileSize() - header.metadataSize();
                in.position(metadataStart);
                byte e = in.readByte();
                in.order(e == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            }

            if (header.version() >= 10) {
                loadMetadataModern(in);
            } else {
                // older formats store the object data before the structure data
                if (header.version() < 9) {
                    in.position(header.fileSize() - header.metadataSize() + 1);
                }
                loadMetadata(in);
            }
            loadObjects(in);
        } catch (EOFException ex) {
            throw new IOException("Unexpected end of file while parsing asset file (format version " + versionInfo.assetVersion() + ")", ex);
        }
    }
    
    private void loadMetadataModern(DataReader in) throws IOException {
        long metadataStart = in.position();

        if (header.version() >= 7) {
            String unityVersion = in.readStringNull();
            try {
                versionInfo.unityRevision(new info.ata4.unity.util.UnityVersion(unityVersion));
            } catch (Exception ex) {
            }
        }

        if (header.version() >= 8) {
            in.readInt();
        }

        boolean enableTypeTree = true;
        if (header.version() >= 13) {
            enableTypeTree = in.readBoolean();
        }

        typeTreeBlock.markBegin(in);
        int typeCount = in.readInt();
        List<Integer> classIDs = new ArrayList<Integer>(typeCount);

        TypeNodeReader nodeReader = new TypeNodeReader(versionInfo);
        for (int i = 0; i < typeCount; i++) {
            int classID = in.readInt();
            classIDs.add(classID);

            if (header.version() >= 16) {
                in.readBoolean();
            }

            if (header.version() >= 17) {
                in.readShort();
            }

            if (header.version() >= 13) {
                boolean readScriptID = (header.version() < 16 && classID < 0) || (header.version() >= 16 && classID == 114);
                if (readScriptID) {
                    in.readBytes(new byte[16]);
                }
                in.readBytes(new byte[16]);
            }

            BaseClass bc = new BaseClass();
            bc.classID(classID);

            if (enableTypeTree) {
                TypeNode node = new TypeNode();
                nodeReader.read(in, node);
                bc.typeTree(node);
            }

            typeTreeMap.put(classID, bc);

            if (header.version() >= 21) {
                int depCount = in.readInt();
                for (int d = 0; d < depCount; d++) {
                    in.readInt();
                }
            }
        }
        typeTreeBlock.markEnd(in);
        L.log(Level.FINER, "typeTreeBlock: {0}", typeTreeBlock);

        if (header.version() >= 7 && header.version() < 14) {
            in.readInt();
        }

        objectInfoBlock.markBegin(in);
        int objectCount = in.readInt();
        for (int i = 0; i < objectCount; i++) {
            long pathID;
            if (header.version() >= 7 && header.version() < 14) {
                pathID = in.readInt();
            } else {
                in.align(4);
                pathID = in.readLong();
            }

            long byteStart;
            if (header.version() >= 22) {
                byteStart = in.readLong();
            } else {
                byteStart = in.readUnsignedInt();
            }

            byteStart += header.dataOffset();
            long byteSize = in.readUnsignedInt();
            int typeID = in.readInt();

            int classID;
            if (header.version() < 16) {
                classID = in.readUnsignedShort();
            } else {
                classID = classIDs.get(typeID);
            }

            if (header.version() < 11) {
                in.readUnsignedShort();
            }

            if (header.version() >= 11 && header.version() < 17) {
                in.readShort();
            }

            if (header.version() == 15 || header.version() == 16) {
                in.readByte();
            }

            ObjectInfo info = new ObjectInfo(versionInfo);
            info.offset(byteStart - header.dataOffset());
            info.length(byteSize);
            info.typeID(typeID);
            info.classID(classID);
            objectInfoMap.put(pathID, info);
        }
        objectInfoBlock.markEnd(in);
        L.log(Level.FINER, "objectInfoBlock: {0}", objectInfoBlock);

        if (header.version() >= 11) {
            int scriptCount = in.readInt();
            for (int i = 0; i < scriptCount; i++) {
                in.readInt();
                if (header.version() < 14) {
                    in.readInt();
                } else {
                    in.align(4);
                    in.readLong();
                }
            }
        }

        externalsBlock.markBegin(in);
        int externalsCount = in.readInt();
        for (int i = 0; i < externalsCount; i++) {
            if (header.version() >= 6) {
                in.readStringNull();
            }
            if (header.version() >= 5) {
                in.readBytes(new byte[16]);
                in.readInt();
            }
            in.readStringNull();
        }

        if (header.version() >= 20) {
            int refTypesCount = in.readInt();
            for (int i = 0; i < refTypesCount; i++) {
                in.readInt();
                if (header.version() >= 16) {
                    in.readBoolean();
                }
                if (header.version() >= 17) {
                    in.readShort();
                }
                if (header.version() >= 13) {
                    in.readBytes(new byte[16]);
                    in.readBytes(new byte[16]);
                }
                if (enableTypeTree) {
                    TypeNode node = new TypeNode();
                    nodeReader.read(in, node);
                }
                if (header.version() >= 21) {
                    in.readStringNull();
                    in.readStringNull();
                    in.readStringNull();
                }
            }
        }

        if (header.version() >= 5) {
            in.readStringNull();
        }

        externalsBlock.markEnd(in);
        L.log(Level.FINER, "externalsBlock: {0}", externalsBlock);

        long metadataEnd = in.position();
        if (metadataEnd - metadataStart != header.metadataSize()) {
            L.log(Level.FINE, "Metadata size mismatch (header: {0}, parsed: {1})",
                    new Object[]{header.metadataSize(), metadataEnd - metadataStart});
        }
    }
    
    public void loadExternals() throws IOException {
        loadExternals(new HashMap<Path, AssetFile>());
    }
    
    private void loadExternals(Map<Path, AssetFile> loadedAssets) throws IOException {
        loadedAssets.put(sourceFile, this);
        
        for (FileIdentifier external : externals) {
            String filePath = external.filePath();

            if (filePath == null || filePath.isEmpty()) {
                continue;
            }

            filePath = filePath.replace("library/", "resources/");

            Path refFile = sourceFile.resolveSibling(filePath);
            if (Files.exists(refFile)) {
                AssetFile childAsset = loadedAssets.get(refFile);
                
                if (childAsset == null) {
                    L.log(Level.FINE, "Loading dependency {0} for {1}",
                            new Object[] {filePath, sourceFile.getFileName()});
                    childAsset = new AssetFile();
                    childAsset.load(refFile);
                    childAsset.loadExternals(loadedAssets);
                    external.assetFile(childAsset);
                }
            }
        }
    }
    
    private void loadHeader(DataReader in) throws IOException {
        headerBlock.markBegin(in);
        in.readStruct(header);
        headerBlock.markEnd(in);
        L.log(Level.FINER, "headerBlock: {0}", headerBlock);
    }
    
    private void loadMetadata(DataReader in) throws IOException {
        in.order(versionInfo.order());
        
        // read structure data
        typeTreeBlock.markBegin(in);
        in.readStruct(typeTreeStruct);
        typeTreeBlock.markEnd(in);
        L.log(Level.FINER, "typeTreeBlock: {0}", typeTreeBlock);
        
        objectInfoBlock.markBegin(in);
        in.readStruct(objectInfoStruct);
        objectInfoBlock.markEnd(in);
        L.log(Level.FINER, "objectInfoBlock: {0}", objectInfoBlock);
        
        // unknown block for Unity 5
        if (header.version() > 13) {
            in.align(4);
            int num = in.readInt();
            if (num != 0 && header.version() >= 15) { 
                // sorry, no info on that block => cant read externals. num -> num-9 worked for my experimental file, but I dont know about others
                return;
            } 
            for (int i = 0; i < num; i++) {
                in.readInt();
                in.readInt();
                in.readInt();
            }
        }

        externalsBlock.markBegin(in);
        in.readStruct(externalsStruct);
        externalsBlock.markEnd(in);
        L.log(Level.FINER, "externalsBlock: {0}", externalsBlock);
    }
    
    private void loadObjects(DataReader in) throws IOException {
        long ofsMin = Long.MAX_VALUE;
        long ofsMax = Long.MIN_VALUE;
        
        for (Map.Entry<Long, ObjectInfo> infoEntry : objectInfoMap.entrySet()) {
            ObjectInfo info = infoEntry.getValue();
            long id = infoEntry.getKey();
            
            ByteBuffer buf = ByteBufferUtils.allocate((int) info.length());
            
            long ofs = header.dataOffset() + info.offset();
            
            ofsMin = Math.min(ofsMin, ofs);
            ofsMax = Math.max(ofsMax, ofs + info.length());
            
            in.position(ofs);
            in.readBuffer(buf);
            
            TypeNode typeNode = null;
            
            int typeKey = header.version() >= 16 ? info.classID() : info.typeID();
            BaseClass typeClass = typeTreeMap.get(typeKey);
            if (typeClass != null) {
                typeNode = typeClass.typeTree();
            }
            
            // get type from database if the embedded one is missing
            if (typeNode == null) {
                typeNode = TypeTreeUtils.getTypeNode(info.unityClass(),
                        versionInfo.unityRevision(), false);
            }

            ObjectData data = new ObjectData(id, versionInfo);
            data.info(info);
            data.buffer(buf);
            data.typeTree(typeNode);
            
            ObjectSerializer serializer = new ObjectSerializer();
            serializer.setSoundData(audioBuffer);
            data.serializer(serializer);
            
            // Add typeless objects to an internal list. They can't be
            // (de)serialized, but can still be written to the file.
            if (typeNode == null) {
                // log warning if it's not a MonoBehaviour
                if (info.classID() != 114) {
                    L.log(Level.WARNING, "{0} has no type information!", data.toString());
                }
                objectListBroken.add(data);
            } else {
                objectList.add(data);
            }
        }
        
        objectDataBlock.offset(ofsMin);
        objectDataBlock.endOffset(ofsMax);
        L.log(Level.FINER, "objectDataBlock: {0}", objectDataBlock);
    }
    
    @Override
    public void save(DataWriter out) throws IOException {
        saveHeader(out);
        
        // write as little endian from now on
        out.order(ByteOrder.LITTLE_ENDIAN);
        
        // older formats store the object data before the structure data
        if (header.version() < 9) {
            header.dataOffset(0);
            
            saveObjects(out);
            out.writeUnsignedByte(0);
            
            saveMetadata(out);
            out.writeUnsignedByte(0);
        } else {
            saveMetadata(out);
            
            // original files have a minimum padding of 4096 bytes after the
            // metadata
            if (out.position() < METADATA_PADDING) {
                out.align(METADATA_PADDING);
            }
            
            out.align(16);
            header.dataOffset(out.position());
            
            saveObjects(out);
            
            // write updated path table
            out.position(objectInfoBlock.offset());
            out.writeStruct(objectInfoStruct);
        }
        
        // update header
        header.fileSize(out.size());
        
        // FIXME: the metadata size is slightly off in comparison to original files
        int metadataOffset = header.version() < 9 ? 2 : 1;
        
        header.metadataSize(typeTreeBlock.length()
                + objectInfoBlock.length()
                + externalsBlock.length()
                + metadataOffset);
        
        // write updated header
        out.order(ByteOrder.BIG_ENDIAN);
        out.position(headerBlock.offset());
        out.writeStruct(header);
             
        checkBlocks();
    }
    
    private void saveHeader(DataWriter out) throws IOException {
        headerBlock.markBegin(out);
        out.writeStruct(header);
        headerBlock.markEnd(out);
        L.log(Level.FINER, "headerBlock: {0}", headerBlock);
    }
    
    private void saveMetadata(DataWriter out) throws IOException {
        out.order(versionInfo.order());
        
        typeTreeBlock.markBegin(out);
        out.writeStruct(typeTreeStruct);
        typeTreeBlock.markEnd(out);
        L.log(Level.FINER, "typeTreeBlock: {0}", typeTreeBlock);

        objectInfoBlock.markBegin(out);
        out.writeStruct(objectInfoStruct);
        objectInfoBlock.markEnd(out);
        L.log(Level.FINER, "objectInfoBlock: {0}", objectInfoBlock);

        externalsBlock.markBegin(out);
        out.writeStruct(externalsStruct);
        externalsBlock.markEnd(out);
        L.log(Level.FINER, "externalsBlock: {0}", externalsBlock);
    }
    
    private void saveObjects(DataWriter out) throws IOException {
        long ofsMin = Long.MAX_VALUE;
        long ofsMax = Long.MIN_VALUE;
        
        // merge object lists
        objectList.addAll(objectListBroken);
        
        for (ObjectData data : objectList) {
            ByteBuffer bb = data.buffer();
            bb.rewind();
            
            out.align(8);
            
            ofsMin = Math.min(ofsMin, out.position());
            ofsMax = Math.max(ofsMax, out.position() + bb.remaining());
            
            ObjectInfo info = data.info();            
            info.offset(out.position() - header.dataOffset());
            info.length(bb.remaining());

            out.writeBuffer(bb);
        }
        
        // separate object lists
        objectList.removeAll(objectListBroken);
        
        objectDataBlock.offset(ofsMin);
        objectDataBlock.endOffset(ofsMax);
        L.log(Level.FINER, "objectDataBlock: {0}", objectDataBlock);
    }
    
    private void checkBlocks() {
        // sanity check for the data blocks
        assert !headerBlock.isIntersecting(typeTreeBlock);
        assert !headerBlock.isIntersecting(objectInfoBlock);
        assert !headerBlock.isIntersecting(externalsBlock);
        assert !headerBlock.isIntersecting(objectDataBlock);
        
        assert !typeTreeBlock.isIntersecting(objectInfoBlock);
        assert !typeTreeBlock.isIntersecting(externalsBlock);
        assert !typeTreeBlock.isIntersecting(objectDataBlock);
        
        assert !objectInfoBlock.isIntersecting(externalsBlock);
        assert !objectInfoBlock.isIntersecting(objectDataBlock);
        
        assert !objectDataBlock.isIntersecting(externalsBlock);
    }

    public VersionInfo versionInfo() {
        return versionInfo;
    }

    public AssetHeader header() {
        return header;
    }
    
    public int typeTreeAttributes() {
        return typeTreeStruct.attributes();
    }
    
    public Map<Integer, BaseClass> typeTree() {
        return typeTreeMap;
    }
    
    public Map<Long, ObjectInfo> objectInfoMap() {
        return objectInfoMap;
    }
    
    public List<ObjectData> objects() {
        return objectList;
    }
    
    public List<FileIdentifier> externals() {
        return externals;
    }

    public boolean isStandalone() {
        return !typeTreeStruct.embedded();
    }
    
    public void setStandalone() {
        typeTreeStruct.embedded(true);
    }
}
