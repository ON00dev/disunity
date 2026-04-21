DisUnity
=========

An experimental command-line toolset for Unity asset and asset bundle files, mostly designed for extraction.

Requirements
------------

- Java runtime: Java 8+ (recommended: 17+)
- Build from source: Maven + JDK 17+ (output is Java 8 bytecode)

Download
--------

The latest build can be found on the [releases page](https://github.com/on00dev/disunity/releases).

Installation (portable ZIP)
---------------------------

The release artifacts include a portable ZIP that contains:

- disunity.jar
- disunity.bat (Windows)
- disunity.sh (Linux/macOS)
- disunity (Linux/macOS, no extension)

To install:

- Extract the ZIP to a folder, e.g.:
  - Windows: C:\Tools\disunity\
  - Linux: /opt/disunity/
- Add that folder to your PATH.
- Run:

    disunity -h

Usage
-----

    disunity <command> <file>
    
**Note:** depending on the platform, you may need to run disunity.bat (Windows) or disunity.sh (Linux/MacOS). In case the launch script fails, try `java -jar disunity.jar`.

### Available commands

| Command        | Purpose
| :------------- | :-------------
| extract        | Extracts supported asset objects to regular files (.txt, .wav, .tga, etc.). See SUPPORT.md for a list of supported asset types.
| lua-extract    | Searches Lua scripts inside TextAssets by content (string or regex) and extracts matching .lua files.
| learn          | Learns the structure information from the submitted files and stores any new structs in the database file structdb.dat. The database is required to deserialize standalone asset files, which usually don't contain any structure information.
| info           | Outputs various information about assets and asset bundle files.
| bundle-extract | Extracts all packed files from asset bundles.
| bundle-list    | Lists all files contained in asset bundles.
| split          | Attempts to split an asset file into multiple smaller asset files.
| bundle-info    | Prints header and entry information for asset bundles (including UnityFS).
| list           | Lists all asset objects in a tabular form.

### Other parameters

Run disunity with the `-h` parameter for further usage.

### Examples

Extract all supported assets from a bundle file:
Extract all supported assets from an asset file or a CAB file:
    disunity extract Web.unity3d
    disunity extract CAB-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Extract all packed files from two bundle files:

    disunity bundle-extract episode1.unity3d episode2.unity3d


Extract files from a UnityFS bundle:

    disunity bundle-extract a74cc6acee889971e67a7f7dc5fc71ef.bundle

Find and extract Lua scripts containing a signature (string search):

    disunity lua-extract -q "gameResult, myTeamMedal, opponentMedal" -o extracted_lua CAB-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

Find and extract Lua scripts using regex (multiline):

    disunity lua-extract --regex -q "function\\s+.*\\(\\s*gameResult\\s*,\\s*myTeamMedal\\s*,\\s*opponentMedal\\s*\\)" -o extracted_lua CAB-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Extract textures from the asset file sharedassets0.assets:

    disunity extract -f texture2d sharedassets0.assets


Dump web player configuration from the file named Web.unity3d:

    disunity dump -f playersettings Web.unity3d

Show information about all asset files in the directory "assets":

    disunity info assets\*.asset
