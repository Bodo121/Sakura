use anyhow::{ensure, Result};
use sakura_ipc::{Command, Request, Response, VERSION};
use std::{
    fs,
    os::unix::{
        fs::{MetadataExt, PermissionsExt},
        net::UnixListener,
    },
    path::PathBuf,
    time::Instant,
};
fn main() {
    if let Err(e) = run() {
        sakura_runtime::log("fatal", &format!("{e:#}"));
        std::process::exit(1);
    }
}
fn run() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let root = match (args.next().as_deref(), args.next()) {
        (Some("--state-dir"), Some(p)) => PathBuf::from(p),
        _ => anyhow::bail!("usage: sakurad --state-dir <private-directory>"),
    };
    ensure!(args.next().is_none(), "unexpected arguments");
    // SAFETY: umask/geteuid have no pointer preconditions.
    unsafe {
        libc::umask(0o077);
    }
    let uid = unsafe { libc::geteuid() };
    fs::create_dir_all(&root)?;
    let md = fs::symlink_metadata(&root)?;
    ensure!(
        md.is_dir() && !md.file_type().is_symlink() && md.uid() == uid && md.mode() & 0o077 == 0,
        "state directory must be private and owned by daemon UID"
    );
    let socket = root.join("core.sock");
    // Exclusive lock prevents duplicate startup and makes stale socket removal safe.
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;
    let lock = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(root.join("daemon.lock"))?;
    ensure!(
        unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "daemon already running"
    );
    if socket.exists() {
        fs::remove_file(&socket)?;
    }
    let listener = UnixListener::bind(&socket)?;
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600))?;
    let start = Instant::now();
    sakura_runtime::log("ready", &socket.display().to_string());
    for stream in listener.incoming() {
        let mut s = stream?;
        if sakura_ipc::configure(&s)
            .and_then(|_| sakura_ipc::authorize(&s, uid))
            .is_err()
        {
            continue;
        }
        let req: Request = match sakura_ipc::recv(&mut s) {
            Ok(r) => r,
            Err(_) => continue,
        };
        if req.version != VERSION {
            let _ = sakura_ipc::send(
                &mut s,
                &Response {
                    version: VERSION,
                    id: req.id,
                    ok: false,
                    data: serde_json::json!({"error":"unsupported protocol"}),
                },
            );
            continue;
        }
        let shutdown = matches!(req.command, Command::Shutdown);
        let data = match req.command {
            Command::Status => {
                serde_json::json!({"version":env!("CARGO_PKG_VERSION"),"pid":std::process::id(),"uid":uid,"uptime_ms":start.elapsed().as_millis(),"protocol":VERSION})
            }
            Command::Hardware => serde_json::to_value(sakura_hardware::snapshot())?,
            Command::Services => {
                serde_json::json!([{"name":"sakurad","state":"running","pid":std::process::id()}])
            }
            Command::Diagnostics => {
                serde_json::json!({"socket":socket,"peer_uid_enforced":true,"selinux":fs::read_to_string("/sys/fs/selinux/enforce").ok(),"apatch_accessible":std::path::Path::new("/data/adb/ap").exists(),"privileged_app_bridge":"not provisioned"})
            }
            Command::Shutdown => serde_json::json!({"state":"stopping"}),
        };
        let _ = sakura_ipc::send(
            &mut s,
            &Response {
                version: VERSION,
                id: req.id,
                ok: true,
                data,
            },
        );
        if shutdown {
            break;
        }
    }
    fs::remove_file(socket)?;
    sakura_runtime::log("stopped", "graceful shutdown");
    Ok(())
}
