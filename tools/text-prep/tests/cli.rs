use std::io::Write;
use std::path::Path;
use std::process::{Command, Output, Stdio};

const BOOKS: [(&str, &str); 4] = [
    ("grimm", "Grimms' Fairy Tales"),
    ("du-bois", "The Souls of Black Folk"),
    ("hugo", "Les Misérables"),
    ("austen", "Sense and Sensibility"),
];

fn fixture(args: &[&str], file: &str) -> Output {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(file);
    Command::new(env!("CARGO_BIN_EXE_text-prep"))
        .args(args)
        .arg(path)
        .output()
        .unwrap()
}

fn stdin(args: &[&str], input: &[u8]) -> Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_text-prep"))
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    child.wait_with_output().unwrap()
}

fn success(output: &Output, expected: &[u8]) {
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(output.stdout, expected);
}

fn rejected(output: &Output) {
    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(output.stdout.is_empty(), "{output:?}");
    assert!(!output.stderr.is_empty());
}

#[test]
fn strip_removes_header_and_footer_and_reports_counts() {
    let output = fixture(&["strip-gutenberg"], "grimm.txt");
    success(&output, b"Fixture body for Grimm.\n");
    assert_eq!(output.stderr, b"removed_lines=7, remaining_chars=24\n");
}

#[test]
fn strip_preserves_markers_inside_the_body() {
    let output = fixture(&["strip-gutenberg"], "markers-in-body.txt");
    success(
        &output,
        b"Body starts.\nAn early *** END OF THE PROJECT GUTENBERG EBOOK mention.\n\
          A later *** START OF THE PROJECT GUTENBERG EBOOK mention.\nBody ends.\n",
    );
}

#[test]
fn strip_passes_through_text_without_markers_and_warns_once() {
    let output = fixture(&["strip-gutenberg"], "no-markers.txt");
    success(&output, include_bytes!("fixtures/no-markers.txt"));
    let diagnostics = std::str::from_utf8(&output.stderr).unwrap();
    assert_eq!(diagnostics.lines().count(), 1);
    assert!(diagnostics.starts_with("warning: no Gutenberg markers found"));
    assert!(diagnostics.contains("removed_lines=0, remaining_chars="));
}

#[test]
fn strip_accepts_a_lowercase_this_start_without_an_end() {
    let output = fixture(&["strip-gutenberg"], "start-only.txt");
    success(&output, b"Body\n");
    assert_eq!(output.stderr, b"removed_lines=2, remaining_chars=5\n");
}

#[test]
fn strip_accepts_an_end_without_a_start() {
    let output = stdin(
        &["strip-gutenberg", "-"],
        b"Body\n*** end of this project gutenberg ebook ***\nFooter",
    );
    success(&output, b"Body\n");
    assert_eq!(output.stderr, b"removed_lines=2, remaining_chars=5\n");
}

#[test]
fn strip_passes_through_reversed_markers_and_warns() {
    let input = b"*** END OF THE PROJECT GUTENBERG EBOOK ***\n\
                  Body\n*** START OF THE PROJECT GUTENBERG EBOOK ***";
    let output = stdin(&["strip-gutenberg"], input);
    success(&output, input);
    assert!(output
        .stderr
        .starts_with(b"warning: Gutenberg START follows END, removed_lines=0"));
}

#[test]
fn strip_preserves_crlf_byte_for_byte() {
    let output = fixture(&["strip-gutenberg"], "crlf.txt");
    success(&output, b"First body line\r\nSecond body line\r\n");
}

#[test]
fn count_unicode_scalars_differs_from_javascript_utf16_length() {
    let input = include_str!("fixtures/count.txt");
    let output = fixture(&["count"], "count.txt");
    success(&output, b"{\"chars\": 5, \"lines\": 1, \"bytes\": 12}\n");
    assert_eq!(input.encode_utf16().count(), 6);
}

#[test]
fn count_handles_empty_input_and_unterminated_lines() {
    success(
        &stdin(&["count"], b""),
        b"{\"chars\": 0, \"lines\": 0, \"bytes\": 0}\n",
    );
    success(
        &stdin(&["count"], b"a\r\nb"),
        b"{\"chars\": 4, \"lines\": 2, \"bytes\": 4}\n",
    );
}

#[test]
fn title_extracts_four_original_titles_from_gutendex() {
    for (book, title) in BOOKS {
        let output = fixture(&["title", "--from-gutendex"], &format!("{book}.json"));
        success(&output, format!("{title}\n").as_bytes());
    }
}

#[test]
fn title_extracts_four_original_titles_from_text() {
    for (book, title) in BOOKS {
        let output = fixture(&["title", "--from-text"], &format!("{book}.txt"));
        success(&output, format!("{title}\n").as_bytes());
    }
}

#[test]
fn title_and_strip_round_trip_japanese_utf8_bytes() {
    let expected = include_bytes!("fixtures/japanese-title.out");
    success(
        &fixture(&["title", "--from-text"], "japanese-title.txt"),
        expected,
    );
    success(
        &fixture(&["strip-gutenberg"], "japanese-title.txt"),
        expected,
    );
}

#[test]
fn title_rejects_blank_and_ascii_numeric_candidates_in_both_formats() {
    for (text, json) in [
        ("0", r#""0""#),
        ("", r#""""#),
        (" \t ", r#"" \t ""#),
        ("12345", r#""12345""#),
        (r#""0""#, r#""\"0\"""#),
        (" ' 1984 ' ", r#"" ' 1984 ' ""#),
    ] {
        let text = format!("Title: {text}\n*** START OF THE PROJECT GUTENBERG EBOOK ***\n");
        rejected(&stdin(&["title", "--from-text"], text.as_bytes()));
        let json = format!(r#"{{"title":{json}}}"#);
        rejected(&stdin(&["title", "--from-gutendex"], json.as_bytes()));
    }
}

#[test]
fn title_trims_and_removes_only_one_matching_quote_pair() {
    for (text, expected) in [
        ("  '  Les Misérables  '  ", "Les Misérables\n"),
        ("  \"Example\"  ", "Example\n"),
        ("\"Example", "\"Example\n"),
        ("Example'", "Example'\n"),
        ("'\"0\"'", "\"0\"\n"),
        ("１９８４", "１９８４\n"),
    ] {
        let input = format!("Title: {text}\n");
        success(
            &stdin(&["title", "--from-text"], input.as_bytes()),
            expected.as_bytes(),
        );
    }
}

#[test]
fn title_uses_only_the_single_header_line() {
    rejected(&stdin(
        &["title", "--from-text"],
        b"Header\n*** start of this project gutenberg ebook ***\nTitle: Body\n",
    ));
    success(
        &stdin(
            &["title", "--from-text"],
            b"Title: First line\n       continuation\n\
              *** START OF THE PROJECT GUTENBERG EBOOK ***\n",
        ),
        b"First line\n",
    );
}

#[test]
fn title_decodes_json_escapes_and_surrogate_pairs() {
    let input = br#"{"meta":{"title":"nested","values":[null,true,false,-0.2e+9,{},[]]},
                      "\u0074itle":"\n\rA\"B\\C\/D\b\f\t\uD83D\uDE00\r\n"}"#;
    let output = stdin(&["title", "--from-gutendex"], input);
    success(&output, "A\"B\\C/D\u{8}\u{c}\t😀\n".as_bytes());
}

#[test]
fn title_rejects_json_outside_the_single_book_contract() {
    for input in [
        r#"{"results":[{"title":"Nested title"}]}"#,
        r#"[{"title":"Array entry"}]"#,
        r#"{"title":0}"#,
        r#"{"title":null}"#,
        r#"{"title":"Bad \q"}"#,
        r#"{"title":"Bad \uD83D"}"#,
        r#"{"title":"Bad \uDE00"}"#,
        r#"{"title":"Bad \uD83D\u0041"}"#,
        r#"{"title":"First\nSecond"}"#,
        r#"{"title":"First\rSecond"}"#,
        r#"{"title":"Title"} trailing data"#,
        r#"{"title":"Title",}"#,
        r#"{"title":"Title","meta":[01]}"#,
        r#"{"title":"Title","meta":[1,]}"#,
    ] {
        rejected(&stdin(&["title", "--from-gutendex"], input.as_bytes()));
    }
}

#[test]
fn all_input_modes_reject_invalid_utf8() {
    for args in [
        &["strip-gutenberg"][..],
        &["count"][..],
        &["title", "--from-text"][..],
        &["title", "--from-gutendex"][..],
    ] {
        rejected(&stdin(args, b"Title: invalid \xff\n"));
    }
}
