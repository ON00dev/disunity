Support
-------

A list of features and Unity versions that are currently supported by DisUnity.

### Tested engine versions

* 2.6
* 3.1
* 3.3
* 3.4
* 3.5
* 4.1
* 4.2
* 4.3
* 5+
* 2021.3.34f1c1 (SerializedFile v22 / LargeFilesSupport)

### Asset bundles

Format              | Status
------------------- | -----------------------------------------------------------
UnityWeb/UnityRaw   | Ok (legacy Unity 2.x–4.x style bundles)
UnityFS (Unity 5+)  | Ok for extraction and bundle-info/bundle-list (LZ4/LZMA). Encrypted/obfuscated bundles are not supported.

### Asset extraction

Type                | Status
------------------- | -----------------------------------------------------------
AudioClip           | Ok
Font                | Ok, but wrong file extension for OpenType fonts
Mesh                | Unity 4 only, one UV layer only, no weights or vertex colors
TextAsset           | Ok
Shader              | Ok
Texture2D           | Missing support for some exotic compression formats, no uniform extraction file format
Cubemap             | Ok
SubstanceArchive    | Ok
MovieTexture        | Ok

### Lua extraction

Feature             | Status
------------------- | -----------------------------------------------------------
Search + extract Lua scripts from TextAssets by content (lua-extract) | Ok
