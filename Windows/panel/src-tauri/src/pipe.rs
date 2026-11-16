use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs::OpenOptions;
use std::io::{BufRead, BufReader, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

const PIPE_PATH: &str = r"\\.\pipe\utochpad-ui";
const MAX_LINE_BYTES: usize = 1024 * 1024;

#[derive(Serialize)]
struct PipeRequest<'a> {
    id: String,
    method: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    params: Option<Value>,
}

#[derive(Deserialize)]
struct PipeResponse {
    ok: bool,
    #[serde(default)]
    result: Option<Value>,
    #[serde(default)]
    error: Option<String>,
}

static REQUEST_COUNTER: AtomicU64 = AtomicU64::new(0);

fn next_id() -> String {
    let n = REQUEST_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:x}-{:x}", std::process::id(), n)
}

/// One request/response round-trip over the Utochpad UI pipe.
///
/// The C# service expects newline-delimited JSON, one request per connection.
/// The read is executed on a worker thread so a hung service still produces a
/// timeout instead of freezing the Tauri command forever.
pub fn send(method: &str, params: Option<Value>, timeout: Duration) -> Result<Value, String> {
    let method = method.to_string();
    let (tx, rx) = mpsc::channel();

    thread::spawn(move || {
        let _ = tx.send(send_blocking(&method, params));
    });

    match rx.recv_timeout(timeout) {
        Ok(result) => result,
        Err(mpsc::RecvTimeoutError::Timeout) => Err("请求超时".to_string()),
        Err(mpsc::RecvTimeoutError::Disconnected) => {
            Err("Utochpad 服务未运行或无法连接".to_string())
        }
    }
}

fn send_blocking(method: &str, params: Option<Value>) -> Result<Value, String> {
    let request = PipeRequest {
        id: next_id(),
        method,
        params,
    };

    let mut payload = serde_json::to_vec(&request).map_err(|e| format!("无法构造请求：{e}"))?;
    if payload.len() > MAX_LINE_BYTES {
        return Err("请求内容过大".to_string());
    }
    payload.push(b'\n');

    let mut pipe = connect(Duration::from_secs(2))?;
    pipe.write_all(&payload)
        .and_then(|_| pipe.flush())
        .map_err(|e| format!("无法写入 Utochpad 服务：{e}"))?;

    let mut reader = BufReader::new(pipe);
    let mut line = String::new();
    let read = reader
        .read_line(&mut line)
        .map_err(|e| format!("无法读取 Utochpad 服务响应：{e}"))?;

    if read == 0 {
        return Err("Utochpad 服务没有响应".to_string());
    }
    if line.len() > MAX_LINE_BYTES {
        return Err("服务响应过大".to_string());
    }

    let response: PipeResponse = serde_json::from_str(line.trim_end())
        .map_err(|e| format!("无法解析服务响应：{e}"))?;

    if response.ok {
        Ok(response.result.unwrap_or(Value::Null))
    } else {
        Err(response
            .error
            .unwrap_or_else(|| "Utochpad 服务返回未知错误".to_string()))
    }
}

fn connect(timeout: Duration) -> Result<std::fs::File, String> {
    let deadline = Instant::now() + timeout;

    loop {
        match OpenOptions::new().read(true).write(true).open(PIPE_PATH) {
            Ok(pipe) => return Ok(pipe),
            Err(err) => {
                if Instant::now() >= deadline {
                    return Err(format!("Utochpad 服务未运行或无法连接：{err}"));
                }
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
}
