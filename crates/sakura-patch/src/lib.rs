//! Signed, bounded flat-file updates into private immutable generations.
use anyhow::{ensure, Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::{
        fd::AsRawFd,
        unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    },
    path::{Component, Path},
};
pub const MAX_PACKAGE: usize = 16 * 1024 * 1024;
pub const MAX_OUTPUT: usize = 8 * 1024 * 1024;
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Envelope {
    pub manifest_base64: String,
    pub signature_base64: String,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub format: u16,
    pub from: String,
    pub to: String,
    pub activation: Activation,
    pub files: Vec<Entry>,
}
#[derive(Debug, Serialize, Deserialize)]
pub enum Activation {
    AssetsReload,
    ServiceRestart,
    ProcessReplacement,
    RebootRequired,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Entry {
    pub name: String,
    pub sha256: String,
    pub content: Content,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "kind", deny_unknown_fields)]
pub enum Content {
    Full {
        data_base64: String,
    },
    Delta {
        base_sha256: String,
        ops: Vec<DeltaOp>,
    },
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "op", deny_unknown_fields)]
pub enum DeltaOp {
    Copy { offset: usize, length: usize },
    Insert { data_base64: String },
}
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct State {
    pub active: Option<String>,
    pub previous: Vec<String>,
}
pub fn hash(b: &[u8]) -> String {
    format!("{:x}", Sha256::digest(b))
}
pub fn filename(n: &str) -> Result<()> {
    ensure!(
        !n.is_empty()
            && n.len() <= 128
            && n.chars()
                .all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)),
        "unsafe filename"
    );
    let mut c = Path::new(n).components();
    ensure!(
        matches!(c.next(), Some(Component::Normal(_))) && c.next().is_none(),
        "unsafe filename"
    );
    Ok(())
}
fn bounded(path: &Path, max: usize) -> Result<Vec<u8>> {
    let mut f = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    ensure!(f.metadata()?.is_file(), "not a regular file");
    let mut b = Vec::new();
    std::io::Read::by_ref(&mut f)
        .take((max + 1) as u64)
        .read_to_end(&mut b)?;
    ensure!(b.len() <= max, "file exceeds limit");
    Ok(b)
}
pub fn verify(bytes: &[u8], key: &VerifyingKey) -> Result<Manifest> {
    ensure!(bytes.len() <= MAX_PACKAGE, "package too large");
    let e: Envelope = serde_json::from_slice(bytes)?;
    let raw = B64.decode(e.manifest_base64)?;
    let sig = Signature::from_slice(&B64.decode(e.signature_base64)?)?;
    key.verify_strict(&raw, &sig)
        .context("invalid patch signature")?;
    let m: Manifest = serde_json::from_slice(&raw)?;
    ensure!(
        m.format == 1 && !m.files.is_empty() && m.files.len() <= 128,
        "unsupported manifest"
    );
    let from = semver::Version::parse(&m.from)?;
    let to = semver::Version::parse(&m.to)?;
    ensure!(to > from, "target must be newer");
    let mut names = std::collections::BTreeSet::new();
    for f in &m.files {
        filename(&f.name)?;
        ensure!(
            f.name != "manifest.json" && names.insert(&f.name),
            "duplicate/reserved filename"
        );
        ensure!(
            f.sha256.len() == 64 && f.sha256.bytes().all(|b| b.is_ascii_hexdigit()),
            "invalid SHA-256"
        );
    }
    Ok(m)
}
fn materialize(content: &Content, base: Option<&[u8]>) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    match content {
        Content::Full { data_base64 } => {
            out = B64.decode(data_base64)?;
        }
        Content::Delta { base_sha256, ops } => {
            let base = base.context("missing delta base")?;
            ensure!(hash(base) == *base_sha256, "delta base hash mismatch");
            ensure!(ops.len() <= 65536, "too many delta operations");
            for op in ops {
                match op {
                    DeltaOp::Copy { offset, length } => {
                        let end = offset
                            .checked_add(*length)
                            .context("delta range overflow")?;
                        ensure!(
                            end <= base.len() && out.len().saturating_add(*length) <= MAX_OUTPUT,
                            "invalid delta range"
                        );
                        out.extend_from_slice(&base[*offset..end]);
                    }
                    DeltaOp::Insert { data_base64 } => {
                        let b = B64.decode(data_base64)?;
                        ensure!(
                            out.len().saturating_add(b.len()) <= MAX_OUTPUT,
                            "delta output too large"
                        );
                        out.extend(b);
                    }
                }
            }
        }
    }
    ensure!(out.len() <= MAX_OUTPUT, "output too large");
    Ok(out)
}
pub struct Store {
    root: std::path::PathBuf,
    _lock: File,
}
impl Store {
    /// The caller owns this private directory. Untrusted writers must never share it.
    pub fn open(root: &Path) -> Result<Self> {
        if !root.exists() {
            fs::create_dir(root)?;
            fs::set_permissions(root, fs::Permissions::from_mode(0o700))?;
        }
        let md = fs::symlink_metadata(root)?;
        ensure!(
            md.is_dir() && !md.file_type().is_symlink() && md.mode() & 0o077 == 0,
            "update root must be a private directory"
        );
        ensure!(
            md.uid() == unsafe { libc::geteuid() },
            "update root owner mismatch"
        );
        let root = root.canonicalize()?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(root.join("lock"))?;
        // SAFETY: lock is a live fd; no pointers are passed.
        ensure!(
            unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
            "update store busy"
        );
        let releases = root.join("releases");
        if !releases.exists() {
            fs::create_dir(&releases)?;
        }
        ensure!(
            fs::symlink_metadata(&releases)?.is_dir()
                && !fs::symlink_metadata(&releases)?.file_type().is_symlink(),
            "unsafe releases directory"
        );
        Ok(Self { root, _lock: lock })
    }
    pub fn state(&self) -> Result<State> {
        let p = self.root.join("state.json");
        if !p.exists() {
            return Ok(State::default());
        }
        let s: State = serde_json::from_slice(&bounded(&p, 65536)?)?;
        for id in s.active.iter().chain(s.previous.iter()) {
            ensure!(
                id.len() == 64 && id.bytes().all(|b| b.is_ascii_hexdigit()),
                "invalid generation ID"
            );
        }
        Ok(s)
    }
    fn save(&self, s: &State) -> Result<()> {
        let mut f = tempfile::NamedTempFile::new_in(&self.root)?;
        f.write_all(&serde_json::to_vec(s)?)?;
        f.as_file().sync_all()?;
        f.persist(self.root.join("state.json"))?;
        File::open(&self.root)?.sync_all()?;
        Ok(())
    }
    fn generation(&self, id: &str) -> Result<std::path::PathBuf> {
        ensure!(
            id.len() == 64 && id.bytes().all(|b| b.is_ascii_hexdigit()),
            "invalid generation ID"
        );
        let p = self.root.join("releases").join(id);
        let md = fs::symlink_metadata(&p)?;
        ensure!(
            md.is_dir() && !md.file_type().is_symlink(),
            "unsafe generation"
        );
        Ok(p)
    }
    pub fn current_version(&self) -> Result<String> {
        match self.state()?.active {
            Some(id) => {
                let m: Manifest = serde_json::from_slice(&bounded(
                    &self.generation(&id)?.join("manifest.json"),
                    MAX_PACKAGE,
                )?)?;
                Ok(m.to)
            }
            None => Ok("0.1.0".into()),
        }
    }
    /// Stages a complete immutable generation. Every manifest is a full file set.
    pub fn stage(&self, bytes: &[u8], key: &VerifyingKey) -> Result<String> {
        let m = verify(bytes, key)?;
        ensure!(
            m.from == self.current_version()?,
            "incompatible base version"
        );
        let state = self.state()?;
        let temp = tempfile::Builder::new()
            .prefix("stage-")
            .tempdir_in(self.root.join("releases"))?;
        let mut total = 0;
        for entry in &m.files {
            let base = match (&entry.content, &state.active) {
                (Content::Delta { .. }, Some(id)) => Some(bounded(
                    &self.generation(id)?.join(&entry.name),
                    MAX_OUTPUT,
                )?),
                _ => None,
            };
            let b = materialize(&entry.content, base.as_deref())?;
            total += b.len();
            ensure!(total <= MAX_PACKAGE, "generation too large");
            ensure!(hash(&b) == entry.sha256, "file hash mismatch");
            let mut f = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(temp.path().join(&entry.name))?;
            f.write_all(&b)?;
            f.sync_all()?;
        }
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(temp.path().join("manifest.json"))?;
        f.write_all(&serde_json::to_vec(&m)?)?;
        f.sync_all()?;
        File::open(temp.path())?.sync_all()?;
        let id = hash(bytes);
        let dest = self.root.join("releases").join(&id);
        ensure!(!dest.exists(), "generation already staged");
        fs::rename(temp.path(), dest)?;
        File::open(self.root.join("releases"))?.sync_all()?;
        Ok(id)
    }
    pub fn activate(&self, id: &str) -> Result<()> {
        let generation = self.generation(id)?;
        let m: Manifest =
            serde_json::from_slice(&bounded(&generation.join("manifest.json"), MAX_PACKAGE)?)?;
        ensure!(m.from == self.current_version()?, "stale staged generation");
        for e in &m.files {
            filename(&e.name)?;
            ensure!(
                hash(&bounded(&generation.join(&e.name), MAX_OUTPUT)?) == e.sha256,
                "staged content changed"
            );
        }
        let mut state = self.state()?;
        ensure!(
            state.previous.len() < 64,
            "history full; archive before updating"
        );
        if let Some(old) = state.active.replace(id.into()) {
            state.previous.push(old);
        }
        self.save(&state)
    }
    pub fn rollback(&self) -> Result<()> {
        let mut s = self.state()?;
        let old = s.previous.pop().context("no previous generation")?;
        self.generation(&old)?;
        s.active = Some(old);
        self.save(&s)
    }
}
pub fn verify_file(path: &Path, key: &VerifyingKey) -> Result<Manifest> {
    verify(&bounded(path, MAX_PACKAGE)?, key)
}
pub fn read_package(path: &Path) -> Result<Vec<u8>> {
    bounded(path, MAX_PACKAGE)
}
#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    fn package(key: &SigningKey, from: &str, to: &str, b: &[u8]) -> Vec<u8> {
        let m = Manifest {
            format: 1,
            from: from.into(),
            to: to.into(),
            activation: Activation::AssetsReload,
            files: vec![Entry {
                name: "theme.txt".into(),
                sha256: hash(b),
                content: Content::Full {
                    data_base64: B64.encode(b),
                },
            }],
        };
        let raw = serde_json::to_vec(&m).unwrap();
        serde_json::to_vec(&Envelope {
            manifest_base64: B64.encode(&raw),
            signature_base64: B64.encode(key.sign(&raw).to_bytes()),
        })
        .unwrap()
    }
    #[test]
    fn signature_tamper() {
        let k = SigningKey::from_bytes(&[7; 32]);
        let b = package(&k, "0.1.0", "0.1.1", b"hello");
        assert!(verify(&b, &k.verifying_key()).is_ok());
        assert!(verify(&b, &SigningKey::from_bytes(&[8; 32]).verifying_key()).is_err());
    }
    #[test]
    fn traversal() {
        for n in ["..", "../a", "/tmp/a", "a/b", "a\\b", ""] {
            assert!(filename(n).is_err(), "{n}");
        }
    }
    #[test]
    fn transaction_rollback() {
        let d = tempfile::tempdir().unwrap();
        let store = Store::open(&d.path().join("store")).unwrap();
        let k = SigningKey::from_bytes(&[7; 32]);
        let a = store
            .stage(&package(&k, "0.1.0", "0.1.1", b"one"), &k.verifying_key())
            .unwrap();
        assert!(store.state().unwrap().active.is_none());
        store.activate(&a).unwrap();
        let b = store
            .stage(&package(&k, "0.1.1", "0.1.2", b"two"), &k.verifying_key())
            .unwrap();
        store.activate(&b).unwrap();
        store.rollback().unwrap();
        assert_eq!(store.state().unwrap().active, Some(a));
        assert!(store.rollback().is_err());
    }
    #[test]
    fn reject_symlink_root() {
        let d = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink(d.path(), d.path().join("link")).unwrap();
        assert!(Store::open(&d.path().join("link")).is_err());
    }
    #[test]
    fn exclusive_lock() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("store");
        let _a = Store::open(&p).unwrap();
        assert!(Store::open(&p).is_err());
    }
    #[test]
    fn delta_bounds() {
        assert!(materialize(
            &Content::Delta {
                base_sha256: hash(b"abc"),
                ops: vec![DeltaOp::Copy {
                    offset: usize::MAX,
                    length: 10
                }]
            },
            Some(b"abc")
        )
        .is_err());
        let b = materialize(
            &Content::Delta {
                base_sha256: hash(b"abc"),
                ops: vec![
                    DeltaOp::Copy {
                        offset: 0,
                        length: 2,
                    },
                    DeltaOp::Insert {
                        data_base64: B64.encode("d"),
                    },
                ],
            },
            Some(b"abc"),
        )
        .unwrap();
        assert_eq!(b, b"abd");
    }
}
