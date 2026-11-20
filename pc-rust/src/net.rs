//! Síť: zjištění IP adresy a mDNS reklama.

use crate::state::Shared;
use std::sync::Arc;

pub fn local_ip(shared: &Shared) -> String {
    let o = shared.lock().config.ip_override.trim().to_string();
    if !o.is_empty() {
        return o;
    }
    local_ip_address::local_ip().map(|i| i.to_string()).unwrap_or_else(|_| "127.0.0.1".into())
}

pub fn pair_uri(shared: &Shared) -> String {
    let ip = local_ip(shared);
    let g = shared.lock();
    format!(
        "tapdrop://pair?ip={}&port={}&id={}&token={}",
        ip, g.config.port, g.config.notebook_id, g.config.token
    )
}

pub fn start_mdns(shared: &Arc<Shared>) -> Option<mdns_sd::ServiceDaemon> {
    let daemon = match mdns_sd::ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            tracing::warn!("mDNS není dostupné: {e}");
            return None;
        }
    };
    let (id, port) = {
        let g = shared.lock();
        (g.config.notebook_id.clone(), g.config.port)
    };
    let ip = local_ip(shared);
    let host = hostname::get().map(|h| h.to_string_lossy().to_string()).unwrap_or_else(|_| "tapdrop".into());
    let host = format!("{}.local.", host.replace(' ', "-"));
    let props = [("id", id.as_str()), ("v", crate::state::VERSION)];
    match mdns_sd::ServiceInfo::new("_tapdrop._tcp.local.", &format!("TapDrop-{id}"), &host, ip.as_str(), port, &props[..]) {
        Ok(info) => {
            let info = info.enable_addr_auto();
            match daemon.register(info) {
                Ok(_) => tracing::info!("mDNS: _tapdrop._tcp.local. na {ip}:{port}"),
                Err(e) => tracing::warn!("mDNS registrace selhala: {e}"),
            }
        }
        Err(e) => tracing::warn!("mDNS info: {e}"),
    }
    Some(daemon)
}
