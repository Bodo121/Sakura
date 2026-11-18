fn main() {
    let root = std::env::var_os("SAKURA_APP_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("sakura-files"));
    if let Err(e) = sakura_files::run(root) {
        eprintln!("{e}");
        std::process::exit(1);
    }
}
