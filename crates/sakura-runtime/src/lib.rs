use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Instant,
};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lifecycle {
    Created,
    Active,
    Suspended,
    Stopped,
}
pub struct Runtime {
    pub id: String,
    pub state: Lifecycle,
    pub started: Instant,
    cancelled: Arc<AtomicBool>,
}
impl Runtime {
    pub fn new(id: &str) -> Self {
        Self {
            id: id.into(),
            state: Lifecycle::Created,
            started: Instant::now(),
            cancelled: Arc::new(AtomicBool::new(false)),
        }
    }
    pub fn activate(&mut self) -> anyhow::Result<()> {
        anyhow::ensure!(self.state != Lifecycle::Stopped, "runtime stopped");
        self.state = Lifecycle::Active;
        Ok(())
    }
    pub fn suspend(&mut self) {
        if self.state == Lifecycle::Active {
            self.state = Lifecycle::Suspended;
        }
    }
    pub fn shutdown(&mut self) {
        self.cancelled.store(true, Ordering::Release);
        self.state = Lifecycle::Stopped;
    }
    pub fn cancellation(&self) -> Arc<AtomicBool> {
        self.cancelled.clone()
    }
}
impl Drop for Runtime {
    fn drop(&mut self) {
        self.shutdown();
    }
}
#[derive(Default, Serialize, Deserialize)]
pub struct Settings {
    pub values: BTreeMap<String, String>,
}
impl Settings {
    pub fn load(path: &Path) -> anyhow::Result<Self> {
        match std::fs::read(path) {
            Ok(b) => Ok(serde_json::from_slice(&b)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e.into()),
        }
    }
    pub fn save(&self, path: &Path) -> anyhow::Result<()> {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let tmp = path.with_extension(format!("{}.tmp", std::process::id()));
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        let result = (|| {
            f.write_all(&serde_json::to_vec(self)?)?;
            f.sync_all()?;
            std::fs::rename(&tmp, path)?;
            std::fs::File::open(path.parent().unwrap_or(Path::new(".")))?.sync_all()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(tmp);
        }
        result
    }
}
pub fn log(event: &str, message: &str) {
    eprintln!(
        "{}",
        serde_json::json!({"event":event,"message":message,"pid":std::process::id()})
    );
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lifecycle() {
        let mut r = Runtime::new("test");
        r.activate().unwrap();
        r.suspend();
        assert_eq!(r.state, Lifecycle::Suspended);
        r.shutdown();
        assert!(r.activate().is_err());
        assert!(r.cancellation().load(Ordering::Acquire));
    }
}
