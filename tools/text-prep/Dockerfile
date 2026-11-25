FROM rust:1.100.0-alpine3.23 AS build
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src/ ./src/
RUN RUSTFLAGS="-C target-feature=+crt-static" cargo build --release --locked --offline --bin text-prep-server

FROM alpine:3.23
COPY --from=build /build/target/release/text-prep-server /usr/local/bin/text-prep-server
EXPOSE 18080
CMD ["text-prep-server", "--bind", "0.0.0.0", "--port", "18080"]
