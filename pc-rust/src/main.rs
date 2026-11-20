#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod net;
mod server;
mod state;
mod sys;
mod ui;

use state::Shared;
use std::sync::Arc;

fn main() {
    let shared = Shared::load();
    let _log_guard = init_logging(&shared);
    std::panic::set_hook(Box::new(|info| {
        tracing::error!("PANIC: {info}");
    }));
    tracing::info!("TapDrop {} startuje", state::VERSION);

    let port = shared.lock().config.port;
    let listener = match std::net::TcpListener::bind(("0.0.0.0", port)) {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("Nelze otevřít port {port}: {e}");
            rfd::MessageDialog::new()
                .set_title("TapDrop")
                .set_description(format!(
                    "Nelze otevřít port {port}.\nPravděpodobně už TapDrop běží (hledej ikonu u hodin), nebo port používá jiný program.\n\n{e}"
                ))
                .set_level(rfd::MessageLevel::Error)
                .show();
            return;
        }
    };
    let _ = listener.set_nonblocking(true);

    let rt = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("Tokio: {e}");
            return;
        }
    };
    let s2 = shared.clone();
    rt.spawn(async move { server::run(s2, listener).await });

    // mDNS reklama _tapdrop._tcp.local.
    let _mdns = net::start_mdns(&shared);

    let start_hidden = std::env::args().any(|a| a == "--minimized");
    ui::run(shared.clone(), start_hidden);
    tracing::info!("TapDrop končí");
    rt.shutdown_background();
}

fn init_logging(shared: &Arc<Shared>) -> Option<tracing_appender::non_blocking::WorkerGuard> {
    let dir = shared.dir.join("logs");
    let _ = std::fs::create_dir_all(&dir);
    let appender = tracing_appender::rolling::daily(&dir, "tapdrop.log");
    let (nb, guard) = tracing_appender::non_blocking(appender);
    tracing_subscriber::fmt()
        .with_writer(nb)
        .with_ansi(false)
        .with_max_level(tracing::Level::INFO)
        .init();
    Some(guard)
}
