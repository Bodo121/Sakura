use serde::Serialize;
use std::{fs, path::Path};
pub type BatteryReading = (String, Option<String>, Option<String>, Option<String>);
#[derive(Debug, Serialize)]
pub struct Snapshot {
    pub kernel: Option<String>,
    pub memory_kib: Option<String>,
    pub cpu_times: Option<String>,
    pub cpu_online: Option<String>,
    pub cpu_frequencies_khz: Vec<(String, Option<String>)>,
    pub zram: Vec<(String, Option<String>)>,
    pub batteries: Vec<BatteryReading>,
    pub thermals_millidegrees: Vec<(String, Option<String>, Option<String>)>,
    pub network: Option<String>,
    pub storage_bytes: Option<(u64, u64)>,
    pub architecture: String,
}
fn read(path: impl AsRef<Path>) -> Option<String> {
    fs::read_to_string(path).ok().map(|s| s.trim().into())
}
fn dirs(path: &str, prefix: &str) -> Vec<std::path::PathBuf> {
    let mut out: Vec<_> = fs::read_dir(path)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter(|e| e.file_name().to_string_lossy().starts_with(prefix))
        .map(|e| e.path())
        .collect();
    out.sort();
    out
}
pub fn storage(path: &Path) -> Option<(u64, u64)> {
    use std::{ffi::CString, os::unix::ffi::OsStrExt};
    let p = CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut v = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // SAFETY: p is NUL terminated and v points to writable storage of the correct size.
    if unsafe { libc::statvfs(p.as_ptr(), v.as_mut_ptr()) } != 0 {
        return None;
    }
    // SAFETY: successful statvfs initialized v.
    let v = unsafe { v.assume_init() };
    Some((
        v.f_blocks.saturating_mul(v.f_frsize),
        v.f_bavail.saturating_mul(v.f_frsize),
    ))
}
pub fn snapshot() -> Snapshot {
    Snapshot {
        kernel: read("/proc/sys/kernel/osrelease"),
        memory_kib: read("/proc/meminfo"),
        cpu_times: read("/proc/stat").and_then(|s| s.lines().next().map(str::to_owned)),
        cpu_online: read("/sys/devices/system/cpu/online"),
        cpu_frequencies_khz: dirs("/sys/devices/system/cpu/cpufreq", "policy")
            .into_iter()
            .map(|p| (p.display().to_string(), read(p.join("scaling_cur_freq"))))
            .collect(),
        zram: dirs("/sys/block", "zram")
            .into_iter()
            .map(|p| (p.display().to_string(), read(p.join("mm_stat"))))
            .collect(),
        batteries: dirs("/sys/class/power_supply", "")
            .into_iter()
            .filter(|p| read(p.join("type")).as_deref() == Some("Battery"))
            .map(|p| {
                (
                    p.display().to_string(),
                    read(p.join("capacity")),
                    read(p.join("status")),
                    read(p.join("temp")),
                )
            })
            .collect(),
        thermals_millidegrees: dirs("/sys/class/thermal", "thermal_zone")
            .into_iter()
            .map(|p| {
                (
                    p.display().to_string(),
                    read(p.join("type")),
                    read(p.join("temp")),
                )
            })
            .collect(),
        network: read("/proc/net/dev"),
        storage_bytes: storage(Path::new(".")),
        architecture: std::env::consts::ARCH.into(),
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn real_kernel() {
        let s = super::snapshot();
        assert!(s.kernel.is_some());
        assert!(s.storage_bytes.is_some());
    }
}
