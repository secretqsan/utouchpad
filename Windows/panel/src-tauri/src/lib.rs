mod pipe;

use serde_json::{json, Value};
use std::time::Duration;

async fn run_command(
    method: &'static str,
    params: Option<Value>,
    timeout: Duration,
) -> Result<Value, String> {
    match tauri::async_runtime::spawn_blocking(move || pipe::send(method, params, timeout)).await {
        Ok(result) => result,
        Err(err) => Err(format!("内部错误：{err}")),
    }
}

#[tauri::command]
async fn get_status() -> Result<Value, String> {
    run_command("status", None, Duration::from_secs(10)).await
}

#[tauri::command]
async fn begin_pairing() -> Result<Value, String> {
    run_command("pair.begin", None, Duration::from_secs(10)).await
}

#[tauri::command]
async fn cancel_pairing() -> Result<Value, String> {
    run_command("pair.cancel", None, Duration::from_secs(10)).await
}

#[tauri::command]
async fn unpair() -> Result<Value, String> {
    run_command("pair.unpair", None, Duration::from_secs(10)).await
}

#[tauri::command]
async fn set_password(password: String) -> Result<Value, String> {
    run_command(
        "password.set",
        Some(json!({ "password": password })),
        Duration::from_secs(100),
    )
    .await
}

#[tauri::command]
async fn restart_viiper() -> Result<Value, String> {
    run_command("viiper.restart", None, Duration::from_secs(30)).await
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .invoke_handler(tauri::generate_handler![
            get_status,
            begin_pairing,
            cancel_pairing,
            unpair,
            set_password,
            restart_viiper
        ])
        .run(tauri::generate_context!())
        .expect("error while running Utochpad settings");
}
