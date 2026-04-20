package info.ata4.unity.assetbundle;

import info.ata4.io.DataReader;
import info.ata4.io.DataReaders;
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

    private UnityFSExtractor() {
    }

    public static boolean isUnityFS(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        try (InputStream is = Files.newInputStream(file)) {
            byte[] header = new byte[7];
            int read = is.read(header);
            if (read != header.length) {
                return false;
            }
            return SIGNATURE_FS.equals(new String(header, "US-ASCII"));
        } catch (IOException ex) {
            return false;
        }
    }

    public static void extract(Path file, Path outDir, Progress progress) throws IOException {
        try (DataReader in = DataReaders.forFile(file, READ)) {
            UnityFSHeader header = readHeader(in);
            long bundleSize = Files.size(file);

            long blocksInfoOffset;
            long dataOffset;
            if ((header.flags & FLAG_BLOCKS_INFO_AT_END) != 0) {
                blocksInfoOffset = bundleSize - header.compressedBlocksInfoSize;
                dataOffset = align(header.headerEndPosition, alignment(header.version, header.flags));
            } else {
                blocksInfoOffset = header.headerEndPosition;
                dataOffset = blocksInfoOffset + header.compressedBlocksInfoSize;
                dataOffset = align(dataOffset, alignment(header.version, header.flags));
            }

            in.position(blocksInfoOffset);
            byte[] blocksInfoCompressed = new byte[(int) header.compressedBlocksInfoSize];
            in.readBytes(blocksInfoCompressed);
            byte[] blocksInfoData = decompress(blocksInfoCompressed, (int) header.uncompressedBlocksInfoSize, header.flags & COMPRESSION_MASK);

            BlocksInfo blocksInfo = readBlocksInfo(blocksInfoData);
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

    private static UnityFSHeader readHeader(DataReader in) throws IOException {
        String signature = in.readStringNull();
        if (!SIGNATURE_FS.equals(signature)) {
            throw new AssetBundleException("Invalid signature");
        }

        UnityFSHeader header = new UnityFSHeader();
        header.version = in.readInt();
        header.unityVersion = in.readStringNull();
        header.unityRevision = in.readStringNull();
        header.size = in.readLong();
        header.compressedBlocksInfoSize = in.readUnsignedInt();
        header.uncompressedBlocksInfoSize = in.readUnsignedInt();
        header.flags = in.readInt();
        header.headerEndPosition = in.position();
        return header;
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
        for (BlockInfo block : blocks) {
            in.position(current);
            byte[] compressed = new byte[(int) block.compressedSize];
            in.readBytes(compressed);
            int compression = block.flags & BLOCK_FLAG_MASK;
            byte[] decompressed = decompress(compressed, (int) block.uncompressedSize, compression);
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
            byte[] exact = new byte[restoredLength];
            System.arraycopy(restored, 0, exact, 0, restoredLength);
            return exact;
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
