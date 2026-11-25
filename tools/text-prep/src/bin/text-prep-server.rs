use std::env;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process::ExitCode;
use std::time::Duration;

use text_prep::{
    count, encode_string, string_fields, strip_gutenberg, title_from_gutendex, title_from_text,
};

const HEADER_LIMIT: usize = 16 * 1024;
const BODY_LIMIT: usize = 20 * 1024 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(15);
const USAGE: &str = "usage: text-prep-server [--bind ADDR] [--port PORT]";

type HttpResult<T> = Result<T, (Status, &'static str)>;

enum Status {
    Ok,
    BadRequest,
    NotFound,
    MethodNotAllowed(&'static str),
    PayloadTooLarge,
}

impl Status {
    fn line(&self) -> &'static str {
        match self {
            Self::Ok => "200 OK",
            Self::BadRequest => "400 Bad Request",
            Self::NotFound => "404 Not Found",
            Self::MethodNotAllowed(_) => "405 Method Not Allowed",
            Self::PayloadTooLarge => "413 Payload Too Large",
        }
    }
}

fn main() -> ExitCode {
    match serve() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("text-prep-server: {err}");
            ExitCode::from(2)
        }
    }
}

fn serve() -> Result<(), Box<dyn std::error::Error>> {
    let mut bind = String::from("127.0.0.1");
    let mut port: u16 = 18080;
    let mut args = env::args().skip(1);
    while let Some(flag) = args.next() {
        match flag.as_str() {
            "--bind" => bind = args.next().ok_or(USAGE)?,
            "--port" => {
                port = args.next().ok_or(USAGE)?.parse().map_err(|_| USAGE)?;
            }
            _ => return Err(USAGE.into()),
        }
    }

    let listener = TcpListener::bind((bind.as_str(), port))?;
    {
        let mut stderr = io::stderr().lock();
        writeln!(stderr, "listening on {}", listener.local_addr()?)?;
        stderr.flush()?;
    }

    for stream in listener.incoming() {
        if let Err(err) = stream.and_then(handle_connection) {
            eprintln!("text-prep-server: {err}");
        }
    }
    Ok(())
}

fn handle_connection(mut stream: TcpStream) -> io::Result<()> {
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let (status, body) = match respond(&mut BufReader::new(&mut stream)) {
        Ok(body) => (Status::Ok, body),
        Err((status, message)) => (status, format!("{{\"error\":{}}}", encode_string(message))),
    };
    write!(
        stream,
        "HTTP/1.1 {}\r\nContent-Type: application/json; charset=utf-8\r\n\
         Content-Length: {}\r\nConnection: close\r\n",
        status.line(),
        body.len()
    )?;
    if let Status::MethodNotAllowed(method) = status {
        write!(stream, "Allow: {method}\r\n")?;
    }
    stream.write_all(b"\r\n")?;
    stream.write_all(body.as_bytes())
}

struct Request {
    method: String,
    path: String,
    length: usize,
    expect_continue: bool,
}

fn read_headers(reader: &mut impl BufRead) -> HttpResult<Request> {
    let mut reader = reader.take((HEADER_LIMIT + 1) as u64);
    let mut line = String::new();
    let first = header_line(&mut reader, &mut line)?;
    let mut parts = first.split(' ');
    let method = parts.next().filter(|word| is_token(word));
    let path = parts
        .next()
        .filter(|word| word.starts_with('/') && !word.bytes().any(|byte| byte.is_ascii_control()));
    let (Some(method), Some(path), Some("HTTP/1.1"), None) =
        (method, path, parts.next(), parts.next())
    else {
        return Err(bad_request("invalid HTTP/1.1 request line"));
    };
    let method = method.to_owned();
    let path = path.to_owned();

    let mut length = None;
    let mut expect_continue = false;
    loop {
        let header = header_line(&mut reader, &mut line)?;
        if header.is_empty() {
            break;
        }
        let (name, value) = header
            .split_once(':')
            .filter(|(name, _)| is_token(name))
            .ok_or_else(|| bad_request("invalid HTTP header"))?;
        let value = value.trim_matches([' ', '\t']);
        if value
            .bytes()
            .any(|byte| byte.is_ascii_control() && byte != b'\t')
        {
            return Err(bad_request("invalid HTTP header value"));
        }
        if name.eq_ignore_ascii_case("transfer-encoding") {
            return Err(bad_request("Transfer-Encoding is unsupported"));
        }
        if name.eq_ignore_ascii_case("content-length") {
            if length.is_some() || value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
                return Err(bad_request("invalid or duplicate Content-Length"));
            }
            length = Some(value.parse::<usize>().unwrap_or(usize::MAX));
        }
        if name.eq_ignore_ascii_case("expect") {
            if !value.eq_ignore_ascii_case("100-continue") {
                return Err(bad_request("unsupported Expect header"));
            }
            expect_continue = true;
        }
    }

    if method == "POST" && length.is_none() {
        return Err(bad_request("Content-Length is required for POST"));
    }
    let length = length.unwrap_or(0);
    if length > BODY_LIMIT {
        return Err((Status::PayloadTooLarge, "request body exceeds 20 MiB"));
    }
    Ok(Request {
        method,
        path,
        length,
        expect_continue,
    })
}

fn header_line<'a, R: BufRead>(
    reader: &mut io::Take<R>,
    line: &'a mut String,
) -> HttpResult<&'a str> {
    line.clear();
    reader
        .read_line(line)
        .map_err(|_| bad_request("could not read HTTP headers"))?;
    if reader.limit() == 0 {
        return Err(bad_request("request headers exceed 16 KiB"));
    }
    line.strip_suffix("\r\n")
        .ok_or_else(|| bad_request("HTTP headers must end with CRLF"))
}

fn is_token(word: &str) -> bool {
    !word.is_empty()
        && word.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || b"!#$%&'*+-.^_|~".contains(&byte) || byte == 0x60
        })
}

enum Endpoint {
    Health,
    Strip,
    Count,
    Title,
}

fn respond(reader: &mut BufReader<&mut TcpStream>) -> HttpResult<String> {
    let request = read_headers(reader)?;
    let (endpoint, expected_method) = match request.path.as_str() {
        "/health" => (Endpoint::Health, "GET"),
        "/strip-gutenberg" => (Endpoint::Strip, "POST"),
        "/count" => (Endpoint::Count, "POST"),
        "/title" => (Endpoint::Title, "POST"),
        _ => return Err((Status::NotFound, "unknown path")),
    };
    if request.method != expected_method {
        return Err((
            Status::MethodNotAllowed(expected_method),
            "method not allowed",
        ));
    }

    if request.expect_continue {
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
            .map_err(|_| bad_request("could not send 100 Continue"))?;
    }
    let mut body = Vec::new();
    reader
        .take(request.length as u64)
        .read_to_end(&mut body)
        .map_err(|_| bad_request("could not read request body"))?;
    if body.len() != request.length {
        return Err(bad_request("incomplete request body"));
    }
    let body = std::str::from_utf8(&body).map_err(|_| bad_request("request body is not UTF-8"))?;

    match endpoint {
        Endpoint::Health => Ok(String::from(r#"{"status":"ok"}"#)),
        Endpoint::Strip => {
            let text = request_text(body)?;
            let result = strip_gutenberg(&text);
            let warning = result
                .warning
                .map_or_else(|| String::from("null"), encode_string);
            Ok(format!(
                "{{\"text\":{},\"removed_lines\":{},\"remaining_chars\":{},\"warning\":{warning}}}",
                encode_string(result.text),
                result.removed_lines,
                result.remaining_chars
            ))
        }
        Endpoint::Count => Ok(count(&request_text(body)?).to_json()),
        Endpoint::Title => request_title(body),
    }
}

fn request_text(body: &str) -> HttpResult<String> {
    let [text] = string_fields(body, ["text"]).ok_or_else(|| bad_request("invalid JSON object"))?;
    text.ok_or_else(|| bad_request("text must be a string"))
}

fn request_title(body: &str) -> HttpResult<String> {
    let [source, json, text] = string_fields(body, ["source", "json", "text"])
        .ok_or_else(|| bad_request("invalid JSON object"))?;
    let title = match source.as_deref() {
        Some("gutendex") => {
            let json = json.ok_or_else(|| bad_request("json must be a string"))?;
            title_from_gutendex(&json)
        }
        Some("text") => {
            let text = text.ok_or_else(|| bad_request("text must be a string"))?;
            title_from_text(&text)
        }
        _ => return Err(bad_request("source must be gutendex or text")),
    }
    .map_err(bad_request)?;
    Ok(format!("{{\"title\":{}}}", encode_string(&title)))
}

fn bad_request(message: &'static str) -> (Status, &'static str) {
    (Status::BadRequest, message)
}
