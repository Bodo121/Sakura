#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: slint::android::AndroidApp) {
    let root = app
        .internal_data_path()
        .expect("Android private directory unavailable");
    slint::android::init(app).expect("Android backend initialization failed");
    if let Err(e) = sakura_ui::run(sakura_ui::App::Files, root) {
        eprintln!("Sakura: {e:#}");
    }
}
pub fn run(root: std::path::PathBuf) -> Result<(), Box<dyn std::error::Error>> {
    sakura_ui::run(sakura_ui::App::Files, root).map_err(Into::into)
}
