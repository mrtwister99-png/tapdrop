//! HTTP server (Axum): /discover, /pending, /download/{id}, /ack/{id}, /upload, /upload/status

use crate::state::*;
use axum::{
    body::Body,
    extract::{ConnectInfo, DefaultBodyLimit, Multipart, Path as AxPath, Query, Request, State},
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use serde_json::json;
use std::collections::HashMap;
use std::io::SeekFrom;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;

type S = Arc<Shared>;
const MAX_ODHOZ_BYTES: usize = 1_048_576;

pub async fn run(shared: S, listener: std::net::TcpListener) {
    let listener = match tokio::net::TcpListener::from_std(listener) {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("Listener: {e}");
            return;
        }
    };
    let app = Router::new()
        .route("/discover", get(discover))
        .route("/pending", get(pending))
        .route("/download/:id", get(download))
        .route("/ack/:id", post(ack))
        .route("/upload", post(upload))
        .route("/upload/status", get(upload_status))
        .route("/odhoz", get(get_odhoz).put(put_odhoz))
        .layer(DefaultBodyLimit::disable())
        .layer(middleware::from_fn_with_state(shared.clone(), auth))
        .with_state(shared);
    tracing::info!("HTTP server naslouchá na {:?}", listener.local_addr());
    if let Err(e) = axum::serve(listener, app.into_make_service_with_connect_info::<std::net::SocketAddr>()).await {
        tracing::error!("Server skončil chybou: {e}");
    }
}

#[cfg(test)]
mod odhoz_tests {
    use super::*;

    #[test]
    fn odhoz_rejects_text_over_limit() {
        assert_eq!(
            validate_odhoz_text(&"x".repeat(MAX_ODHOZ_BYTES + 1)),
            Err(StatusCode::PAYLOAD_TOO_LARGE)
        );
    }

    #[test]
    fn odhoz_accepts_empty_text() {
        assert_eq!(validate_odhoz_text(""), Ok(()));
    }
}

fn ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn auth(State(s): State<S>, ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>, req: Request, next: Next) -> Response {
    let ok = req
        .headers()
        .get("x-tapdrop-token")
        .and_then(|v| v.to_str().ok())
        .map(|t| ct_eq(t, &s.token()))
        .unwrap_or(false);
    if ok {
        s.touch_phone(addr.ip().to_string());
        next.run(req).await
    } else {
        tracing::warn!("Zamítnuto (401): {} {}", req.method(), req.uri().path());
        (StatusCode::UNAUTHORIZED, Json(json!({"error": "Špatný token"}))).into_response()
    }
}

fn err(code: StatusCode, msg: &str) -> Response {
    (code, Json(json!({"error": msg}))).into_response()
}

async fn discover(State(s): State<S>) -> Response {
    let id = s.lock().config.notebook_id.clone();
    let name = hostname::get().map(|h| h.to_string_lossy().to_string()).unwrap_or_else(|_| "TapDrop".into());
    Json(json!({"id": id, "name": name, "version": VERSION})).into_response()
}

fn validate_odhoz_text(text: &str) -> Result<(), StatusCode> {
    if text.len() > MAX_ODHOZ_BYTES {
        Err(StatusCode::PAYLOAD_TOO_LARGE)
    } else {
        Ok(())
    }
}

async fn get_odhoz(State(s): State<S>) -> Response {
    Json(json!({"text": s.odhoz_text()})).into_response()
}

async fn put_odhoz(State(s): State<S>, Json(body): Json<serde_json::Value>) -> Response {
    let Some(text) = body.get("text").and_then(|v| v.as_str()) else {
        return err(StatusCode::BAD_REQUEST, "Pole text je povinné");
    };
    if let Err(code) = validate_odhoz_text(text) {
        return (code, Json(json!({"error": "Text je větší než 1 MiB"}))).into_response();
    }
    s.set_odhoz_text(text.to_string());
    Json(json!({"ok": true, "text": text})).into_response()
}

async fn pending(State(s): State<S>) -> Response {
    let items: Vec<_> = s
        .lock()
        .queue
        .iter()
        .filter(|q| q.path.is_file())
        .map(|q| json!({"id": q.id, "name": q.name, "size": std::fs::metadata(&q.path).map(|m| m.len()).unwrap_or(q.size)}))
        .collect();
    Json(items).into_response()
}

/// Při zahození odstraní záznam o průběhu (i když přenos selže uprostřed).
struct ProgressGuard {
    shared: S,
    key: String,
}
impl Drop for ProgressGuard {
    fn drop(&mut self) {
        self.shared.lock().progress.remove(&self.key);
        self.shared.repaint();
    }
}

fn set_progress(s: &S, key: &str, name: &str, done: u64, total: u64, upload: bool) {
    s.lock()
        .progress
        .insert(key.to_string(), Progress { name: name.to_string(), done, total, upload });
}

fn pct_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn parse_range(h: &HeaderMap, total: u64) -> Option<(u64, u64)> {
    let v = h.get(header::RANGE)?.to_str().ok()?.strip_prefix("bytes=")?;
    let (a, b) = v.split_once('-')?;
    if total == 0 {
        return None;
    }
    if a.is_empty() {
        let n: u64 = b.parse().ok()?;
        let n = n.min(total);
        return Some((total - n, total - 1));
    }
    let start: u64 = a.parse().ok()?;
    let end: u64 = if b.is_empty() { total - 1 } else { b.parse::<u64>().ok()?.min(total - 1) };
    if start > end || start >= total {
        return None;
    }
    Some((start, end))
}

async fn download(State(s): State<S>, AxPath(id): AxPath<String>, headers: HeaderMap) -> Response {
    let item = s.lock().queue.iter().find(|q| q.id == id).cloned();
    let Some(item) = item else { return err(StatusCode::NOT_FOUND, "Soubor není ve frontě") };
    let mut file = match tokio::fs::File::open(&item.path).await {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("Nelze otevřít {}: {e}", item.path.display());
            return err(StatusCode::NOT_FOUND, "Soubor už neexistuje");
        }
    };
    let total = file.metadata().await.map(|m| m.len()).unwrap_or(item.size);
    let wants_range = headers.contains_key(header::RANGE);
    let (start, end, status) = match parse_range(&headers, total) {
        Some((a, b)) => (a, b, StatusCode::PARTIAL_CONTENT),
        None if wants_range && total > 0 => {
            return Response::builder()
                .status(StatusCode::RANGE_NOT_SATISFIABLE)
                .header(header::CONTENT_RANGE, format!("bytes */{total}"))
                .body(Body::empty())
                .unwrap();
        }
        None => (0, total.saturating_sub(1), StatusCode::OK),
    };
    let len = if total == 0 { 0 } else { end - start + 1 };
    if start > 0 && file.seek(SeekFrom::Start(start)).await.is_err() {
        return err(StatusCode::INTERNAL_SERVER_ERROR, "Seek selhal");
    }
    let key = format!("down-{}", item.id);
    let guard = ProgressGuard { shared: s.clone(), key: key.clone() };
    let name = item.name.clone();
    let s2 = s.clone();
    let mut sent = start;
    let stream = ReaderStream::with_capacity(file.take(len), 256 * 1024).map(move |chunk| {
        let _keep = &guard;
        if let Ok(b) = &chunk {
            sent += b.len() as u64;
            set_progress(&s2, &key, &name, sent, total, false);
        }
        chunk
    });
    tracing::info!("Odesílám telefonu: {} ({} B od {})", item.name, len, start);
    let mut resp = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "application/octet-stream")
        .header(header::CONTENT_LENGTH, len)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(
            header::CONTENT_DISPOSITION,
            format!("attachment; filename*=UTF-8''{}", pct_encode(&item.name)),
        );
    if status == StatusCode::PARTIAL_CONTENT {
        resp = resp.header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"));
    }
    resp.body(Body::from_stream(stream)).unwrap_or_else(|_| err(StatusCode::INTERNAL_SERVER_ERROR, "Chyba odpovědi"))
}

async fn ack(State(s): State<S>, AxPath(id): AxPath<String>) -> Response {
    let removed = {
        let mut g = s.lock();
        let item = g.queue.iter().find(|q| q.id == id).cloned();
        g.queue.retain(|q| q.id != id);
        item
    };
    if let Some(item) = removed {
        s.add_history(&item.name, item.size, false);
        s.save_queue();
        s.repaint();
        tracing::info!("Potvrzeno staženo, odstraněno z fronty: {id}");
        Json(json!({"ok": true})).into_response()
    } else {
        err(StatusCode::NOT_FOUND, "Soubor není ve frontě")
    }
}

fn part_path(s: &S, name: &str, size: u64) -> std::path::PathBuf {
    let dir = s.lock().config.download_dir.join(".partial");
    dir.join(format!("{name}.{size}.part"))
}

fn header_u64(h: &HeaderMap, k: &str) -> Option<u64> {
    h.get(k)?.to_str().ok()?.trim().parse().ok()
}

async fn upload_status(State(s): State<S>, Query(q): Query<HashMap<String, String>>) -> Response {
    let name = sanitize_name(q.get("name").map(|s| s.as_str()).unwrap_or(""));
    let size: u64 = q.get("size").and_then(|s| s.parse().ok()).unwrap_or(0);
    let off = tokio::fs::metadata(part_path(&s, &name, size)).await.map(|m| m.len()).unwrap_or(0);
    Json(json!({"offset": off})).into_response()
}

async fn upload(State(s): State<S>, headers: HeaderMap, mut mp: Multipart) -> Response {
    let total = header_u64(&headers, "x-tapdrop-size").unwrap_or(0);
    let offset = header_u64(&headers, "x-tapdrop-offset").unwrap_or(0);
    loop {
        let field = match mp.next_field().await {
            Ok(Some(f)) => f,
            Ok(None) => return err(StatusCode::BAD_REQUEST, "Chybí soubor v požadavku"),
            Err(e) => {
                tracing::warn!("Multipart chyba: {e}");
                return err(StatusCode::BAD_REQUEST, "Neplatný požadavek");
            }
        };
        let Some(fname) = field.file_name().map(|s| s.to_string()) else { continue };
        let name = sanitize_name(&fname);
        let part = part_path(&s, &name, total);
        if let Some(p) = part.parent() {
            if let Err(e) = tokio::fs::create_dir_all(p).await {
                tracing::error!("Nelze vytvořit složku: {e}");
                return err(StatusCode::INTERNAL_SERVER_ERROR, "Nelze vytvořit složku pro příjem");
            }
        }
        let have = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
        if offset != 0 && have != offset {
            return (StatusCode::CONFLICT, Json(json!({"error": "Nesouhlasí offset", "offset": have}))).into_response();
        }
        let mut out = match tokio::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(offset == 0)
            .append(offset != 0)
            .open(&part)
            .await
        {
            Ok(f) => f,
            Err(e) => {
                tracing::error!("Nelze otevřít {}: {e}", part.display());
                return err(StatusCode::INTERNAL_SERVER_ERROR, "Nelze zapisovat soubor");
            }
        };
        let key = format!("up-{}-{}", name, total);
        let _guard = ProgressGuard { shared: s.clone(), key: key.clone() };
        let mut written = offset;
        let mut field = field;
        tracing::info!("Přijímám z telefonu: {name} (od {offset} z {total})");
        loop {
            match field.chunk().await {
                Ok(Some(chunk)) => {
                    if let Err(e) = out.write_all(&chunk).await {
                        tracing::error!("Zápis selhal: {e}");
                        return err(StatusCode::INSUFFICIENT_STORAGE, "Zápis na disk selhal (málo místa?)");
                    }
                    written += chunk.len() as u64;
                    set_progress(&s, &key, &name, written, total.max(written), true);
                }
                Ok(None) => break,
                Err(e) => {
                    let _ = out.flush().await;
                    tracing::warn!("Přenos {name} přerušen na {written} B: {e}");
                    return err(StatusCode::BAD_REQUEST, "Přenos přerušen");
                }
            }
        }
        let _ = out.flush().await;
        drop(out);
        if total > 0 && written < total {
            return (StatusCode::ACCEPTED, Json(json!({"ok": false, "offset": written}))).into_response();
        }
        let dir = s.lock().config.download_dir.clone();
        let final_path = unique_path(&dir, &name);
        if let Err(e) = tokio::fs::rename(&part, &final_path).await {
            tracing::error!("Přejmenování selhalo: {e}");
            return err(StatusCode::INTERNAL_SERVER_ERROR, "Nelze uložit soubor");
        }
        tracing::info!("Uloženo: {}", final_path.display());
        s.add_received(Received { name: name.clone(), path: final_path, size: written, time: now_secs() });
        return Json(json!({"ok": true, "name": name, "size": written})).into_response();
    }
}

