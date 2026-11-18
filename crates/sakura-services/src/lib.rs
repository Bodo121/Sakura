use serde::Serialize;
use std::{
    collections::{BTreeMap, VecDeque},
    process::{Child, Command},
    time::{Duration, Instant},
};
#[derive(Clone)]
pub struct Spec {
    pub name: String,
    pub executable: std::path::PathBuf,
    pub args: Vec<String>,
    pub dependencies: Vec<String>,
}
#[derive(Serialize)]
pub struct State {
    pub name: String,
    pub running: bool,
    pub pid: Option<u32>,
    pub restarts: usize,
    pub failed: bool,
}
pub struct Manager {
    specs: BTreeMap<String, Spec>,
    children: BTreeMap<String, Child>,
    attempts: BTreeMap<String, VecDeque<Instant>>,
    failed: std::collections::BTreeSet<String>,
}
impl Default for Manager {
    fn default() -> Self {
        Self::new()
    }
}
impl Manager {
    pub fn new() -> Self {
        Self {
            specs: BTreeMap::new(),
            children: BTreeMap::new(),
            attempts: BTreeMap::new(),
            failed: Default::default(),
        }
    }
    pub fn register(&mut self, s: Spec) -> anyhow::Result<()> {
        anyhow::ensure!(!self.specs.contains_key(&s.name), "duplicate service");
        self.specs.insert(s.name.clone(), s);
        Ok(())
    }
    pub fn start(&mut self, name: &str) -> anyhow::Result<()> {
        self.start_inner(name, &mut Vec::new())
    }
    fn start_inner(&mut self, name: &str, stack: &mut Vec<String>) -> anyhow::Result<()> {
        if self.children.contains_key(name) {
            return Ok(());
        }
        anyhow::ensure!(!stack.iter().any(|n| n == name), "dependency cycle");
        let s = self
            .specs
            .get(name)
            .ok_or_else(|| anyhow::anyhow!("unknown service"))?
            .clone();
        stack.push(name.into());
        for dep in &s.dependencies {
            self.start_inner(dep, stack)?;
        }
        stack.pop();
        let a = self.attempts.entry(name.into()).or_default();
        let now = Instant::now();
        while a
            .front()
            .is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60))
        {
            a.pop_front();
        }
        if a.len() >= 3 {
            self.failed.insert(name.into());
            anyhow::bail!("restart rate limit");
        }
        a.push_back(now);
        let child = Command::new(&s.executable)
            .args(&s.args)
            .stdin(std::process::Stdio::null())
            .spawn()?;
        self.children.insert(name.into(), child);
        self.failed.remove(name);
        Ok(())
    }
    pub fn stop(&mut self, name: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            !self
                .specs
                .values()
                .any(|s| s.dependencies.iter().any(|d| d == name)
                    && self.children.contains_key(&s.name)),
            "running dependents"
        );
        if let Some(mut c) = self.children.remove(name) {
            if c.try_wait()?.is_none() {
                c.kill()?;
            }
            c.wait()?;
        }
        Ok(())
    }
    pub fn restart(&mut self, name: &str) -> anyhow::Result<()> {
        self.stop(name)?;
        self.start(name)
    }
    /// Invoked by a host event/timer, never an internal polling thread.
    pub fn health_check(&mut self) -> anyhow::Result<()> {
        let mut dead = Vec::new();
        for (n, c) in &mut self.children {
            if c.try_wait()?.is_some() {
                dead.push(n.clone());
            }
        }
        for n in dead {
            self.children.remove(&n);
            if self.start(&n).is_err() {
                self.failed.insert(n);
            }
        }
        Ok(())
    }
    pub fn states(&self) -> Vec<State> {
        self.specs
            .keys()
            .map(|n| State {
                name: n.clone(),
                running: self.children.contains_key(n),
                pid: self.children.get(n).map(Child::id),
                restarts: self.attempts.get(n).map_or(0, VecDeque::len),
                failed: self.failed.contains(n),
            })
            .collect()
    }
}
impl Drop for Manager {
    fn drop(&mut self) {
        for c in self.children.values_mut() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn spec(name: &str, deps: Vec<String>) -> Spec {
        Spec {
            name: name.into(),
            executable: "/bin/true".into(),
            args: vec![],
            dependencies: deps,
        }
    }
    #[test]
    fn cycle() {
        let mut m = Manager::new();
        m.register(spec("a", vec!["b".into()])).unwrap();
        m.register(spec("b", vec!["a".into()])).unwrap();
        assert!(m.start("a").is_err());
    }
    #[test]
    fn recovery_bounded() {
        let mut m = Manager::new();
        m.register(spec("a", vec![])).unwrap();
        for _ in 0..3 {
            m.start("a").unwrap();
            m.stop("a").unwrap();
        }
        assert!(m.start("a").is_err());
        assert!(m.states()[0].failed);
    }
}
