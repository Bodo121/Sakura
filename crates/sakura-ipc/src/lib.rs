use anyhow::{ensure, Result};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::{
    io::{Read, Write},
    os::{fd::AsRawFd, unix::net::UnixStream},
    time::Duration,
};
pub const VERSION: u16 = 1;
pub const MAX_FRAME: usize = 256 * 1024;
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Command {
    Status,
    Hardware,
    Services,
    Diagnostics,
    Shutdown,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub version: u16,
    pub id: u64,
    pub command: Command,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Response {
    pub version: u16,
    pub id: u64,
    pub ok: bool,
    pub data: serde_json::Value,
}
pub fn configure(s: &UnixStream) -> Result<()> {
    s.set_read_timeout(Some(Duration::from_secs(2)))?;
    s.set_write_timeout(Some(Duration::from_secs(2)))?;
    Ok(())
}
pub fn peer_uid(s: &UnixStream) -> Result<u32> {
    let mut cred = std::mem::MaybeUninit::<libc::ucred>::uninit();
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: kernel receives a correctly sized writable ucred buffer and a valid socket fd.
    let r = unsafe {
        libc::getsockopt(
            s.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            cred.as_mut_ptr().cast(),
            &mut len,
        )
    };
    ensure!(
        r == 0 && len as usize == std::mem::size_of::<libc::ucred>(),
        "peer credentials unavailable"
    );
    // SAFETY: getsockopt succeeded with the full struct length.
    Ok(unsafe { cred.assume_init() }.uid)
}
pub fn authorize(s: &UnixStream, owner: u32) -> Result<()> {
    ensure!(peer_uid(s)? == owner, "unauthorized peer UID");
    Ok(())
}
pub fn send<T: Serialize>(s: &mut UnixStream, value: &T) -> Result<()> {
    let b = serde_json::to_vec(value)?;
    ensure!(b.len() <= MAX_FRAME, "frame too large");
    s.write_all(&(b.len() as u32).to_be_bytes())?;
    s.write_all(&b)?;
    Ok(())
}
pub fn recv<T: DeserializeOwned>(s: &mut UnixStream) -> Result<T> {
    let mut n = [0; 4];
    s.read_exact(&mut n)?;
    let n = u32::from_be_bytes(n) as usize;
    ensure!(n > 0 && n <= MAX_FRAME, "invalid frame length");
    let mut b = vec![0; n];
    s.read_exact(&mut b)?;
    Ok(serde_json::from_slice(&b)?)
}
pub fn request(path: &std::path::Path, command: Command) -> Result<Response> {
    let mut s = UnixStream::connect(path)?;
    configure(&s)?;
    // SAFETY: geteuid has no preconditions.
    authorize(&s, unsafe { libc::geteuid() })?;
    send(
        &mut s,
        &Request {
            version: VERSION,
            id: 1,
            command,
        },
    )?;
    let r: Response = recv(&mut s)?;
    ensure!(r.version == VERSION && r.id == 1, "protocol mismatch");
    ensure!(r.ok, "service rejected request: {}", r.data);
    Ok(r)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn typed_roundtrip() {
        let (mut a, mut b) = UnixStream::pair().unwrap();
        send(
            &mut a,
            &Request {
                version: VERSION,
                id: 9,
                command: Command::Status,
            },
        )
        .unwrap();
        let r: Request = recv(&mut b).unwrap();
        assert_eq!(r.id, 9);
    }
    #[test]
    fn reject_large() {
        let (mut a, mut b) = UnixStream::pair().unwrap();
        a.write_all(&u32::MAX.to_be_bytes()).unwrap();
        assert!(recv::<Request>(&mut b).is_err());
    }
    #[test]
    fn credentials() {
        let (a, _) = UnixStream::pair().unwrap();
        assert!(authorize(&a, u32::MAX).is_err());
        assert_eq!(peer_uid(&a).unwrap(), unsafe { libc::geteuid() });
    }
    #[test]
    fn timeout() {
        let (a, _) = UnixStream::pair().unwrap();
        configure(&a).unwrap();
        assert_eq!(a.read_timeout().unwrap(), Some(Duration::from_secs(2)));
    }
}
