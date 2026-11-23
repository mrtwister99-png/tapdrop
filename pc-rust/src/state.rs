//! Sdílený stav aplikace: konfigurace, fronta souborů pro telefon, přijaté soubory, průběh přenosů.

use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const DEFAULT_PORT: u16 = 8765;

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub notebook_id: String,
    pub token: String,
    pub port: u16,
    pub download_dir: PathBuf,
    pub autostart: bool,
    pub ip_override: String,
    pub win_w: f32,
    pub win_h: f32,
    pub last_folder: Option<PathBuf>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            notebook_id: gen_notebook_id(),
            token: gen_token(),
            port: DEFAULT_PORT,
            download_dir: default_download_dir(),
            autostart: false,
            ip_override: String::new(),
            win_w: 980.0,
            win_h: 680.0,
            last_folder: None,
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct QueueItem {
    pub id: String,
    pub name: String,
    pub path: PathBuf,
    pub size: u64,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Received {
    pub name: String,
    pub path: PathBuf,
    pub size: u64,
    /// UNIX čas přijetí (sekundy)
    pub time: u64,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub name: String,
    pub size: u64,
    /// true = z telefonu na PC, false = z PC do telefonu
    pub from_phone: bool,
    pub time: u64,
}

#[derive(Clone)]
pub struct Progress {
    pub name: String,
    pub done: u64,
    pub total: u64,
    pub upload: bool, // true = z telefonu na PC, false = z PC do telefonu
}

pub struct Inner {
    pub config: Config,
    pub queue: Vec<QueueItem>,
    pub received: Vec<Received>,
    pub history: Vec<HistoryEntry>,
    pub progress: HashMap<String, Progress>,
    pub odhoz_text: String,
    /// Poslední ověřený požadavek z telefonu: (IP, čas)
    pub phone_seen: Option<(String, std::time::Instant)>,
}

pub struct Shared {
    inner: Mutex<Inner>,
    pub dir: PathBuf,
    ctx: Mutex<Option<eframe::egui::Context>>,
}

pub fn gen_token() -> String {
    const CH: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    let mut r = rand::thread_rng();
    (0..32).map(|_| CH[r.gen_range(0..CH.len())] as char).collect()
}

pub fn gen_notebook_id() -> String {
    const CH: &[u8] = b"abcdefghjkmnpqrstuvwxyz23456789";
    let mut r = rand::thread_rng();
    let tail: String = (0..4).map(|_| CH[r.gen_range(0..CH.len())] as char).collect();
    format!("nb{tail}")
}

pub fn gen_id() -> String {
    let mut r = rand::thread_rng();
    format!("{:016x}", r.gen::<u64>())
}

pub fn default_download_dir() -> PathBuf {
    dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")).join("TapDrop")
}

pub fn config_dir() -> PathBuf {
    dirs::config_dir().unwrap_or_else(|| PathBuf::from(".")).join("TapDrop")
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Očistí název souboru: žádné cesty, žádné zakázané znaky, žádné rezervované názvy Windows.
pub fn sanitize_name(raw: &str) -> String {
    let last = raw.rsplit(|c| c == '/' || c == '\\').next().unwrap_or("");
    let cleaned: String = last
        .chars()
        .filter(|c| !c.is_control())
        .map(|c| if "<>:\"|?*".contains(c) { '_' } else { c })
        .collect();
    let mut name = cleaned.trim_matches(|c: char| c == '.' || c == ' ').to_string();
    if name.chars().count() > 200 {
        name = name.chars().take(200).collect();
    }
    let stem = name.split('.').next().unwrap_or("").to_ascii_uppercase();
    const RESERVED: &[&str] = &[
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];
    if name.is_empty() || RESERVED.contains(&stem.as_str()) {
        name = format!("soubor_{name}");
        if name == "soubor_" {
            name = "soubor".into();
        }
    }
    name
}

/// Vrátí cestu, která ještě neexistuje ("a.txt" -> "a (1).txt").
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let p = Path::new(name);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or("soubor");
    let ext = p.extension().and_then(|s| s.to_str());
    for i in 1..10000 {
        let cand = match ext {
            Some(e) => format!("{stem} ({i}).{e}"),
            None => format!("{stem} ({i})"),
        };
        let path = dir.join(cand);
        if !path.exists() {
            return path;
        }
    }
    dir.join(format!("{}_{}", gen_id(), name))
}

fn load_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Option<T> {
    let s = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&s).ok()
}

fn save_json<T: Serialize>(path: &Path, v: &T) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match serde_json::to_string_pretty(v) {
        Ok(s) => {
            let tmp = path.with_extension("tmp");
            if std::fs::write(&tmp, s).is_ok() {
                if let Err(e) = std::fs::rename(&tmp, path) {
                    tracing::warn!("Nelze uložit {}: {e}", path.display());
                }
            }
        }
        Err(e) => tracing::warn!("Serializace selhala: {e}"),
    }
}

impl Shared {
    pub fn load() -> Arc<Shared> {
        let dir = config_dir();
        let _ = std::fs::create_dir_all(&dir);
        let config: Config = load_json(&dir.join("config.json")).unwrap_or_default();
        let mut queue: Vec<QueueItem> = load_json(&dir.join("queue.json")).unwrap_or_default();
        queue.retain(|q| q.path.is_file());
        let received: Vec<Received> = load_json(&dir.join("received.json")).unwrap_or_default();
        let history: Vec<HistoryEntry> = load_json(&dir.join("history.json")).unwrap_or_default();
        let s = Arc::new(Shared {
            inner: Mutex::new(Inner { config, queue, received, history, progress: HashMap::new(), odhoz_text: String::new(), phone_seen: None }),
            dir,
            ctx: Mutex::new(None),
        });
        s.save_config();
        s.save_queue();
        let _ = std::fs::create_dir_all(&s.lock().config.download_dir);
        s
    }

    pub fn lock(&self) -> MutexGuard<'_, Inner> {
        match self.inner.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }

    pub fn set_ctx(&self, ctx: eframe::egui::Context) {
        *self.ctx.lock().unwrap_or_else(|p| p.into_inner()) = Some(ctx);
    }

    pub fn repaint(&self) {
        if let Some(c) = self.ctx.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
            c.request_repaint();
        }
    }

    pub fn token(&self) -> String {
        self.lock().config.token.clone()
    }

    pub fn touch_phone(&self, ip: String) {
        self.lock().phone_seen = Some((ip, std::time::Instant::now()));
    }

    /// Telefon je „připojen“, pokud se ozval v posledních 10 s (pending se volá každé 3 s).
    pub fn connected_phone(&self) -> Option<String> {
        match &self.lock().phone_seen {
            Some((ip, t)) if t.elapsed() < std::time::Duration::from_secs(10) => Some(ip.clone()),
            _ => None,
        }
    }

    pub fn odhoz_text(&self) -> String {
        self.lock().odhoz_text.clone()
    }

    pub fn set_odhoz_text(&self, text: String) {
        self.lock().odhoz_text = text;
        self.repaint();
    }

    pub fn save_config(&self) {
        let c = self.lock().config.clone();
        save_json(&self.dir.join("config.json"), &c);
    }

    pub fn save_queue(&self) {
        let q = self.lock().queue.clone();
        save_json(&self.dir.join("queue.json"), &q);
    }

    pub fn save_received(&self) {
        let r = self.lock().received.clone();
        save_json(&self.dir.join("received.json"), &r);
    }

    pub fn add_to_queue(&self, path: &Path) -> Result<(), String> {
        let meta = std::fs::metadata(path).map_err(|e| format!("{}: {e}", path.display()))?;
        if !meta.is_file() {
            return Err(format!("{} není soubor (složky zatím nejsou podporovány)", path.display()));
        }
        let name = sanitize_name(&path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default());
        {
            let mut g = self.lock();
            if g.queue.iter().any(|q| q.path == path) {
                return Ok(());
            }
            g.queue.push(QueueItem { id: gen_id(), name, path: path.to_path_buf(), size: meta.len() });
            if let Some(parent) = path.parent() {
                g.config.last_folder = Some(parent.to_path_buf());
            }
        }
        self.save_queue();
        self.save_config();
        tracing::info!("Do fronty přidáno: {}", path.display());
        Ok(())
    }

    pub fn add_history(&self, name: &str, size: u64, from_phone: bool) {
        let h = {
            let mut g = self.lock();
            g.history.insert(0, HistoryEntry { name: name.to_string(), size, from_phone, time: now_secs() });
            g.history.truncate(1000);
            g.history.clone()
        };
        save_json(&self.dir.join("history.json"), &h);
    }

    pub fn clear_history(&self) {
        self.lock().history.clear();
        save_json(&self.dir.join("history.json"), &Vec::<HistoryEntry>::new());
    }

    pub fn add_received(&self, r: Received) {
        self.add_history(&r.name, r.size, true);
        {
            let mut g = self.lock();
            g.received.insert(0, r);
            g.received.truncate(300);
        }
        self.save_received();
    }

    pub fn regenerate_token(&self) {
        self.lock().config.token = gen_token();
        self.save_config();
        tracing::info!("Token byl vygenerován znovu");
    }

    pub fn clear_queue(&self) {
        self.lock().queue.clear();
        self.save_queue();
    }
}
