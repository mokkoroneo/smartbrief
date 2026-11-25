mod json;

pub use json::{encode_string, string_fields};

const START_MARKERS: [&str; 2] = [
    "*** START OF THE PROJECT GUTENBERG EBOOK",
    "*** START OF THIS PROJECT GUTENBERG EBOOK",
];
const END_MARKERS: [&str; 2] = [
    "*** END OF THE PROJECT GUTENBERG EBOOK",
    "*** END OF THIS PROJECT GUTENBERG EBOOK",
];

pub struct StripResult<'a> {
    pub text: &'a str,
    pub removed_lines: usize,
    pub remaining_chars: usize,
    pub warning: Option<&'static str>,
}

pub struct Counts {
    pub chars: usize,
    pub lines: usize,
    pub bytes: usize,
}

impl Counts {
    pub fn to_json(&self) -> String {
        format!(
            "{{\"chars\": {}, \"lines\": {}, \"bytes\": {}}}",
            self.chars, self.lines, self.bytes
        )
    }
}

pub fn count(input: &str) -> Counts {
    Counts {
        chars: input.chars().count(),
        lines: input.lines().count(),
        bytes: input.len(),
    }
}

fn has_marker(line: &str, markers: [&str; 2]) -> bool {
    markers.iter().any(|marker| {
        line.as_bytes()
            .windows(marker.len())
            .any(|part| part.eq_ignore_ascii_case(marker.as_bytes()))
    })
}

pub fn strip_gutenberg(input: &str) -> StripResult<'_> {
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

    let (text, warning) = match (start, end) {
        (None, None) => (input, Some("no Gutenberg markers found")),
        (Some((start_line, _)), Some(end_line)) if start_line > end_line => {
            (input, Some("Gutenberg START follows END"))
        }
        (Some((start_line, _)), Some(end_line)) if start_line == end_line => ("", None),
        (start, end) => {
            let body_start = start.map_or(0, |(_, after_marker)| after_marker);
            (&input[body_start..end.unwrap_or(input.len())], None)
        }
    };

    StripResult {
        text,
        removed_lines: input.lines().count() - text.lines().count(),
        remaining_chars: text.chars().count(),
        warning,
    }
}

pub fn title_from_gutendex(input: &str) -> Result<String, &'static str> {
    validate_title(&json::title(input)?).map(str::to_owned)
}

pub fn title_from_text(input: &str) -> Result<String, &'static str> {
    let candidate = input
        .lines()
        .take_while(|line| !has_marker(line, START_MARKERS))
        .find_map(|line| line.strip_prefix("Title:"))
        .ok_or("missing Title: line in Gutenberg header")?;
    validate_title(candidate).map(str::to_owned)
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
