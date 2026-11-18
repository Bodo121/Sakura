# SakuraPatch format 1

`.patch` is bounded UTF-8 JSON, not a ZIP or executable script. Envelope keys: `manifest_base64` and `signature_base64`. The signature is Ed25519 over the **exact decoded manifest bytes**, verified with `ed25519-dalek::verify_strict`. There is no JSON reserialization during signature verification.

Manifest keys:

```json
{"format":1,"from":"0.1.0","to":"0.1.1","activation":"AssetsReload","files":[{"name":"theme.txt","sha256":"<64 hex SHA-256>","content":{"kind":"Full","data_base64":"<base64 bytes>"}}]}
```

The target must be a newer semantic version and `from` must exactly match the active generation version (initial version 0.1.0). Maximum package/output generation size: 16 MiB. Maximum output file: 8 MiB. At most 128 flat files; no duplicate names, separators, symlinks, archive extraction or `manifest.json` payload. Each manifest is the complete file set for its generation, not an in-place mutation list.

A delta entry uses `{"kind":"Delta","base_sha256":"...","ops":[{"op":"Copy","offset":0,"length":8},{"op":"Insert","data_base64":"..."}]}`. This is a bounded copy/insert binary delta representation. It is not bsdiff, does not generate optimal deltas, and uses no proprietary cryptography. The base must be the same-named file in the current generation and match its hash. At most 65,536 operations; arithmetic and output lengths are checked.

Staging writes a fresh directory, fsyncs files/metadata, and renames it to a SHA-256 envelope ID under `releases`. Activation verifies content again and atomically replaces `state.json`, including prior generation IDs. Rollback selects and consumes the previous ID. The first generation currently has no rollback snapshot of the implicit 0.1.0 base. The library does not garbage collect generations, rotate keys or store a hardware-backed monotonic version counter.

Activation categories: `AssetsReload`, `ServiceRestart`, `ProcessReplacement`, `RebootRequired`. In this preview they describe required work; no running process is restarted automatically. The update store is separate from the module binaries and APKs. Applying a patch does not replace APKs, Android framework, boot images, vendor files or kernel memory.

CLI: `SAKURA_PATCH_KEY=/path/to/public.key SAKURA_UPDATE_DIR=/private/store sakura patch verify file.patch`, then `stage`, `apply GENERATION_ID`, `list`, `rollback`. Key file is exactly 32 raw public bytes. Keep private signing keys offline.
