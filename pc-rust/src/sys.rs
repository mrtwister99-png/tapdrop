//! Systémové věci pro Windows: autostart, zobrazení okna, otevření v Průzkumníku.

use std::path::Path;

pub const WINDOW_TITLE: &str = "TapDrop";

#[cfg(windows)]
pub fn set_autostart(enable: bool) {
    use winreg::{enums::*, RegKey};
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    match hkcu.create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Run") {
        Ok((key, _)) => {
            if enable {
                if let Ok(exe) = std::env::current_exe() {
                    let v = format!("\"{}\" --minimized", exe.display());
                    if let Err(e) = key.set_value("TapDrop", &v) {
                        tracing::warn!("Autostart: {e}");
                    }
                }
            } else {
                let _ = key.delete_value("TapDrop");
            }
        }
        Err(e) => tracing::warn!("Autostart registr: {e}"),
    }
}
#[cfg(not(windows))]
pub fn set_autostart(_enable: bool) {}

/// Zobrazí okno přímo přes Win32 (funguje i když je okno skryté a smyčka egui nekreslí).
#[cfg(windows)]
pub fn win_show() {
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    let title: Vec<u16> = WINDOW_TITLE.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        let hwnd = FindWindowW(std::ptr::null(), title.as_ptr());
        if !hwnd.is_null() {
            ShowWindow(hwnd, SW_SHOW);
            ShowWindow(hwnd, SW_RESTORE);
            SetForegroundWindow(hwnd);
        }
    }
}
#[cfg(not(windows))]
pub fn win_show() {}

pub fn reveal(path: &Path) {
    let _ = std::process::Command::new("explorer").arg(format!("/select,{}", path.display())).spawn();
}

pub fn open_dir(path: &Path) {
    let _ = std::process::Command::new("explorer").arg(path).spawn();
}
