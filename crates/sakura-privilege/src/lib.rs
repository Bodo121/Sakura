use serde::Serialize;
#[derive(Clone, Copy, Debug, Serialize)]
pub enum Capability {
    ReadHardware,
    ReadServices,
    ManageOwnedFiles,
    StageOwnedUpdate,
    RestartOwnedService,
}
#[derive(Debug, Serialize)]
pub enum Decision {
    Granted,
    ApprovalRequired,
    Unavailable,
}
pub trait Provider {
    fn check(&self, capability: Capability) -> Decision;
}
pub struct AndroidHost;
impl Provider for AndroidHost {
    fn check(&self, c: Capability) -> Decision {
        match c {
            Capability::ReadHardware | Capability::ManageOwnedFiles => Decision::Granted,
            _ => Decision::ApprovalRequired,
        }
    }
}
pub fn apatch_detected() -> bool {
    std::path::Path::new("/data/adb/ap").exists()
}
