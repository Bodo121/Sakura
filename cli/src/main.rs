use anyhow::Result;
use sakura_ipc::Command;
use std::path::PathBuf;
fn main() {
    if let Err(e) = run() {
        eprintln!("sakura: {e:#}");
        std::process::exit(1);
    }
}
fn run() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    let cmd = args.first().map(String::as_str).unwrap_or("help");
    let root = std::env::var_os("SAKURA_STATE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/data/adb/sakura"));
    let print = |v: serde_json::Value| -> Result<()> {
        println!("{}", serde_json::to_string_pretty(&v)?);
        Ok(())
    };
    match cmd {
        "help" | "--help" | "-h" => {
            println!("SakuraCLI 0.1.0\nstatus | info | version | services | logs | diagnostics | hardware\npatch list | patch verify <file> | patch stage <file> | patch apply <generation> | patch rollback\nservice stop\nSAKURA_STATE_DIR: private daemon directory\nSAKURA_PATCH_KEY: path to 32-byte raw Ed25519 public key\nSAKURA_UPDATE_DIR: private owned-file store");
            Ok(())
        }
        "version" | "--version" => {
            println!("SakuraOS {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        "info" | "hardware" => print(serde_json::to_value(sakura_hardware::snapshot())?),
        "status" | "services" | "diagnostics" => {
            let c = match cmd {
                "status" => Command::Status,
                "services" => Command::Services,
                _ => Command::Diagnostics,
            };
            print(sakura_ipc::request(&root.join("core.sock"), c)?.data)
        }
        "service" if args.get(1).map(String::as_str) == Some("stop") => {
            print(sakura_ipc::request(&root.join("core.sock"), Command::Shutdown)?.data)
        }
        "logs" => {
            use std::io::Read;
            let path = root.join("daemon.log");
            let f = std::fs::File::open(path)?;
            let mut b = String::new();
            f.take(1024 * 1024).read_to_string(&mut b)?;
            print!("{b}");
            Ok(())
        }
        "patch" => {
            let verb = args.get(1).map(String::as_str).unwrap_or("list");
            let dir = std::env::var_os("SAKURA_UPDATE_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| root.join("updates"));
            let key = || -> Result<ed25519_dalek::VerifyingKey> {
                let path = std::env::var_os("SAKURA_PATCH_KEY").ok_or_else(|| {
                    anyhow::anyhow!("set SAKURA_PATCH_KEY to a trusted public-key file")
                })?;
                let b = std::fs::read(path)?;
                let a: [u8; 32] = b
                    .try_into()
                    .map_err(|_| anyhow::anyhow!("public key must be exactly 32 raw bytes"))?;
                Ok(ed25519_dalek::VerifyingKey::from_bytes(&a)?)
            };
            let arg = || {
                args.get(2)
                    .ok_or_else(|| anyhow::anyhow!("missing patch file or generation"))
            };
            match verb {
                "inspect" => {
                    let m = sakura_patch::verify_file(std::path::Path::new(arg()?), &key()?)?;
                    print(serde_json::json!({
                        "format": m.format, "from": m.from, "to": m.to,
                        "activation": m.activation,
                        "files": m.files.iter().map(|f| serde_json::json!({"name": f.name, "sha256": f.sha256})).collect::<Vec<_>>()
                    }))
                }
                "verify" => print(serde_json::to_value(sakura_patch::verify_file(
                    std::path::Path::new(arg()?),
                    &key()?,
                )?)?),
                "list" => print(serde_json::to_value(
                    sakura_patch::Store::open(&dir)?.state()?,
                )?),
                "stage" => {
                    let path = arg()?;
                    sakura_patch::verify_file(std::path::Path::new(path), &key()?)?;
                    let bytes = sakura_patch::read_package(std::path::Path::new(path))?;
                    let id = sakura_patch::Store::open(&dir)?.stage(&bytes, &key()?)?;
                    println!("{id}");
                    Ok(())
                }
                "apply" => {
                    sakura_patch::Store::open(&dir)?.activate(arg()?)?;
                    println!(
                        "activated owned-file generation; running processes were not replaced"
                    );
                    Ok(())
                }
                "rollback" => {
                    sakura_patch::Store::open(&dir)?.rollback()?;
                    println!("rolled back owned-file generation");
                    Ok(())
                }
                _ => anyhow::bail!("unknown patch command"),
            }
        }
        _ => anyhow::bail!("unknown command; use sakura help"),
    }
}
