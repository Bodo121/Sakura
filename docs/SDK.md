# Rust application SDK

A native app depends on `sakura-sdk` for portable runtime, settings, logging, hardware, IPC and privilege decision types. `sakura-ui` owns the shared design, model and platform seams. The six crates under `apps` are working entry-point examples. Android requires a `cdylib` with exported `android_main`; it initializes Slint's Android backend and passes the private app directory into portable UI code. Linux binaries call the same model directly.

```rust
let mut runtime = sakura_sdk::Runtime::new("dev.example.app");
runtime.activate()?;
let hardware = sakura_sdk::hardware::snapshot();
sakura_sdk::log("hardware_loaded", &format!("{:?}", hardware.kernel));
runtime.shutdown();
```

Hardware fields are `Option` where unavailable. Do not substitute guessed temperatures, battery capacity or refresh rates. CPU times are cumulative counters, not an instantaneous percent. Thermal units are millidegrees; Android battery sysfs temperature is reported separately with source units.

Settings use a caller-owned file and atomic same-directory replacement. Create the private directory first. Do not store secrets in shared external storage. UI theme changes persist in private `settings.json`. IPC clients use `sakura_sdk::ipc::request` with a known private service path and typed commands. A failed socket connection is a real error; ordinary APKs cannot directly open the root daemon socket.

The runtime cancellation flag allows cooperative task cancellation; it does not yet supply a thread pool or executor. Capability enums describe intent; a complete authorization/audit service is future work. Do not treat a type declaration as an OS-enforced sandbox.
