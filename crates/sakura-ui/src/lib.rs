use slint::{ComponentHandle, ModelRc, VecModel};
use std::{cell::RefCell, path::PathBuf, rc::Rc};
slint::slint! {
 #[edition = "1.19"]
 import { Button, LineEdit, ScrollView, CheckBox, Slider } from "std-widgets.slint";
 export struct Row { label:string, detail:string }
 export component SakuraWindow inherits Window {
  title: "SakuraOS"; width: 420px; height: 820px;
  in-out property <bool> dark: true;
  in-out property <string> heading;
  in-out property <string> subtitle;
  in-out property <string> status: "Ready";
  in-out property <[Row]> rows;
  in-out property <string> primary: "Refresh";
  in-out property <string> secondary: "Back";
  in-out property <string> field-placeholder: "Search or enter a value";
  in-out property <string> field;
  in-out property <bool> terminal: false;
  in-out property <bool> documents: false;
  in-out property <string> tertiary: "More";
  in-out property <string> output;
  callback action(int);
  callback selected(int);
  callback search(string);
  background: dark ? #090c12 : #f6f5fa;
  VerticalLayout { padding: 22px; spacing: 14px;
   HorizontalLayout { spacing: 8px;
    Text { text:"SAKURA";color:root.dark?#f3a8c4:#9b3f66;font-size:14px;font-weight:700;vertical-alignment:center; }
    Rectangle { horizontal-stretch:1; background:transparent; }
    CheckBox { text:"Dark";checked <=> root.dark; toggled => {root.action(9);} }
   }
   Text { text:root.heading;color:root.dark?#f7f3fa:#191c27;font-size:30px;font-weight:700; }
   Text { text:root.subtitle;color:root.dark?#a9a6b8:#666579;font-size:13px;wrap:word-wrap; }
   LineEdit { placeholder-text:root.field-placeholder;text <=> root.field;edited=>{root.search(self.text);} }
   if root.documents : Button {text:"Import document";clicked=>{root.action(3);}}
   if !root.terminal : ScrollView { vertical-stretch:1;
    VerticalLayout { spacing:10px;
     for row[i] in root.rows : Rectangle { min-height:80px;border-radius:18px;background:root.dark?#151a24:#ffffff;
      VerticalLayout { padding:16px;spacing:6px;
       Text {text:row.label;color:root.dark?#f3edf8:#212332;font-size:16px;font-weight:600;wrap:word-wrap;}
       Text {text:row.detail;color:root.dark?#a9a6b8:#656779;font-size:12px;wrap:word-wrap;}
      }
      TouchArea {clicked=>{root.selected(i);}}
     }
    }
   }
   if root.terminal : ScrollView {vertical-stretch:1;
    Text { text:root.output;color:root.dark?#c9e6d0:#183522;font-family:"monospace";font-size:13px;wrap:word-wrap; }
   }
   HorizontalLayout {spacing:8px;
    Button {text:root.primary;clicked=>{root.action(0);}}
    Button {text:root.secondary;clicked=>{root.action(1);}}
    Button {text:root.tertiary;clicked=>{root.action(2);}}
   }
   Text {text:root.status;color:root.dark?#f3a8c4:#9b3f66;font-size:12px;wrap:word-wrap;}
  }
 }
}
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum App {
    Settings,
    Home,
    Files,
    Core,
    Patch,
    Terminal,
}
struct Model {
    kind: App,
    root: PathBuf,
    cwd: PathBuf,
    selected: Option<PathBuf>,
    all: Vec<(String, String)>,
    visible: Vec<(String, String)>,
    settings: sakura_sdk::Settings,
    patch_stage: Option<String>,
    pty: Option<Pty>,
}
fn show(ui: &SakuraWindow, m: &mut Model, q: &str) {
    m.visible = m
        .all
        .iter()
        .filter(|(a, b)| {
            format!("{a} {b}")
                .to_lowercase()
                .contains(&q.to_lowercase())
        })
        .cloned()
        .collect();
    ui.set_rows(ModelRc::new(VecModel::from(
        m.visible
            .iter()
            .map(|(a, b)| Row {
                label: a.into(),
                detail: b.into(),
            })
            .collect::<Vec<_>>(),
    )));
}
fn hardware_rows() -> Vec<(String, String)> {
    let s = sakura_sdk::hardware::snapshot();
    let mut r = vec![
        (
            "Kernel".into(),
            s.kernel.unwrap_or_else(|| "Unavailable".into()),
        ),
        ("Architecture".into(), s.architecture),
        (
            "CPU online".into(),
            s.cpu_online.unwrap_or_else(|| "Unavailable".into()),
        ),
        (
            "Memory".into(),
            s.memory_kib.unwrap_or_else(|| "Unavailable".into()),
        ),
        (
            "Storage (total / available bytes)".into(),
            format!("{:?}", s.storage_bytes),
        ),
    ];
    for (p, f) in s.cpu_frequencies_khz {
        r.push((
            p,
            format!("{} kHz", f.unwrap_or_else(|| "Unavailable".into())),
        ));
    }
    for (p, c, st, t) in s.batteries {
        r.push((
            p,
            format!("capacity {c:?}% · {st:?} · temp {t:?} tenths °C"),
        ));
    }
    for (p, k, t) in s.thermals_millidegrees {
        r.push((k.unwrap_or(p), format!("{t:?} millidegrees °C")));
    }
    for (p, s) in s.zram {
        r.push((p, format!("mm_stat: {s:?}")));
    }
    r.push((
        "Network counters".into(),
        s.network.unwrap_or_else(|| "Unavailable".into()),
    ));
    r
}
fn refresh(ui: &SakuraWindow, m: &mut Model) -> anyhow::Result<()> {
    m.all = match m.kind {
        App::Settings | App::Core => {
            let mut r = hardware_rows();
            r.insert(
                0,
                (
                    "SakuraOS Developer Preview".into(),
                    "0.1.0 · portable Rust userspace · Android host".into(),
                ),
            );
            r.push((
                "Privileged daemon diagnostics".into(),
                std::fs::read_to_string(m.root.join("diagnostics.json")).unwrap_or_else(|_| {
                    "Tap Authorize diagnostics to request APatch approval, then Refresh.".into()
                }),
            ));
            r.push((
                "Display and host".into(),
                host("deviceInfo", "").unwrap_or_else(|_| "Unavailable on this host".into()),
            ));
            r
        }
        App::Home => [
            "Settings",
            "Home",
            "Files",
            "CoreManager",
            "Patch",
            "Terminal",
        ]
        .iter()
        .map(|s| {
            (
                format!("Sakura {s}"),
                "Tap to launch native Sakura application".into(),
            )
        })
        .collect(),
        App::Files => {
            ui.set_subtitle(m.cwd.display().to_string().into());
            let mut r = Vec::new();
            for e in std::fs::read_dir(&m.cwd)? {
                let e = e?;
                let md = e.metadata()?;
                if e.file_type()?.is_symlink() {
                    continue;
                }
                r.push((
                    e.file_name().to_string_lossy().into_owned(),
                    format!(
                        "{} · {} bytes",
                        if md.is_dir() { "Folder" } else { "File" },
                        md.len()
                    ),
                ));
            }
            r.sort_by_key(|a| a.0.to_lowercase());
            r
        }
        App::Patch => {
            let store = sakura_patch::Store::open(&m.root.join("updates"))?;
            vec![("Active generation".into(),format!("{:?}",store.state()?.active)),("Rollback history".into(),format!("{:?}",store.state()?.previous)),("Trust key".into(),"Place a trusted 32-byte raw Ed25519 public key in patch-public.key in this app's imported files. No default trusted key.".into()),("Package format".into(),"Signed JSON .patch · immutable owned-file generations · no system writes".into())]
        }
        App::Terminal => vec![],
    };
    show(ui, m, "");
    Ok(())
}
fn safe_name(n: &str) -> anyhow::Result<()> {
    sakura_patch::filename(n)
}
fn file_command(m: &mut Model, text: &str) -> anyhow::Result<String> {
    let (cmd, arg) = text.split_once(' ').unwrap_or((text, ""));
    let selected = || {
        m.selected
            .clone()
            .ok_or_else(|| anyhow::anyhow!("select a file first"))
    };
    match cmd{
 "mkdir"=>{safe_name(arg)?;std::fs::create_dir(m.cwd.join(arg))?;},
 "new"=>{safe_name(arg)?;std::fs::OpenOptions::new().write(true).create_new(true).open(m.cwd.join(arg))?;},
 "rename"|"move"=>{safe_name(arg)?;let dest=m.cwd.join(arg);anyhow::ensure!(!dest.exists(),"destination exists");std::fs::rename(selected()?,dest)?;},
 "copy"=>{safe_name(arg)?;let src=selected()?;anyhow::ensure!(src.is_file(),"copy supports regular files");let mut input=std::fs::File::open(src)?;let mut output=std::fs::OpenOptions::new().write(true).create_new(true).open(m.cwd.join(arg))?;std::io::copy(&mut input,&mut output)?;},
 "delete-confirm"=>{let src=selected()?;if src.is_dir(){std::fs::remove_dir(src)?;}else{std::fs::remove_file(src)?;}m.selected=None;},
 "import"=>{return host("importDocument","");},
 "export"=>{let src=selected()?;anyhow::ensure!(src.is_file(),"select a regular file");return host("exportDocument",src.to_str().unwrap_or(""));},
 _=>anyhow::bail!("mkdir NAME | new NAME | copy NAME | move NAME | rename NAME | delete-confirm | import | export"),
 }
    Ok("Done".into())
}
fn action(ui: &SakuraWindow, m: &mut Model, n: i32) -> anyhow::Result<String> {
    if n == 9 {
        m.settings
            .values
            .insert("dark".into(), ui.get_dark().to_string());
        m.settings.save(&m.root.join("settings.json"))?;
        return Ok("Appearance saved".into());
    }
    if n == 3 {
        return host("importDocument", "");
    }
    let text = ui.get_field().to_string();
    match m.kind {
        App::Settings | App::Core => {
            if m.kind == App::Core && n == 2 {
                return host("requestDiagnostics", "");
            }
            refresh(ui, m)?;
            Ok("Read-only snapshot refreshed".into())
        }
        App::Home => {
            refresh(ui, m)?;
            Ok("Six Sakura apps".into())
        }
        App::Files => {
            let result = if n == 1 {
                if m.cwd != m.root.join("files") {
                    m.cwd.pop();
                }
                m.selected = None;
                "Parent directory".into()
            } else if n == 2 {
                file_command(m, &text)?
            } else {
                "Directory refreshed".into()
            };
            refresh(ui, m)?;
            Ok(result)
        }
        App::Patch => {
            if n == 2 {
                let store = sakura_patch::Store::open(&m.root.join("updates"))?;
                store.rollback()?;
                refresh(ui, m)?;
                return Ok("Rollback completed".into());
            }
            if n == 1 {
                let id = m
                    .patch_stage
                    .as_ref()
                    .ok_or_else(|| anyhow::anyhow!("verify and stage first"))?;
                sakura_patch::Store::open(&m.root.join("updates"))?.activate(id)?;
                refresh(ui, m)?;
                return Ok("Owned-file generation activated; no running code patched".into());
            }
            safe_name(&text)?;
            let key_bytes = std::fs::read(m.root.join("files/patch-public.key"))?;
            let key = ed25519_dalek::VerifyingKey::from_bytes(
                &key_bytes
                    .try_into()
                    .map_err(|_| anyhow::anyhow!("key must be 32 bytes"))?,
            )?;
            let file = m.root.join("files").join(text);
            sakura_patch::verify_file(&file, &key)?;
            let id = sakura_patch::Store::open(&m.root.join("updates"))?
                .stage(&sakura_patch::read_package(&file)?, &key)?;
            m.patch_stage = Some(id);
            Ok("Verified and staged. Apply activates owned files.".into())
        }
        App::Terminal => {
            if n == 0 {
                m.pty
                    .as_mut()
                    .ok_or_else(|| anyhow::anyhow!("PTY unavailable"))?
                    .send(&text)?;
                ui.set_field("".into());
                Ok("Command sent as app UID".into())
            } else if n == 1 {
                ui.set_output("".into());
                Ok("Display cleared".into())
            } else {
                Ok("One PTY session; close app to terminate shell".into())
            }
        }
    }
}
pub fn run(kind: App, root: PathBuf) -> anyhow::Result<()> {
    std::fs::create_dir_all(root.join("files"))?;
    let settings = sakura_sdk::Settings::load(&root.join("settings.json"))?;
    let ui = SakuraWindow::new()?;
    ui.set_dark(settings.values.get("dark").is_none_or(|s| s == "true"));
    let (title, sub, primary, secondary) = match kind {
        App::Settings => ("Settings", "Your device, at a glance", "Refresh", "Refresh"),
        App::Home => ("Home", "A native place to begin", "Refresh", "Refresh"),
        App::Files => (
            "Files",
            "Private files · import/export through Android's document picker",
            "Refresh",
            "Parent",
        ),
        App::Core => (
            "SakuraCore",
            "Live hardware snapshot · read-only",
            "Refresh",
            "Refresh",
        ),
        App::Patch => (
            "SakuraPatch",
            "Signed updates for Sakura-owned development files",
            "Verify & stage",
            "Apply staged",
        ),
        App::Terminal => (
            "Terminal",
            "Private shell · app UID · no automatic root",
            "Send",
            "Clear",
        ),
    };
    ui.set_heading(title.into());
    ui.set_subtitle(sub.into());
    ui.set_primary(primary.into());
    ui.set_secondary(secondary.into());
    ui.set_terminal(kind == App::Terminal);
    ui.set_documents(matches!(kind, App::Files | App::Patch));
    ui.set_tertiary(
        match kind {
            App::Files => "Run command",
            App::Patch => "Rollback",
            App::Core => "Authorize diagnostics",
            App::Terminal => "Session info",
            _ => "Refresh",
        }
        .into(),
    );
    let model = Rc::new(RefCell::new(Model {
        kind,
        root: root.clone(),
        cwd: root.join("files"),
        selected: None,
        all: vec![],
        visible: vec![],
        settings,
        patch_stage: None,
        pty: None,
    }));
    if kind == App::Terminal {
        match Pty::new(Some(ui.as_weak())) {
            Ok(mut p) => {
                if let Ok(path) = host("cliPath", "") {
                    let _ = p.send(&format!("alias sakura='{}'", path));
                }
                model.borrow_mut().pty = Some(p)
            }
            Err(e) => ui.set_status(format!("PTY unavailable: {e}").into()),
        }
    }
    refresh(&ui, &mut model.borrow_mut())?;
    let w = ui.as_weak();
    let m = model.clone();
    ui.on_search(move |q| {
        if let Some(ui) = w.upgrade() {
            let mut m = m.borrow_mut();
            if !matches!(m.kind, App::Terminal | App::Patch) {
                show(&ui, &mut m, &q);
            }
        }
    });
    let w = ui.as_weak();
    let m = model.clone();
    ui.on_action(move |n| {
        if let Some(ui) = w.upgrade() {
            let result = action(&ui, &mut m.borrow_mut(), n);
            ui.set_status(
                match result {
                    Ok(s) => s,
                    Err(e) => format!("{e:#}"),
                }
                .into(),
            );
        }
    });
    let w = ui.as_weak();
    let m = model.clone();
    ui.on_selected(move |i| {
        if let Some(ui) = w.upgrade() {
            let mut m = m.borrow_mut();
            let Some((label, _)) = m.visible.get(i as usize).cloned() else {
                return;
            };
            match m.kind {
                App::Home => {
                    let package = format!(
                        "dev.sakura.{}",
                        label.trim_start_matches("Sakura ").to_lowercase()
                    );
                    ui.set_status(
                        host("launchApp", &package)
                            .unwrap_or_else(|e| e.to_string())
                            .into(),
                    );
                }
                App::Files => {
                    let p = m.cwd.join(&label);
                    if p.is_dir() {
                        m.cwd = p;
                        m.selected = None;
                        if let Err(e) = refresh(&ui, &mut m) {
                            ui.set_status(e.to_string().into());
                        }
                    } else {
                        m.selected = Some(p.clone());
                        let preview = std::fs::File::open(&p)
                            .and_then(|f| {
                                use std::io::Read;
                                let mut b = Vec::new();
                                f.take(4096).read_to_end(&mut b)?;
                                Ok(String::from_utf8(b)
                                    .unwrap_or_else(|_| "Binary file · preview unavailable".into()))
                            })
                            .unwrap_or_else(|e| e.to_string());
                        ui.set_status(format!("Selected {label}: {preview}").into());
                    }
                }
                _ => {}
            }
        }
    });
    let mut runtime = sakura_sdk::Runtime::new(title);
    runtime.activate()?;
    ui.run()?;
    runtime.shutdown();
    Ok(())
}
#[cfg(target_os = "android")]
fn host(method: &str, arg: &str) -> anyhow::Result<String> {
    use jni::{
        objects::{JObject, JString, JValue},
        JavaVM,
    };
    let ctx = ndk_context::android_context();
    // SAFETY: ndk-context's pointers are provided by the live NativeActivity.
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    let result = (|| -> anyhow::Result<String> {
        let j = env.new_string(arg)?;
        let out = env
            .call_method(
                &activity,
                method,
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&j)],
            )?
            .l()?;
        let s = JString::from(out);
        let value: String = env.get_string(&s)?.into();
        Ok(value)
    })();
    // The activity pointer is a borrowed global reference, not an owned local reference.
    std::mem::forget(activity);
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    result
}
#[cfg(not(target_os = "android"))]
fn host(_method: &str, _arg: &str) -> anyhow::Result<String> {
    anyhow::bail!("Android host operation unavailable on Linux")
}
fn decode_utf8(pending: &mut Vec<u8>) -> String {
    let mut out = String::new();
    let mut consumed = 0;
    while consumed < pending.len() {
        match std::str::from_utf8(&pending[consumed..]) {
            Ok(s) => {
                out.push_str(s);
                consumed = pending.len();
            }
            Err(e) => {
                let valid = consumed + e.valid_up_to();
                out.push_str(std::str::from_utf8(&pending[consumed..valid]).unwrap());
                consumed = valid;
                match e.error_len() {
                    Some(n) => {
                        out.push('\u{fffd}');
                        consumed += n;
                    }
                    None => break,
                }
            }
        }
    }
    pending.drain(..consumed);
    out
}
struct Pty {
    master: std::fs::File,
    child: std::process::Child,
    #[cfg(test)]
    output: std::sync::Arc<std::sync::Mutex<Vec<u8>>>,
}
impl Pty {
    fn new(ui_weak: Option<slint::Weak<SakuraWindow>>) -> anyhow::Result<Self> {
        use std::{
            io::Read,
            os::{
                fd::{AsRawFd, FromRawFd},
                unix::process::CommandExt,
            },
        };
        // SAFETY: libc PTY calls use valid descriptors and a correctly sized name buffer.
        let fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC) };
        anyhow::ensure!(fd >= 0, "posix_openpt: {}", std::io::Error::last_os_error());
        let master = unsafe { std::fs::File::from_raw_fd(fd) };
        anyhow::ensure!(
            unsafe { libc::grantpt(fd) } == 0 && unsafe { libc::unlockpt(fd) } == 0,
            "PTY grant/unlock failed"
        );
        let mut name = [0 as libc::c_char; 256];
        anyhow::ensure!(
            unsafe { libc::ptsname_r(fd, name.as_mut_ptr(), name.len()) } == 0,
            "PTY name failed"
        );
        let name = unsafe { std::ffi::CStr::from_ptr(name.as_ptr()) };
        let slave = unsafe {
            libc::open(
                name.as_ptr(),
                libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC,
            )
        };
        anyhow::ensure!(slave >= 0, "slave open failed");
        let slave = unsafe { std::fs::File::from_raw_fd(slave) };
        let shell = if cfg!(target_os = "android") {
            "/system/bin/sh"
        } else {
            "/bin/sh"
        };
        let mut cmd = std::process::Command::new(shell);
        cmd.env("TERM", "dumb")
            .stdin(slave.try_clone()?)
            .stdout(slave.try_clone()?)
            .stderr(slave.try_clone()?);
        let sfd = slave.as_raw_fd();
        unsafe {
            cmd.pre_exec(move || {
                if libc::setsid() < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                if libc::ioctl(sfd, libc::TIOCSCTTY, 0) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let child = cmd.spawn()?;
        let output = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let o = output.clone();
        let mut reader = master.try_clone()?;
        let scheduled = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let output_for_event = output.clone();
        std::thread::spawn(move || {
            let mut buf = [0; 4096];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let mut o = o.lock().unwrap_or_else(|e| e.into_inner());
                        if o.len() + n > 65536 {
                            let excess = (o.len() + n).saturating_sub(65536);
                            o.drain(..excess);
                        }
                        o.extend_from_slice(&buf[..n]);
                        drop(o);
                        if let Some(weak) = &ui_weak {
                            if !scheduled.swap(true, std::sync::atomic::Ordering::AcqRel) {
                                let output = output_for_event.clone();
                                let flag = scheduled.clone();
                                let _ = weak.upgrade_in_event_loop(move |ui| {
                                    let mut pending =
                                        output.lock().unwrap_or_else(|e| e.into_inner());
                                    let text = decode_utf8(&mut pending);
                                    flag.store(false, std::sync::atomic::Ordering::Release);
                                    drop(pending);
                                    let mut out = ui.get_output().to_string();
                                    out.push_str(&text);
                                    if out.len() > 65536 {
                                        let mut boundary = out.len() - 65536;
                                        while !out.is_char_boundary(boundary) {
                                            boundary += 1;
                                        }
                                        out.drain(..boundary);
                                    }
                                    ui.set_output(out.into());
                                });
                            }
                        }
                    }
                }
            }
        });
        Ok(Self {
            master,
            child,
            #[cfg(test)]
            output,
        })
    }
    fn send(&mut self, s: &str) -> anyhow::Result<()> {
        use std::io::Write;
        self.master.write_all(s.as_bytes())?;
        self.master.write_all(b"\n")?;
        Ok(())
    }
}
impl Drop for Pty {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn utf8_split_across_pty_reads() {
        let bytes = "桜".as_bytes();
        let mut pending = bytes[..2].to_vec();
        assert_eq!(decode_utf8(&mut pending), "");
        pending.extend_from_slice(&bytes[2..]);
        assert_eq!(decode_utf8(&mut pending), "桜");
        assert!(pending.is_empty());
    }
    #[test]
    fn pty_shell() {
        let mut p = Pty::new(None).unwrap();
        p.send("printf sakura_pty_test").unwrap();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_millis(25));
            if String::from_utf8_lossy(&p.output.lock().unwrap()).contains("sakura_pty_test") {
                return;
            }
        }
        panic!("shell output missing");
    }
    #[test]
    fn native_ui_renders_theme_and_controls() {
        use slint::platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType};
        struct Headless {
            window: Rc<MinimalSoftwareWindow>,
        }
        impl slint::platform::Platform for Headless {
            fn create_window_adapter(
                &self,
            ) -> Result<Rc<dyn slint::platform::WindowAdapter>, slint::PlatformError> {
                Ok(self.window.clone())
            }
        }
        let window = MinimalSoftwareWindow::new(RepaintBufferType::ReusedBuffer);
        slint::platform::set_platform(Box::new(Headless {
            window: window.clone(),
        }))
        .unwrap();
        let ui = SakuraWindow::new().unwrap();
        ui.window().set_size(slint::PhysicalSize::new(420, 820));
        ui.set_heading("Sakura Settings".into());
        ui.set_subtitle("Native Rust developer preview".into());
        ui.set_rows(ModelRc::new(VecModel::from(vec![
            Row {
                label: "Battery".into(),
                detail: "Read-only hardware snapshot".into(),
            },
            Row {
                label: "Appearance".into(),
                detail: "Light and dark themes".into(),
            },
        ])));
        ui.show().unwrap();
        slint::platform::update_timers_and_animations();
        let mut pixels = vec![slint::Rgb8Pixel::default(); 420 * 820];
        let mut rendered = false;
        window.draw_if_needed(|renderer| {
            renderer.render(&mut pixels, 420);
            rendered = true;
        });
        assert!(rendered);
        assert!(
            pixels.windows(2).filter(|p| p[0] != p[1]).count() > 500,
            "UI must contain visible text and controls"
        );
        let before = pixels.clone();
        ui.set_dark(false);
        ui.window().request_redraw();
        window.draw_if_needed(|renderer| {
            renderer.render(&mut pixels, 420);
        });
        assert!(
            pixels.iter().zip(before).filter(|(a, b)| **a != *b).count() > 10000,
            "theme must change rendered pixels"
        );
        ui.hide().unwrap();
    }
}
