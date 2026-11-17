# Security model and limits

IPC v1 uses length-prefixed bounded JSON over Unix sockets. Max frame is 256 KiB, read/write timeout two seconds, protocol version one. Peers are checked using Linux SO_PEERCRED; clients also check the server UID. Request IDs are matched. Private 0700 state and 0600 sockets deny ordinary Android UIDs access. There is no network listener, bearer token in shared storage, or privileged arbitrary-command method. This is not an externally audited protocol.

A same-UID/root attacker can alter that UID's data and executable files: isolation is between application identities, not processes sharing a UID. Filesystem locks protect against concurrent cooperating updaters; a private trusted directory is required. The APatch host provider itself is trusted. SELinux remains enforcing; device policy can legitimately deny execution or sysfs access. Such denials require device investigation rather than automatic policy weakening.

Patch inputs are bounded, signatures cover exact manifest bytes, and every materialized file is hashed. Flat safe names prevent traversal and avoid archive extraction entirely. Input files use O_NOFOLLOW, generations use private directories, and store ownership/mode are checked. Staged data are non-executable 0600 files. Copy/insert offsets and lengths are checked. A generation is renamed only after fsync, and state updates use same-directory atomic replacement and fsync. These measures require power-loss tests on target storage before durability claims. Keys are provisioned explicitly; no development private key is trusted by default. Untrusted metadata and file contents must never be treated as instructions.

The daemon processes one bounded request at a time. An authorized same-UID caller can delay it by a timeout; root already controls the process. Event subscriptions and a multiplexing protocol are not implemented. Restart limits apply to the service library; APatch starts the daemon once per boot.

Report security issues privately to the repository owner before public disclosure. No security audit or physical-device validation is claimed.
