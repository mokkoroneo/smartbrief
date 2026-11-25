mod json;

use std::env;
use std::ffi::OsStr;
use std::fs;
use std::io::{self, Read, Write};
use std::process::ExitCode;

const USAGE: &str = "usage: text-prep strip-gutenberg [FILE|-]\n       text-prep count [FILE|-]\n       text-prep title (--from-gutendex|--from-text) [FILE|-]";

const START_MARKERS: [&str; 2] = [
    "*** START OF THE PROJECT GUTENBERG EBOOK",
    "*** START OF THIS PROJECT GUTENBERG EBOOK",
];
const END_MARKERS: [&str; 2] = [
    "*** END OF THE PROJECT GUTENBERG EBOOK",
    "*** END OF THIS PROJECT GUTENBERG EBOOK",
];

fn main() -> ExitCode {
    let args: Vec<_> = env::args_os().skip(1).collect();
    let args: Vec<_> = args.iter().map(|arg| arg.as_os_str()).collect();
    let result = run(&args).and_then(|output| {
        let mut stdout = io::stdout().lock();
        stdout
            .write_all(output.as_bytes())
            .and_then(|()| stdout.flush())
            .map_err(|err| format!("stdout: {err}"))
    });
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("text-prep: {err}");
            ExitCode::from(2)
        }
    }
}

fn run(args: &[&OsStr]) -> Result<String, String> {
    match args {
        [command, input @ ..] if *command == "strip-gutenberg" => {
            let input = read_input(input)?;
            Ok(strip_gutenberg(&input).to_owned())
        }
        [command, input @ ..] if *command == "count" => {
            let input = read_input(input)?;
            Ok(format!(
                "{{\"chars\": {}, \"lines\": {}, \"bytes\": {}}}\n",
                input.chars().count(),
                input.lines().count(),
                input.len()
            ))
        }
        [command, source, input @ ..] if *command == "title" => {
            let extract = match source.to_str() {
                Some("--from-gutendex") => json::title,
                Some("--from-text") => text_title,
                _ => return Err(USAGE.into()),
            };
            let input = read_input(input)?;
            let candidate = extract(&input)?;
            Ok(format!("{}\n", validate_title(&candidate)?))
        }
        _ => Err(USAGE.into()),
    }
}

fn read_input(args: &[&OsStr]) -> Result<String, String> {
    match args {
        [path] if *path != "-" => {
            fs::read_to_string(path).map_err(|err| format!("{path:?}: {err}"))
        }
        [] | [_] => {
            let mut input = String::new();
            io::stdin()
                .lock()
                .read_to_string(&mut input)
                .map_err(|err| format!("stdin: {err}"))?;
            Ok(input)
        }
        _ => Err(USAGE.into()),
    }
}

fn has_marker(line: &str, markers: [&str; 2]) -> bool {
    markers.iter().any(|marker| {
        line.as_bytes()
            .windows(marker.len())
            .any(|part| part.eq_ignore_ascii_case(marker.as_bytes()))
    })
}

fn strip_gutenberg(input: &str) -> &str {
    let mut start = None;
    let mut end = None;
    let mut offset = 0;
    for line in input.split_inclusive('\n') {
        if start.is_none() && has_marker(line, START_MARKERS) {
            start = Some((offset, offset + line.len()));
        }
        if has_marker(line, END_MARKERS) {
            end = Some(offset);
        }
        offset += line.len();
    }

    let (body, warning) = match (start, end) {
        (None, None) => (input, "warning: no Gutenberg markers found, "),
        (Some((start_line, _)), Some(end_line)) if start_line > end_line => {
            (input, "warning: Gutenberg START follows END, ")
        }
        (Some((start_line, _)), Some(end_line)) if start_line == end_line => ("", ""),
        (start, end) => {
            let body_start = start.map_or(0, |(_, after_marker)| after_marker);
            (&input[body_start..end.unwrap_or(input.len())], "")
        }
    };

    eprintln!(
        "{warning}removed_lines={}, remaining_chars={}",
        input.lines().count() - body.lines().count(),
        body.chars().count()
    );
    body
}

fn text_title(input: &str) -> Result<String, &'static str> {
    input
        .lines()
        .take_while(|line| !has_marker(line, START_MARKERS))
        .find_map(|line| line.strip_prefix("Title:"))
        .map(str::to_owned)
        .ok_or("missing Title: line in Gutenberg header")
}

fn validate_title(candidate: &str) -> Result<&str, &'static str> {
    let mut title = candidate.trim();
    for quote in ['"', '\''] {
        if let Some(unquoted) = title
            .strip_prefix(quote)
            .and_then(|rest| rest.strip_suffix(quote))
        {
            title = unquoted.trim();
            break;
        }
    }
    if title.is_empty() || title.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("title is empty or contains only ASCII digits");
    }
    if title.contains(['\r', '\n']) {
        return Err("title contains an embedded line break");
    }
    Ok(title)
}
