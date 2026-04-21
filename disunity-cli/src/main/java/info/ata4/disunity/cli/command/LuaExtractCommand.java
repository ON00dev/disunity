package info.ata4.disunity.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import info.ata4.io.buffer.ByteBufferUtils;
import info.ata4.log.LogUtils;
import info.ata4.unity.asset.AssetFile;
import info.ata4.unity.engine.TextAsset;
import info.ata4.unity.rtti.ObjectData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;

@Parameters(
    commandNames = "lua-extract",
    commandDescription = "Finds Lua scripts inside TextAssets by searching their contents and extracts matching files."
)
public class LuaExtractCommand extends AssetFileCommand {

    private static final Logger L = LogUtils.getLogger();

    @Parameter(
        names = {"-q", "--query"},
        description = "Search string or regex pattern",
        required = true
    )
    private String query;

    @Parameter(
        names = {"-o", "--output"},
        description = "Output directory for extracted scripts"
    )
    private String outputDir;

    @Parameter(
        names = {"--regex"},
        description = "Treat query as a regex"
    )
    private boolean regex;

    @Parameter(
        names = {"-i", "--ignore-case"},
        description = "Ignore case while searching"
    )
    private boolean ignoreCase;

    @Override
    public void handleAssetFile(AssetFile asset) throws IOException {
        Path out = resolveOutputDirectory();
        if (Files.notExists(out)) {
            Files.createDirectories(out);
        }

        Pattern pattern = null;
        if (regex) {
            int flags = Pattern.MULTILINE;
            if (ignoreCase) {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            pattern = Pattern.compile(query, flags);
        }

        int matches = 0;
        for (ObjectData objectData : asset.objects()) {
            if (!"TextAsset".equals(objectData.info().unityClass().name())) {
                continue;
            }

            TextAsset textAsset;
            try {
                textAsset = new TextAsset(objectData.instance());
            } catch (Exception ex) {
                continue;
            }

            String name = textAsset.getName();
            if (name == null || name.isEmpty()) {
                name = String.valueOf(objectData.ID());
            }

            byte[] data = getTextAssetBytes(textAsset);
            if (data == null || data.length == 0) {
                continue;
            }

            String content = new String(data, StandardCharsets.UTF_8);
            if (!matchesQuery(content, pattern)) {
                continue;
            }

            Path outFile = out.resolve(sanitizeRelativePath(ensureLuaExtension(name)));
            Files.createDirectories(outFile.getParent());
            ByteBufferUtils.save(outFile, ByteBuffer.wrap(data));
            L.log(Level.INFO, "Extracted {0}", outFile);
            matches++;
        }

        if (matches == 0) {
            L.log(Level.WARNING, "No matching Lua scripts found for query: {0}", query);
        } else {
            L.log(Level.INFO, "Matched {0} Lua script(s)", matches);
        }
    }

    private boolean matchesQuery(String content, Pattern pattern) {
        if (pattern != null) {
            return pattern.matcher(content).find();
        }
        if (ignoreCase) {
            return content.toLowerCase().contains(query.toLowerCase());
        }
        return content.contains(query);
    }

    private static byte[] getTextAssetBytes(TextAsset asset) {
        try {
            ByteBuffer buf = asset.getScriptRaw();
            if (buf == null) {
                return null;
            }
            ByteBuffer dup = buf.duplicate();
            dup.clear();
            byte[] out = new byte[dup.remaining()];
            dup.get(out);
            return out;
        } catch (Exception ex) {
            String s;
            try {
                s = asset.getScript();
            } catch (Exception ex2) {
                return null;
            }
            if (s == null) {
                return null;
            }
            return s.getBytes(StandardCharsets.UTF_8);
        }
    }

    private Path resolveOutputDirectory() {
        if (outputDir != null && !outputDir.trim().isEmpty()) {
            return Paths.get(outputDir);
        }
        Path current = getCurrentFile();
        String base = FilenameUtils.removeExtension(current.getFileName().toString());
        return current.toAbsolutePath().getParent().resolve(base + "_lua");
    }

    private static String ensureLuaExtension(String name) {
        String baseName = FilenameUtils.getName(name);
        String ext = FilenameUtils.getExtension(baseName);
        if (ext == null || ext.isEmpty()) {
            return baseName + ".lua";
        }
        return baseName;
    }

    private static Path sanitizeRelativePath(String name) {
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        rel = rel.replace(':', '_');
        rel = rel.replace('*', '_');
        rel = rel.replace('?', '_');
        rel = rel.replace('"', '_');
        rel = rel.replace('<', '_');
        rel = rel.replace('>', '_');
        rel = rel.replace('|', '_');
        rel = rel.replace("..", "__");
        return Paths.get(rel);
    }
}

