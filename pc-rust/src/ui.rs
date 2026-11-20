//! Okno aplikace (egui): QR párování, drag & drop, fronty, nastavení, ikona v tray.

use crate::{net, state::*, sys};
use eframe::egui::{self, Color32, RichText, Stroke, Vec2};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{TrayIcon, TrayIconBuilder, TrayIconEvent};

static QUITTING: AtomicBool = AtomicBool::new(false);

const ACCENT: Color32 = Color32::from_rgb(76, 139, 245);
const OK_GREEN: Color32 = Color32::from_rgb(70, 190, 120);
const CARD: Color32 = Color32::from_rgb(24, 46, 92);
const BG: Color32 = Color32::from_rgb(10, 22, 50);

pub fn run(shared: Arc<Shared>, start_hidden: bool) {
    let (w, h) = {
        let g = shared.lock();
        (g.config.win_w.clamp(760.0, 4000.0), g.config.win_h.clamp(520.0, 3000.0))
    };
    let icon = make_icon(64);
    let viewport = egui::ViewportBuilder::default()
        .with_title(sys::WINDOW_TITLE)
        .with_inner_size([w, h])
        .with_min_inner_size([760.0, 520.0])
        .with_drag_and_drop(true)
        .with_visible(!start_hidden)
        .with_icon(egui::IconData { rgba: icon.clone(), width: 64, height: 64 });
    let options = eframe::NativeOptions { viewport, ..Default::default() };
    let s2 = shared.clone();
    let res = eframe::run_native(
        "TapDrop",
        options,
        Box::new(move |cc| Ok(Box::new(TapDropApp::new(cc, s2, start_hidden)))),
    );
    if let Err(e) = res {
        tracing::error!("GUI skončilo chybou: {e}");
    }
}

fn make_icon(size: u32) -> Vec<u8> {
    let mut px = vec![0u8; (size * size * 4) as usize];
    let c = size as f32 / 2.0;
    for y in 0..size {
        for x in 0..size {
            let (dx, dy) = (x as f32 + 0.5 - c, y as f32 + 0.5 - c);
            let d = (dx * dx + dy * dy).sqrt();
            let i = ((y * size + x) * 4) as usize;
            let (r, g, b, a) = if d <= c - 1.0 {
                if d < c * 0.38 {
                    (255, 255, 255, 255)
                } else if d < c * 0.55 {
                    (76, 139, 245, 255)
                } else if d > c * 0.66 && d < c * 0.80 {
                    (255, 255, 255, 235)
                } else {
                    (76, 139, 245, 255)
                }
            } else {
                (0, 0, 0, 0)
            };
            px[i..i + 4].copy_from_slice(&[r, g, b, a]);
        }
    }
    px
}

#[derive(PartialEq, Clone, Copy)]
enum Tab {
    Home,
    History,
    Settings,
}

enum Action {
    Remove(String),
    Reveal(std::path::PathBuf),
}

struct TapDropApp {
    shared: Arc<Shared>,
    _tray: Option<TrayIcon>,
    tab: Tab,
    ip: String,
    ip_checked: Instant,
    qr: (String, Vec<bool>, usize),
    port_edit: String,
    ip_edit: String,
    status_msg: Option<(String, Instant)>,
    last_size: (f32, f32),
    last_size_change: Option<Instant>,
}

impl TapDropApp {
    fn new(cc: &eframe::CreationContext<'_>, shared: Arc<Shared>, _hidden: bool) -> Self {
        shared.set_ctx(cc.egui_ctx.clone());
        setup_style(&cc.egui_ctx);
        let tray = make_tray(&cc.egui_ctx, shared.clone());
        let (port, ipo, w, h) = {
            let g = shared.lock();
            (g.config.port.to_string(), g.config.ip_override.clone(), g.config.win_w, g.config.win_h)
        };
        let ip = net::local_ip(&shared);
        TapDropApp {
            shared,
            _tray: tray,
            tab: Tab::Home,
            ip,
            ip_checked: Instant::now(),
            qr: (String::new(), vec![], 0),
            port_edit: port,
            ip_edit: ipo,
            status_msg: None,
            last_size: (w, h),
            last_size_change: None,
        }
    }

    fn say(&mut self, m: impl Into<String>) {
        self.status_msg = Some((m.into(), Instant::now()));
    }
}

fn setup_style(ctx: &egui::Context) {
    let mut v = egui::Visuals::dark();
    v.panel_fill = BG;
    v.window_fill = Color32::from_rgb(14, 30, 64);
    v.extreme_bg_color = Color32::from_rgb(8, 16, 38);
    v.widgets.noninteractive.bg_fill = CARD;
    v.selection.bg_fill = ACCENT;
    v.hyperlink_color = ACCENT;
    ctx.set_visuals(v);
    ctx.style_mut(|s| {
        s.spacing.item_spacing = Vec2::new(8.0, 8.0);
        s.spacing.button_padding = Vec2::new(12.0, 6.0);
        s.visuals.widgets.inactive.rounding = 6.0.into();
        s.visuals.widgets.hovered.rounding = 6.0.into();
        s.visuals.widgets.active.rounding = 6.0.into();
    });
}

fn make_tray(ctx: &egui::Context, shared: Arc<Shared>) -> Option<TrayIcon> {
    let menu = Menu::new();
    let show = MenuItem::new("Zobrazit TapDrop", true, None);
    let quit = MenuItem::new("Ukončit", true, None);
    let _ = menu.append(&show);
    let _ = menu.append(&PredefinedMenuItem::separator());
    let _ = menu.append(&quit);
    let (show_id, quit_id) = (show.id().clone(), quit.id().clone());
    let c1 = ctx.clone();
    let sh = shared.clone();
    MenuEvent::set_event_handler(Some(move |e: MenuEvent| {
        if e.id == show_id {
            show_window(&c1);
        } else if e.id == quit_id {
            QUITTING.store(true, Ordering::SeqCst);
            sh.save_config();
            std::process::exit(0);
        }
    }));
    let c2 = ctx.clone();
    TrayIconEvent::set_event_handler(Some(move |e: TrayIconEvent| {
        if let TrayIconEvent::DoubleClick { .. } = e {
            show_window(&c2);
        }
    }));
    let icon = tray_icon::Icon::from_rgba(make_icon(32), 32, 32).ok()?;
    match TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_menu_on_left_click(false)
        .with_tooltip("TapDrop – běží")
        .with_icon(icon)
        .build()
    {
        Ok(t) => Some(t),
        Err(e) => {
            tracing::warn!("Tray ikona nelze vytvořit: {e}");
            None
        }
    }
}

fn show_window(ctx: &egui::Context) {
    sys::win_show();
    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
    ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(false));
    ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
    ctx.request_repaint();
}

pub fn human_size(b: u64) -> String {
    const U: [&str; 5] = ["B", "kB", "MB", "GB", "TB"];
    let mut v = b as f64;
    let mut i = 0;
    while v >= 1024.0 && i < U.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{b} B")
    } else {
        format!("{v:.1} {}", U[i])
    }
}

fn ago(t: u64) -> String {
    let d = now_secs().saturating_sub(t);
    match d {
        0..=59 => "právě teď".into(),
        60..=3599 => format!("před {} min", d / 60),
        3600..=86399 => format!("před {} h", d / 3600),
        _ => format!("před {} dny", d / 86400),
    }
}

fn qr_matrix(data: &str) -> (Vec<bool>, usize) {
    match qrcode::QrCode::new(data.as_bytes()) {
        Ok(c) => {
            let w = c.width();
            (c.to_colors().into_iter().map(|x| x == qrcode::Color::Dark).collect(), w)
        }
        Err(_) => (vec![], 0),
    }
}

fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(CARD)
        .rounding(10.0)
        .inner_margin(egui::Margin::same(12.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            add(ui)
        });
}

impl eframe::App for TapDropApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // --- tray: zavření / minimalizace = skrýt do tray
        let (close_req, minimized, inner) = ctx.input(|i| {
            let v = i.viewport();
            (v.close_requested(), v.minimized.unwrap_or(false), v.inner_rect)
        });
        if close_req && !QUITTING.load(Ordering::SeqCst) {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        } else if minimized {
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        }

        // --- pamatuj velikost okna
        if let Some(r) = inner {
            let sz = (r.width().round(), r.height().round());
            if !minimized && sz.0 > 300.0 && (sz != self.last_size) {
                self.last_size = sz;
                self.last_size_change = Some(Instant::now());
            }
        }
        if let Some(t) = self.last_size_change {
            if t.elapsed() > Duration::from_millis(800) {
                {
                    let mut g = self.shared.lock();
                    g.config.win_w = self.last_size.0;
                    g.config.win_h = self.last_size.1;
                }
                self.shared.save_config();
                self.last_size_change = None;
            }
        }

        // --- IP se může změnit (přepnutí WiFi)
        if self.ip_checked.elapsed() > Duration::from_secs(5) {
            self.ip = net::local_ip(&self.shared);
            self.ip_checked = Instant::now();
        }

        // --- drag & drop souborů
        let dropped = ctx.input(|i| i.raw.dropped_files.clone());
        for f in dropped {
            if let Some(p) = f.path {
                if let Err(e) = self.shared.add_to_queue(&p) {
                    self.say(e);
                }
            }
        }
        let hovering = ctx.input(|i| !i.raw.hovered_files.is_empty());

        // --- snapshot stavu
        let (cfg, queue, received, progress, history) = {
            let g = self.shared.lock();
            (g.config.clone(), g.queue.clone(), g.received.clone(), g.progress.clone(), g.history.clone())
        };
        if !progress.is_empty() {
            ctx.request_repaint_after(Duration::from_millis(150));
        } else {
            ctx.request_repaint_after(Duration::from_secs(2));
        }

        let uri = net::pair_uri(&self.shared);
        if self.qr.0 != uri {
            let (m, w) = qr_matrix(&uri);
            self.qr = (uri.clone(), m, w);
        }

        let mut actions: Vec<Action> = vec![];

        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                ui.heading(RichText::new("TapDrop").color(ACCENT).strong());
                ui.add_space(12.0);
                ui.label(RichText::new("● Běží").color(OK_GREEN).strong());
                ui.label(RichText::new(format!("{}:{}", self.ip, cfg.port)).monospace());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.selectable_value(&mut self.tab, Tab::Settings, "Nastavení");
                    ui.selectable_value(&mut self.tab, Tab::History, "Historie");
                    ui.selectable_value(&mut self.tab, Tab::Home, "Přehled");
                });
            });
            ui.add_space(6.0);
        });

        if let Some((m, t)) = &self.status_msg {
            if t.elapsed() > Duration::from_secs(6) {
                self.status_msg = None;
            } else {
                let m = m.clone();
                egui::TopBottomPanel::bottom("status").show(ctx, |ui| {
                    ui.label(RichText::new(m).color(Color32::from_rgb(240, 180, 80)));
                });
            }
        }

        egui::SidePanel::left("pair").exact_width(300.0).show(ctx, |ui| {
            ui.add_space(8.0);
            ui.label(RichText::new("Spárování s telefonem").strong().size(16.0));
            ui.label("V aplikaci TapDrop stiskni „Spárovat s notebookem“ a naskenuj kód.");
            ui.add_space(4.0);
            let side = ui.available_width().min(270.0);
            let (rect, _) = ui.allocate_exact_size(Vec2::splat(side), egui::Sense::hover());
            let p = ui.painter_at(rect);
            p.rect_filled(rect, 8.0, Color32::WHITE);
            let (_, m, n) = (&self.qr.0, &self.qr.1, self.qr.2);
            if n > 0 {
                let quiet = 2.0;
                let cell = side / (n as f32 + quiet * 2.0);
                for y in 0..n {
                    for x in 0..n {
                        if m[y * n + x] {
                            let r = egui::Rect::from_min_size(
                                rect.min + Vec2::new((x as f32 + quiet) * cell, (y as f32 + quiet) * cell),
                                Vec2::splat(cell.ceil()),
                            );
                            p.rect_filled(r, 0.0, Color32::BLACK);
                        }
                    }
                }
            }
            ui.add_space(6.0);
            card(ui, |ui| {
                kv(ui, ctx, "NOTEBOOK_ID", &cfg.notebook_id);
                kv(ui, ctx, "TOKEN", &cfg.token);
                kv(ui, ctx, "IP", &self.ip);
                kv(ui, ctx, "Port", &cfg.port.to_string());
            });
        });

        egui::CentralPanel::default().show(ctx, |ui| match self.tab {
            Tab::Home => self.home(ui, ctx, hovering, &cfg, &queue, &received, &progress, &mut actions),
            Tab::History => self.history_tab(ui, &history),
            Tab::Settings => self.settings(ui, &cfg),
        });

        for a in actions {
            match a {
                Action::Remove(id) => {
                    self.shared.lock().queue.retain(|q| q.id != id);
                    self.shared.save_queue();
                }
                Action::Reveal(p) => sys::reveal(&p),
            }
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        {
            let mut g = self.shared.lock();
            g.config.win_w = self.last_size.0;
            g.config.win_h = self.last_size.1;
        }
        self.shared.save_config();
    }
}

fn kv(ui: &mut egui::Ui, ctx: &egui::Context, k: &str, v: &str) {
    ui.horizontal(|ui| {
        ui.label(RichText::new(k).weak().small());
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.small_button("Kopírovat").clicked() {
                ctx.copy_text(v.to_string());
            }
            ui.label(RichText::new(v).monospace().size(12.0));
        });
    });
}

impl TapDropApp {
    #[allow(clippy::too_many_arguments)]
    fn home(
        &mut self,
        ui: &mut egui::Ui,
        _ctx: &egui::Context,
        hovering: bool,
        cfg: &Config,
        queue: &[QueueItem],
        received: &[Received],
        progress: &std::collections::HashMap<String, Progress>,
        actions: &mut Vec<Action>,
    ) {
        // drop zóna
        let h = 130.0;
        let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), h), egui::Sense::click());
        let col = if hovering { OK_GREEN } else { ACCENT };
        ui.painter().rect_filled(rect, 14.0, if hovering { Color32::from_rgb(28, 50, 40) } else { CARD });
        ui.painter().rect_stroke(rect, 14.0, Stroke::new(2.0_f32, col));
        ui.painter().text(
            rect.center() - Vec2::new(0.0, 14.0),
            egui::Align2::CENTER_CENTER,
            "Přetáhni sem soubory pro telefon",
            egui::FontId::proportional(22.0),
            Color32::WHITE,
        );
        ui.painter().text(
            rect.center() + Vec2::new(0.0, 18.0),
            egui::Align2::CENTER_CENTER,
            "nebo klikni a vyber je · pak stačí přiložit telefon k tagu",
            egui::FontId::proportional(14.0),
            Color32::GRAY,
        );
        if resp.clicked() {
            let mut d = rfd::FileDialog::new().set_title("Vyber soubory pro telefon");
            if let Some(f) = &cfg.last_folder {
                d = d.set_directory(f);
            }
            if let Some(files) = d.pick_files() {
                for f in files {
                    if let Err(e) = self.shared.add_to_queue(&f) {
                        self.say(e);
                    }
                }
            }
        }

        ui.add_space(6.0);
        ui.columns(2, |cols| {
            // --- čeká na stažení
            let ui = &mut cols[0];
            ui.label(RichText::new(format!("Čeká na stažení ({})", queue.len())).strong().size(16.0));
            egui::ScrollArea::vertical().id_salt("q").show(ui, |ui| {
                if queue.is_empty() {
                    empty_state(ui, "Fronta je prázdná", "Přetáhni sem soubory a přilož telefon k tagu.");
                }
                for q in queue {
                    card(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.label(RichText::new(&q.name).strong());
                                ui.label(RichText::new(human_size(q.size)).weak().small());
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if ui.small_button("Odebrat").clicked() {
                                    actions.push(Action::Remove(q.id.clone()));
                                }
                            });
                        });
                        if let Some(p) = progress.get(&format!("down-{}", q.id)) {
                            bar(ui, p);
                        }
                    });
                }
            });

            // --- přijato
            let ui = &mut cols[1];
            ui.label(RichText::new("Přijato z telefonu").strong().size(16.0));
            egui::ScrollArea::vertical().id_salt("r").show(ui, |ui| {
                let uploads: Vec<&Progress> = progress.values().filter(|p| p.upload).collect();
                if received.is_empty() && uploads.is_empty() {
                    empty_state(ui, "Zatím nic nepřišlo", "V telefonu vyber soubor, stiskni „Připraveno k odeslání“ a přilož telefon k tagu.");
                }
                for p in uploads {
                    card(ui, |ui| {
                        ui.label(RichText::new(&p.name).strong());
                        bar(ui, p);
                    });
                }
                for r in received.iter().take(100) {
                    card(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.label(RichText::new(&r.name).strong());
                                ui.label(RichText::new(format!("{} · {}", human_size(r.size), ago(r.time))).weak().small());
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if ui.small_button("Zobrazit").clicked() {
                                    actions.push(Action::Reveal(r.path.clone()));
                                }
                            });
                        });
                    });
                }
            });
        });
    }

    fn history_tab(&mut self, ui: &mut egui::Ui, history: &[HistoryEntry]) {
        ui.horizontal(|ui| {
            ui.label(RichText::new(format!("Historie přenosů ({})", history.len())).strong().size(18.0));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("Vymazat historii").clicked() {
                    self.shared.clear_history();
                }
            });
        });
        egui::ScrollArea::vertical().id_salt("h").show(ui, |ui| {
            if history.is_empty() {
                empty_state(ui, "Historie je prázdná", "Zde uvidíš všechny soubory poslané do telefonu i přijaté z telefonu.");
            }
            for h in history {
                card(ui, |ui| {
                    ui.horizontal(|ui| {
                        let (txt, col) = if h.from_phone { ("↓ z telefonu", OK_GREEN) } else { ("↑ do telefonu", ACCENT) };
                        ui.label(RichText::new(txt).color(col).strong());
                        ui.vertical(|ui| {
                            ui.label(RichText::new(&h.name).strong());
                            ui.label(RichText::new(format!("{} · {}", human_size(h.size), ago(h.time))).weak().small());
                        });
                    });
                });
            }
        });
    }

    fn settings(&mut self, ui: &mut egui::Ui, cfg: &Config) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.label(RichText::new("Nastavení").strong().size(18.0));
            card(ui, |ui| {
                ui.label(RichText::new("Složka pro přijaté soubory").strong());
                ui.label(RichText::new(cfg.download_dir.display().to_string()).monospace());
                ui.horizontal(|ui| {
                    if ui.button("Změnit…").clicked() {
                        if let Some(d) = rfd::FileDialog::new().set_directory(&cfg.download_dir).pick_folder() {
                            let _ = std::fs::create_dir_all(&d);
                            self.shared.lock().config.download_dir = d;
                            self.shared.save_config();
                        }
                    }
                    if ui.button("Otevřít složku").clicked() {
                        sys::open_dir(&cfg.download_dir);
                    }
                });
            });
            card(ui, |ui| {
                ui.label(RichText::new("Síť").strong());
                ui.horizontal(|ui| {
                    ui.label("Port:");
                    ui.add(egui::TextEdit::singleline(&mut self.port_edit).desired_width(70.0));
                    ui.label("IP (prázdné = automaticky):");
                    ui.add(egui::TextEdit::singleline(&mut self.ip_edit).desired_width(130.0));
                    if ui.button("Uložit").clicked() {
                        match self.port_edit.trim().parse::<u16>() {
                            Ok(p) if p >= 1024 => {
                                {
                                    let mut g = self.shared.lock();
                                    g.config.port = p;
                                    g.config.ip_override = self.ip_edit.trim().to_string();
                                }
                                self.shared.save_config();
                                self.say("Uloženo. Změna portu se projeví po restartu programu (a je nutné telefon spárovat znovu).");
                            }
                            _ => self.say("Port musí být číslo 1024–65535."),
                        }
                    }
                });
                ui.label(RichText::new("Při prvním spuštění povol TapDrop ve Windows Firewallu pro soukromé sítě.").weak().small());
            });
            card(ui, |ui| {
                ui.label(RichText::new("Systém").strong());
                let mut auto = cfg.autostart;
                if ui.checkbox(&mut auto, "Spouštět při startu Windows (do tray)").changed() {
                    self.shared.lock().config.autostart = auto;
                    self.shared.save_config();
                    sys::set_autostart(auto);
                }
                ui.label(RichText::new("Zavření okna program jen skryje do lišty u hodin. Ukončit lze z menu ikony.").weak().small());
                if ui.button("Otevřít složku s logy").clicked() {
                    sys::open_dir(&self.shared.dir.join("logs"));
                }
            });
            card(ui, |ui| {
                ui.label(RichText::new("Bezpečnost a fronta").strong());
                ui.horizontal(|ui| {
                    if ui.button("Vygenerovat nový token").clicked() {
                        let yes = rfd::MessageDialog::new()
                            .set_title("TapDrop")
                            .set_description("Nový token zneplatní stávající spárování. Telefon bude nutné spárovat znovu. Pokračovat?")
                            .set_buttons(rfd::MessageButtons::YesNo)
                            .show();
                        if yes == rfd::MessageDialogResult::Yes {
                            self.shared.regenerate_token();
                        }
                    }
                    if ui.button("Vyčistit frontu").clicked() {
                        self.shared.clear_queue();
                    }
                });
            });
        });
    }
}

fn bar(ui: &mut egui::Ui, p: &Progress) {
    let f = if p.total > 0 { (p.done as f32 / p.total as f32).clamp(0.0, 1.0) } else { 0.0 };
    ui.add(egui::ProgressBar::new(f).show_percentage().animate(true));
    ui.label(RichText::new(format!("{} / {}", human_size(p.done), human_size(p.total))).weak().small());
}

fn empty_state(ui: &mut egui::Ui, title: &str, text: &str) {
    ui.add_space(20.0);
    ui.vertical_centered(|ui| {
        ui.label(RichText::new(title).size(16.0).color(Color32::GRAY));
        ui.label(RichText::new(text).weak().small());
    });
}
