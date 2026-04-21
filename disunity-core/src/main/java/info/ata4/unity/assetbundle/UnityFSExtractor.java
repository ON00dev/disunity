package info.ata4.unity.assetbundle;

import info.ata4.io.DataReader;
import info.ata4.io.DataReaders;
import info.ata4.unity.assetbundle.AssetBundleException;
import info.ata4.util.progress.Progress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.READ;
import java.util.ArrayList;
import java.util.List;
import net.contrapunctus.lzma.LzmaInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
import org.apache.commons.io.IOUtils;

public class UnityFSExtractor {

    public static final String SIGNATURE_FS = "UnityFS";

    private static final int FLAG_BLOCKS_INFO_AT_END = 0x80;
    private static final int FLAG_BLOCK_INFO_NEED_PADDING_AT_START = 0x200;
    private static final int COMPRESSION_MASK = 0x3F;

    private static final int COMPRESSION_NONE = 0;
    private static final int COMPRESSION_LZMA = 1;
    private static final int COMPRESSION_LZ4 = 2;
    private static final int COMPRESSION_LZ4HC = 3;

    private static final int BLOCK_FLAG_MASK = 0x3F;
    private static final byte[] SIGNATURE_BYTES = new byte[]{'U', 'n', 'i', 't', 'y', 'F', 'S'};

    private UnityFSExtractor() {
    }

    public static boolean isUnityFS(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        try (InputStream is = Files.newInputStream(file)) {
            int maxScan = 1024 * 1024;
            byte[] buf = new byte[64 * 1024];
            byte[] tail = new byte[SIGNATURE_BYTES.length - 1];
            int tailLen = 0;
            int total = 0;

            while (total < maxScan) {
                int toRead = Math.min(buf.length, maxScan - total);
                int read = is.read(buf, 0, toRead);
                if (read <= 0) {
                    break;
                }
                total += read;

                if (containsSignature(tail, tailLen, buf, read)) {
                    return true;
                }

                tailLen = Math.min(tail.length, read);
                System.arraycopy(buf, read - tailLen, tail, 0, tailLen);
            }
            return false;
        } catch (IOException ex) {
            return false;
        }
    }
    
    private static boolean containsSignature(byte[] tail, int tailLen, byte[] buf, int bufLen) {
        int sigLen = SIGNATURE_BYTES.length;
        int scanLen = tailLen + bufLen;
        if (scanLen < sigLen) {
            return false;
        }
        for (int i = 0; i <= scanLen - sigLen; i++) {
            boolean match = true;
            for (int j = 0; j < sigLen; j++) {
                byte b;
                int idx = i + j;
                if (idx < tailLen) {
                    b = tail[idx];
                } else {
                    b = buf[idx - tailLen];
                }
                if (b != SIGNATURE_BYTES[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    public static void extract(Path file, Path outDir, Progress progress) throws IOException {
        long fileSize = Files.size(file);
        try (DataReader in = DataReaders.forFile(file, READ)) {
            long baseOffset = findUnityFSBaseOffset(in, fileSize);
            in.position(baseOffset);
            UnityFSHeader header = readHeader(in);
            long bundleEnd = baseOffset + header.size;
            int alignTo = alignment(header.version, header.flags);

            long blocksInfoOffset = -1;
            BlocksInfo blocksInfo = null;
            List<Long> candidateOffsets = getBlocksInfoCandidateOffsets(header, bundleEnd, alignTo);
            for (Long candidateOffset : candidateOffsets) {
                if (candidateOffset < 0 || candidateOffset + header.compressedBlocksInfoSize > fileSize) {
                    continue;
                }
                in.position(candidateOffset);
                byte[] blocksInfoCompressed = new byte[(int) header.compressedBlocksInfoSize];
                in.readBytes(blocksInfoCompressed);

                byte[] blocksInfoData = tryDecompressBlocksInfo(blocksInfoCompressed, header);
                if (blocksInfoData == null) {
                    continue;
                }

                try {
                    blocksInfo = readBlocksInfo(blocksInfoData);
                    blocksInfoOffset = candidateOffset;
                    break;
                } catch (Exception ex) {
                }
            }

            if (blocksInfo == null) {
                throw new IOException("Failed to locate/decompress UnityFS BlocksInfo");
            }

            long dataOffset = findDataOffset(in, fileSize, blocksInfoOffset, header, blocksInfo, alignTo);

            List<byte[]> blockData = readAndDecompressBlocks(in, dataOffset, blocksInfo.blocks);
            progress.setLimit(blocksInfo.nodes.size());

            for (int i = 0; i < blocksInfo.nodes.size(); i++) {
                if (progress.isCanceled()) {
                    break;
                }

                NodeInfo node = blocksInfo.nodes.get(i);
                progress.setLabel(node.path);

                Path entryFile = outDir.resolve(node.path);
                Files.createDirectories(entryFile.getParent());
                writeNode(entryFile, node, blockData);

                progress.update(i + 1);
            }
        }
    }
    
    private static long findDataOffset(DataReader in, long fileSize, long blocksInfoOffset, UnityFSHeader header, BlocksInfo blocksInfo, int alignTo) throws IOException {
        if ((header.flags & FLAG_BLOCKS_INFO_AT_END) != 0) {
            return align(header.headerEndPosition, alignTo);
        }
        if (blocksInfo.blocks.isEmpty()) {
            throw new IOException("UnityFS BlocksInfo has no storage blocks");
        }
        
        BlockInfo first = blocksInfo.blocks.get(0);
        long base = blocksInfoOffset + header.compressedBlocksInfoSize;
        long aligned = align(base, alignTo);
        
        for (int i = 0; i < alignTo; i++) {
            long candidate = aligned + i;
            if (candidate < 0 || candidate + first.compressedSize > fileSize) {
                continue;
            }
            
            try {
                in.position(candidate);
                byte[] compressed = new byte[(int) first.compressedSize];
                in.readBytes(compressed);
                int compression = first.flags & BLOCK_FLAG_MASK;
                byte[] out = decompress(compressed, (int) first.uncompressedSize, compression);
                if (out.length == (int) first.uncompressedSize) {
                    return candidate;
                }
            } catch (Exception ex) {
            }
        }
        
        for (int i = 0; i < alignTo; i++) {
            long candidate = base + i;
            if (candidate < 0 || candidate + first.compressedSize > fileSize) {
                continue;
            }
            
            try {
                in.position(candidate);
                byte[] compressed = new byte[(int) first.compressedSize];
                in.readBytes(compressed);
                int compression = first.flags & BLOCK_FLAG_MASK;
                byte[] out = decompress(compressed, (int) first.uncompressedSize, compression);
                if (out.length == (int) first.uncompressedSize) {
                    return candidate;
                }
            } catch (Exception ex) {
            }
        }
        
        throw new IOException("Failed to locate UnityFS data offset");
    }
    
    private static List<Long> getBlocksInfoCandidateOffsets(UnityFSHeader header, long bundleEnd, int alignTo) {
        List<Long> offsets = new ArrayList<Long>();
        if ((header.flags & FLAG_BLOCKS_INFO_AT_END) != 0) {
            offsets.add(bundleEnd - header.compressedBlocksInfoSize);
            offsets.add(align(bundleEnd - header.compressedBlocksInfoSize, alignTo));
        } else {
            long start = header.headerEndPosition;
            for (int i = 0; i < alignTo; i++) {
                offsets.add(start + i);
            }
        }
        return offsets;
    }
    
    private static byte[] tryDecompressBlocksInfo(byte[] data, UnityFSHeader header) {
        if (data == null) {
            return null;
        }
        int compression = header.flags & COMPRESSION_MASK;
        int[] candidates;
        if (compression == COMPRESSION_LZMA) {
            candidates = new int[]{COMPRESSION_LZMA, COMPRESSION_LZ4, COMPRESSION_LZ4HC, COMPRESSION_NONE};
        } else if (compression == COMPRESSION_LZ4 || compression == COMPRESSION_LZ4HC) {
            candidates = new int[]{COMPRESSION_LZ4, COMPRESSION_LZ4HC, COMPRESSION_LZMA, COMPRESSION_NONE};
        } else {
            candidates = new int[]{COMPRESSION_NONE, COMPRESSION_LZ4, COMPRESSION_LZ4HC, COMPRESSION_LZMA};
        }

        for (int c : candidates) {
            try {
                byte[] out = decompress(data, (int) header.uncompressedBlocksInfoSize, c);
                if (out.length == (int) header.uncompressedBlocksInfoSize) {
                    return out;
                }
            } catch (Exception ex) {
            }
        }

        return null;
    }

    private static UnityFSHeader readHeader(DataReader in) throws IOException {
        long start = in.position();
        byte[] sig = new byte[8];
        in.readBytes(sig);
        if (!matchesUnityFSSignature(sig)) {
            throw new AssetBundleException("Invalid signature");
        }
        if (sig[7] != 0) {
            in.position(start + 7);
        }

        UnityFSHeader header = new UnityFSHeader();
        header.version = in.readInt();
        header.unityVersion = readNullTerminatedString(in, 256);
        header.unityRevision = readNullTerminatedString(in, 256);
        header.size = in.readLong();
        header.compressedBlocksInfoSize = in.readUnsignedInt();
        header.uncompressedBlocksInfoSize = in.readUnsignedInt();
        header.flags = in.readInt();
        header.headerEndPosition = in.position();
        return header;
    }
    
    private static boolean matchesUnityFSSignature(byte[] sig) {
        if (sig == null || sig.length < SIGNATURE_BYTES.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE_BYTES.length; i++) {
            if (sig[i] != SIGNATURE_BYTES[i]) {
                return false;
            }
        }
        return true;
    }
    
    private static String readNullTerminatedString(DataReader in, int maxLen) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < maxLen; i++) {
            int b = in.readUnsignedByte();
            if (b == 0) {
                return new String(bos.toByteArray(), "UTF-8");
            }
            bos.write(b);
        }
        throw new IOException("Invalid UnityFS string");
    }
    
    private static long findUnityFSBaseOffset(DataReader in, long fileSize) throws IOException {
        long originalPos = in.position();
        try {
            long bestOffset = 0;
            long bestDelta = Long.MAX_VALUE;
            
            List<Long> candidates = findSignatureOffsets(in, fileSize);
            for (Long offset : candidates) {
                UnityFSHeader header = tryReadHeaderAt(in, offset);
                if (header == null) {
                    continue;
                }
                long end = offset + header.size;
                long delta = Math.abs(fileSize - end);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestOffset = offset;
                }
                if (delta == 0) {
                    break;
                }
            }
            
            return bestOffset;
        } finally {
            in.position(originalPos);
        }
    }
    
    private static UnityFSHeader tryReadHeaderAt(DataReader in, long offset) {
        try {
            in.position(offset);
            UnityFSHeader header = readHeader(in);
            if (header.version < 5 || header.version > 10) {
                return null;
            }
            if (header.size <= 0) {
                return null;
            }
            if (header.compressedBlocksInfoSize <= 0 || header.uncompressedBlocksInfoSize <= 0) {
                return null;
            }
            if (header.compressedBlocksInfoSize > header.size) {
                return null;
            }
            return header;
        } catch (Exception ex) {
            return null;
        }
    }
    
    private static List<Long> findSignatureOffsets(DataReader in, long fileSize) throws IOException {
        long originalPos = in.position();
        try {
            List<Long> offsets = new ArrayList<Long>();
            int chunkSize = 64 * 1024;
            byte[] tail = new byte[7];
            int tailLen = 0;
            long pos = 0;
            while (pos < fileSize) {
                in.position(pos);
                int toRead = (int) Math.min(chunkSize, fileSize - pos);
                byte[] chunk = new byte[toRead];
                in.readBytes(chunk);
                
                byte[] scan;
                int scanOffset;
                if (tailLen > 0) {
                    scan = new byte[tailLen + chunk.length];
                    System.arraycopy(tail, 0, scan, 0, tailLen);
                    System.arraycopy(chunk, 0, scan, tailLen, chunk.length);
                    scanOffset = (int) (pos - tailLen);
                } else {
                    scan = chunk;
                    scanOffset = (int) pos;
                }
                
                for (int i = 0; i <= scan.length - SIGNATURE_BYTES.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < SIGNATURE_BYTES.length; j++) {
                        if (scan[i + j] != SIGNATURE_BYTES[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        offsets.add((long) scanOffset + i);
                    }
                }
                
                tailLen = Math.min(tail.length, scan.length);
                System.arraycopy(scan, scan.length - tailLen, tail, 0, tailLen);
                pos += toRead;
                
                if (offsets.size() > 64) {
                    break;
                }
            }
            if (offsets.isEmpty()) {
                offsets.add(0L);
            }
            return offsets;
        } finally {
            in.position(originalPos);
        }
    }

    private static int alignment(int version, int flags) {
        if (version >= 7 && (flags & FLAG_BLOCK_INFO_NEED_PADDING_AT_START) != 0) {
            return 16;
        }
        return 4;
    }

    private static long align(long value, int alignTo) {
        long mod = value % alignTo;
        if (mod == 0) {
            return value;
        }
        return value + (alignTo - mod);
    }

    private static BlocksInfo readBlocksInfo(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        bb.position(16);

        int blockCount = bb.getInt();
        List<BlockInfo> blocks = new ArrayList<BlockInfo>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            BlockInfo block = new BlockInfo();
            block.uncompressedSize = bb.getInt() & 0xFFFFFFFFL;
            block.compressedSize = bb.getInt() & 0xFFFFFFFFL;
            block.flags = bb.getShort() & 0xFFFF;
            blocks.add(block);
        }

        int nodeCount = bb.getInt();
        List<NodeInfo> nodes = new ArrayList<NodeInfo>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            NodeInfo node = new NodeInfo();
            node.offset = bb.getLong();
            node.size = bb.getLong();
            node.flags = bb.getInt();
            node.path = readNullTerminated(bb);
            nodes.add(node);
        }

        BlocksInfo blocksInfo = new BlocksInfo();
        blocksInfo.blocks = blocks;
        blocksInfo.nodes = nodes;
        return blocksInfo;
    }

    private static String readNullTerminated(ByteBuffer bb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (bb.hasRemaining()) {
            byte b = bb.get();
            if (b == 0) {
                return new String(bos.toByteArray(), "UTF-8");
            }
            bos.write(b);
        }
        throw new IOException("Invalid UnityFS node path");
    }

    private static List<byte[]> readAndDecompressBlocks(DataReader in, long dataOffset, List<BlockInfo> blocks) throws IOException {
        List<byte[]> blockData = new ArrayList<byte[]>(blocks.size());
        long current = dataOffset;
        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo block = blocks.get(i);
            in.position(current);
            byte[] compressed = new byte[(int) block.compressedSize];
            in.readBytes(compressed);
            int compression = block.flags & BLOCK_FLAG_MASK;
            byte[] decompressed;
            try {
                decompressed = decompress(compressed, (int) block.uncompressedSize, compression);
            } catch (Exception ex) {
                throw new IOException("Failed to decompress UnityFS block " + i + " (compression=" + compression
                        + ", compressedSize=" + block.compressedSize + ", uncompressedSize=" + block.uncompressedSize
                        + ") at file offset " + current, ex);
            }
            blockData.add(decompressed);
            current += block.compressedSize;
        }
        return blockData;
    }

    private static byte[] decompress(byte[] data, int uncompressedSize, int compressionType) throws IOException {
        switch (compressionType) {
            case COMPRESSION_NONE:
                return data;
            case COMPRESSION_LZMA:
                return decompressLzma(data);
            case COMPRESSION_LZ4:
            case COMPRESSION_LZ4HC:
                return decompressLz4(data, uncompressedSize);
            default:
                throw new IOException("Unsupported UnityFS compression type: " + compressionType);
        }
    }

    private static byte[] decompressLzma(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new LzmaInputStream(new ByteArrayInputStream(data))) {
            IOUtils.copy(in, out);
        }
        return out.toByteArray();
    }

    private static byte[] decompressLz4(byte[] data, int uncompressedSize) throws IOException {
        LZ4SafeDecompressor decompressor = LZ4Factory.fastestInstance().safeDecompressor();
        byte[] restored = new byte[uncompressedSize];
        int restoredLength = decompressor.decompress(data, 0, data.length, restored, 0, uncompressedSize);
        if (restoredLength != uncompressedSize) {
            throw new IOException("LZ4 decompression failed: expected " + uncompressedSize + " bytes, got " + restoredLength);
        }
        return restored;
    }

    private static void writeNode(Path entryFile, NodeInfo node, List<byte[]> blockData) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(node.size, Integer.MAX_VALUE));
        long nodeStart = node.offset;
        long nodeEnd = node.offset + node.size;

        long uncompressedOffset = 0;
        for (byte[] block : blockData) {
            long blockStart = uncompressedOffset;
            long blockEnd = uncompressedOffset + block.length;
            uncompressedOffset = blockEnd;

            if (blockEnd <= nodeStart || blockStart >= nodeEnd) {
                continue;
            }

            int start = (int) Math.max(0, nodeStart - blockStart);
            int end = (int) Math.min(block.length, nodeEnd - blockStart);
            output.write(block, start, end - start);
        }

        try (InputStream data = new ByteArrayInputStream(output.toByteArray())) {
            Files.copy(data, entryFile, REPLACE_EXISTING);
        }
    }

    private static class UnityFSHeader {

        private int version;
        private String unityVersion;
        private String unityRevision;
        private long size;
        private long compressedBlocksInfoSize;
        private long uncompressedBlocksInfoSize;
        private int flags;
        private long headerEndPosition;
    }

    private static class BlockInfo {

        private long uncompressedSize;
        private long compressedSize;
        private int flags;
    }

    private static class NodeInfo {

        private long offset;
        private long size;
        private int flags;
        private String path;
    }

    private static class BlocksInfo {

        private List<BlockInfo> blocks;
        private List<NodeInfo> nodes;
    }
}
