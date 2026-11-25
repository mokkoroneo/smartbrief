pub fn title(input: &str) -> Result<String, &'static str> {
    let mut parser = Parser { rest: input };
    let title = parser.book().ok_or("invalid Gutendex JSON object")?;
    title.ok_or("Gutendex object is missing a string title")
}

struct Parser<'a> {
    rest: &'a str,
}

enum Container {
    Array,
    Object,
}

impl Parser<'_> {
    fn book(&mut self) -> Option<Option<String>> {
        self.token('{')?;
        let mut title = None;
        if self.token('}').is_none() {
            loop {
                let key = self.member_key()?;
                if key == "title" && self.rest.starts_with('"') {
                    title = Some(self.string()?);
                } else {
                    self.skip_value()?;
                    if key == "title" {
                        title = None;
                    }
                }
                if self.token('}').is_some() {
                    break;
                }
                self.token(',')?;
            }
        }
        self.whitespace();
        self.rest.is_empty().then_some(title)
    }

    fn whitespace(&mut self) {
        self.rest = self.rest.trim_start_matches([' ', '\t', '\n', '\r']);
    }

    fn token(&mut self, token: char) -> Option<()> {
        self.whitespace();
        self.rest = self.rest.strip_prefix(token)?;
        Some(())
    }

    fn member_key(&mut self) -> Option<String> {
        self.whitespace();
        let key = self.string()?;
        self.token(':')?;
        self.whitespace();
        Some(key)
    }

    fn skip_value(&mut self) -> Option<()> {
        // Keep nesting on the heap so deeply nested metadata does not grow the call stack.
        let mut containers = Vec::new();
        loop {
            self.whitespace();
            match self.rest.as_bytes().first()? {
                b'[' => {
                    self.token('[')?;
                    if self.token(']').is_none() {
                        containers.push(Container::Array);
                        continue;
                    }
                }
                b'{' => {
                    self.token('{')?;
                    if self.token('}').is_none() {
                        containers.push(Container::Object);
                        self.member_key()?;
                        continue;
                    }
                }
                b'"' => {
                    self.string()?;
                }
                b't' => self.rest = self.rest.strip_prefix("true")?,
                b'f' => self.rest = self.rest.strip_prefix("false")?,
                b'n' => self.rest = self.rest.strip_prefix("null")?,
                b'-' | b'0'..=b'9' => self.number()?,
                _ => return None,
            }

            while let Some(container) = containers.last() {
                let closing = match container {
                    Container::Array => ']',
                    Container::Object => '}',
                };
                if self.token(closing).is_some() {
                    containers.pop();
                } else {
                    self.token(',')?;
                    if matches!(container, Container::Object) {
                        self.member_key()?;
                    }
                    break;
                }
            }
            if containers.is_empty() {
                return Some(());
            }
        }
    }

    fn number(&mut self) -> Option<()> {
        if let Some(rest) = self.rest.strip_prefix('-') {
            self.rest = rest;
        }
        if let Some(rest) = self.rest.strip_prefix('0') {
            self.rest = rest;
        } else {
            self.digits()?;
        }
        if let Some(rest) = self.rest.strip_prefix('.') {
            self.rest = rest;
            self.digits()?;
        }
        if let Some(rest) = self.rest.strip_prefix(['e', 'E']) {
            self.rest = rest.strip_prefix(['+', '-']).unwrap_or(rest);
            self.digits()?;
        }
        Some(())
    }

    fn digits(&mut self) -> Option<()> {
        let rest = self.rest.trim_start_matches(|ch: char| ch.is_ascii_digit());
        let consumed = rest.len() < self.rest.len();
        self.rest = rest;
        consumed.then_some(())
    }

    fn string(&mut self) -> Option<String> {
        self.rest = self.rest.strip_prefix('"')?;
        let mut value = String::new();
        while let Some(ch) = self.next_char() {
            match ch {
                '"' => return Some(value),
                '\\' => value.push(self.escape()?),
                '\0'..='\u{1f}' => return None,
                _ => value.push(ch),
            }
        }
        None
    }

    fn next_char(&mut self) -> Option<char> {
        let ch = self.rest.chars().next()?;
        self.rest = &self.rest[ch.len_utf8()..];
        Some(ch)
    }

    fn escape(&mut self) -> Option<char> {
        match self.next_char()? {
            ch @ ('"' | '\\' | '/') => Some(ch),
            'b' => Some('\u{8}'),
            'f' => Some('\u{c}'),
            'n' => Some('\n'),
            'r' => Some('\r'),
            't' => Some('\t'),
            'u' => self.unicode_escape(),
            _ => None,
        }
    }

    fn unicode_escape(&mut self) -> Option<char> {
        let first = self.hex_quad()?;
        if (0xd800..=0xdbff).contains(&first) {
            self.rest = self.rest.strip_prefix("\\u")?;
            let second = self.hex_quad()?;
            if !(0xdc00..=0xdfff).contains(&second) {
                return None;
            }
            char::from_u32(0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00))
        } else {
            char::from_u32(first)
        }
    }

    fn hex_quad(&mut self) -> Option<u32> {
        let hex = self.rest.get(..4)?;
        if !hex.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return None;
        }
        self.rest = &self.rest[4..];
        u32::from_str_radix(hex, 16).ok()
    }
}
