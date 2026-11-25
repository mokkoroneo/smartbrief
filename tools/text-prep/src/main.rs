use std::env;
use std::ffi::OsStr;
use std::fs;
use std::io::{self, Read, Write};
use std::process::ExitCode;

use text_prep::{count, strip_gutenberg, title_from_gutendex, title_from_text};

const USAGE: &str = "usage: text-prep strip-gutenberg [FILE|-]\n       text-prep count [FILE|-]\n       text-prep title (--from-gutendex|--from-text) [FILE|-]";

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
            let result = strip_gutenberg(&input);
            let warning = result
                .warning
                .map_or_else(String::new, |message| format!("warning: {message}, "));
            eprintln!(
                "{warning}removed_lines={}, remaining_chars={}",
                result.removed_lines, result.remaining_chars
            );
            Ok(result.text.to_owned())
        }
        [command, input @ ..] if *command == "count" => {
            let input = read_input(input)?;
            Ok(format!("{}\n", count(&input).to_json()))
        }
        [command, source, input @ ..] if *command == "title" => {
            let extract = match source.to_str() {
                Some("--from-gutendex") => title_from_gutendex,
                Some("--from-text") => title_from_text,
                _ => return Err(USAGE.into()),
            };
            let input = read_input(input)?;
            Ok(format!("{}\n", extract(&input)?))
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
