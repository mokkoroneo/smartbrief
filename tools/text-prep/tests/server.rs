use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use text_prep::{encode_string, string_fields};

struct Server {
    child: Child,
    address: SocketAddr,
}

impl Server {
    fn start() -> Self {
        let child = Command::new(env!("CARGO_BIN_EXE_text-prep-server"))
            .args(["--port", "0"])
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let mut server = Self {
            child,
            address: "127.0.0.1:0".parse().unwrap(),
        };
        let mut stderr = BufReader::new(server.child.stderr.take().unwrap());
        let (sender, receiver) = mpsc::channel();
        thread::spawn(move || {
            let mut line = String::new();
            let result = stderr.read_line(&mut line).map(|_| line);
            let _ = sender.send(result);
            let _ = std::io::copy(&mut stderr, &mut std::io::sink());
        });
        let line = receiver
            .recv_timeout(Duration::from_secs(30))
            .unwrap()
            .unwrap();
        server.address = line
            .strip_prefix("listening on ")
            .unwrap_or_else(|| panic!("unexpected startup log: {line:?}"))
            .trim_end()
            .parse()
            .unwrap();
        assert_eq!(server.address.ip().to_string(), "127.0.0.1");
        assert_ne!(server.address.port(), 0);
        server
    }

    fn connect(&self) -> TcpStream {
        let timeout = Duration::from_secs(10);
        let stream = TcpStream::connect_timeout(&self.address, timeout).unwrap();
        stream.set_read_timeout(Some(timeout)).unwrap();
        stream.set_write_timeout(Some(timeout)).unwrap();
        stream
    }

    fn request(&self, request: &[u8]) -> Response {
        let mut stream = self.connect();
        stream.write_all(request).unwrap();
        stream.shutdown(Shutdown::Write).unwrap();
        Response::read(stream)
    }

    fn post(&self, path: &str, body: &str) -> Response {
        let request = format!(
            "POST {path} HTTP/1.1\r\nHost: localhost\r\ncontent-length: {}\r\n\
             content-type: application/json\r\n\r\n{body}",
            body.len()
        );
        self.request(request.as_bytes())
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct Response {
    status: u16,
    headers: String,
    body: String,
}

impl Response {
    fn read(mut stream: impl Read) -> Self {
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        let (headers, body) = response.split_once("\r\n\r\n").unwrap();
        let mut lines = headers.split("\r\n");
        let status_line = lines.next().unwrap();
        assert!(status_line.starts_with("HTTP/1.1 "));
        let status = status_line.split(' ').nth(1).unwrap().parse().unwrap();
        assert!(lines.clone().any(|line| line == "Connection: close"));
        assert!(lines
            .clone()
            .any(|line| line == "Content-Type: application/json; charset=utf-8"));
        let length: usize = lines
            .find_map(|line| line.strip_prefix("Content-Length: "))
            .unwrap()
            .parse()
            .unwrap();
        assert_eq!(body.len(), length);
        Self {
            status,
            headers: headers.into(),
            body: body.into(),
        }
    }

    fn ok(self, expected: &str) {
        assert_eq!(self.status, 200, "{}", self.body);
        assert_eq!(self.body, expected);
    }

    fn error(&self, status: u16) {
        assert_eq!(self.status, status, "{}", self.body);
        let [error] = string_fields(&self.body, ["error"]).unwrap();
        assert!(error.is_some_and(|message| !message.is_empty()));
    }
}

fn text_body(text: &str) -> String {
    format!("{{\"text\":{}}}", encode_string(text))
}

#[test]
fn health_returns_ok() {
    Server::start()
        .request(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .ok(r#"{"status":"ok"}"#);
}

#[test]
fn strip_returns_cli_body_and_statistics() {
    Server::start()
        .post("/strip-gutenberg", &text_body(include_str!("fixtures/grimm.txt")))
        .ok(r#"{"text":"Fixture body for Grimm.\n","removed_lines":7,"remaining_chars":24,"warning":null}"#);
}

#[test]
fn strip_preserves_crlf() {
    Server::start()
        .post("/strip-gutenberg", &text_body(include_str!("fixtures/crlf.txt")))
        .ok(r#"{"text":"First body line\r\nSecond body line\r\n","removed_lines":4,"remaining_chars":35,"warning":null}"#);
}

#[test]
fn strip_round_trips_utf8_and_json_escapes_with_warning() {
    let input = "\"\\😀\0\u{8}\u{c}\t\r\n\u{1f}";
    let body = r#"{"text":"\"\\\uD83D\uDE00\u0000\b\f\t\r\n\u001f"}"#;
    let response = Server::start().post("/strip-gutenberg", body);
    let [text] = string_fields(&response.body, ["text"]).unwrap();
    assert_eq!(text.unwrap().as_bytes(), input.as_bytes());
    response.ok(
        r#"{"text":"\"\\😀\u0000\b\f\t\r\n\u001f","removed_lines":0,"remaining_chars":10,"warning":"no Gutenberg markers found"}"#,
    );
}

#[test]
fn count_matches_cli_for_japanese_and_emoji() {
    Server::start()
        .post("/count", &text_body(include_str!("fixtures/count.txt")))
        .ok(r#"{"chars": 5, "lines": 1, "bytes": 12}"#);
}

#[test]
fn title_extracts_four_original_titles_from_both_sources() {
    let server = Server::start();
    for (json, text, title) in [
        (
            include_str!("fixtures/grimm.json"),
            include_str!("fixtures/grimm.txt"),
            "Grimms' Fairy Tales",
        ),
        (
            include_str!("fixtures/du-bois.json"),
            include_str!("fixtures/du-bois.txt"),
            "The Souls of Black Folk",
        ),
        (
            include_str!("fixtures/hugo.json"),
            include_str!("fixtures/hugo.txt"),
            "Les Misérables",
        ),
        (
            include_str!("fixtures/austen.json"),
            include_str!("fixtures/austen.txt"),
            "Sense and Sensibility",
        ),
    ] {
        let expected = format!("{{\"title\":\"{title}\"}}");
        server
            .post(
                "/title",
                &format!(r#"{{"source":"gutendex","json":{}}}"#, encode_string(json)),
            )
            .ok(&expected);
        server
            .post(
                "/title",
                &format!(r#"{{"source":"text","text":{}}}"#, encode_string(text)),
            )
            .ok(&expected);
    }
}

#[test]
fn title_rejects_invalid_candidates_from_both_sources() {
    let server = Server::start();
    for candidate in ["\"0\"", "12345", "", "   "] {
        let book = format!("{{\"title\":{}}}", encode_string(candidate));
        let raw = format!("Title: {candidate}\n");
        server
            .post(
                "/title",
                &format!(r#"{{"source":"gutendex","json":{}}}"#, encode_string(&book)),
            )
            .error(400);
        server
            .post(
                "/title",
                &format!(r#"{{"source":"text","text":{}}}"#, encode_string(&raw)),
            )
            .error(400);
    }
    for body in [
        r#"{"source":"gutendex","json":{"title":"Example"}}"#,
        r#"{"source":"gutendex","json":"{}"}"#,
        r#"{"source":"text","text":"No title line"}"#,
    ] {
        server.post("/title", body).error(400);
    }
}

#[test]
fn invalid_json_envelopes_and_utf8_return_400() {
    let server = Server::start();
    for invalid in ["{", r#"{"text":"x",}"#, r#"["x"]"#, "{}", r#"{"text":42}"#] {
        server.post("/count", invalid).error(400);
    }
    server
        .request(b"POST /count HTTP/1.1\r\nContent-Length: 12\r\n\r\n{\"text\":\"\xff\"}")
        .error(400);
}

#[test]
fn unknown_paths_and_wrong_methods_return_404_and_405() {
    let server = Server::start();
    server
        .request(b"GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .error(404);
    for path in ["/strip-gutenberg", "/count", "/title"] {
        let response = server.request(format!("GET {path} HTTP/1.1\r\n\r\n").as_bytes());
        response.error(405);
        assert!(response.headers.contains("\r\nAllow: POST"));
    }
}

#[test]
fn oversized_body_is_rejected_before_continue_or_body_read() {
    let server = Server::start();
    let mut stream = server.connect();
    stream
        .write_all(
            b"POST /count HTTP/1.1\r\nContent-Length: 20971521\r\nExpect: 100-continue\r\n\r\n",
        )
        .unwrap();
    Response::read(stream).error(413);
}

#[test]
fn expect_continue_is_sent_before_reading_body() {
    let server = Server::start();
    let mut stream = server.connect();
    stream
        .write_all(b"POST /count HTTP/1.1\r\ncOnTenT-LenGTh: 12\r\neXpEcT: 100-CoNtInUe\r\n\r\n")
        .unwrap();
    let mut interim = [0; b"HTTP/1.1 100 Continue\r\n\r\n".len()];
    stream.read_exact(&mut interim).unwrap();
    assert_eq!(&interim, b"HTTP/1.1 100 Continue\r\n\r\n");
    stream.write_all(br#"{"text":"x"}"#).unwrap();
    Response::read(stream).ok(r#"{"chars": 1, "lines": 1, "bytes": 1}"#);
}

#[test]
fn invalid_request_framing_returns_400() {
    let server = Server::start();
    for headers in [
        "",
        "Transfer-Encoding: chunked\r\n",
        "Content-Length: 0\r\ntransfer-encoding: identity\r\n",
        "Content-Length: 0\r\ncontent-length: 0\r\n",
        "Content-Length: +0\r\n",
        "Content-Length: 1\r\n",
    ] {
        server
            .request(format!("POST /count HTTP/1.1\r\n{headers}\r\n").as_bytes())
            .error(400);
    }
}

#[test]
fn header_limit_includes_the_request_line_and_terminator() {
    let server = Server::start();
    let prefix = "GET /health HTTP/1.1\r\nHost: localhost\r\nX-Padding: ";
    let suffix = "\r\n\r\n";
    let at_limit = 16 * 1024 - prefix.len() - suffix.len();
    server
        .request(format!("{prefix}{}{suffix}", "x".repeat(at_limit)).as_bytes())
        .ok(r#"{"status":"ok"}"#);
    server
        .request(format!("{prefix}{}{suffix}", "x".repeat(at_limit + 1)).as_bytes())
        .error(400);
}
