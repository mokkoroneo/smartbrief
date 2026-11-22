package jp.smartbrief.billing.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import jp.smartbrief.billing.shared.security.AuthenticationManager;
import jp.smartbrief.billing.shared.security.SecurityContextRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 🛡️ セキュリティ基盤設定クラス (Spring WebFlux Security)
 * * 【主な特徴】
 * 1. ステートレス認証: JWTを利用し、サーバー側にセッションを持たないモダンなSaaS構成。
 * 2. HttpOnly Cookie対応: XSS攻撃を防ぐため、Cookieベースの認証をサポート。
 * 3. 疎結合な認証ロジック: 自作の AuthenticationManager と SecurityContextRepository を
 * インジェクトすることで、Spring Securityの標準フローに独自のJWT検証ロジックを統合。
 * 4. 最小権限の原則: 公開API以外はデフォルトで認証を必須とし、セキュリティホールを最小化。
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    /**
     * パスワードのハッシュ化アルゴリズムを定義
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * セキュリティフィルターチェーンの設定
     * リクエストの認可、CORS、認証マネージャーの登録を行う。
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            // --- 基本セキュリティ設定 ---
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            
            // --- 独自認証エンジンを登録 ---
            // JWTの抽出(Repository)と検証(Manager)を担当
            .authenticationManager(authenticationManager)
            .securityContextRepository(securityContextRepository)
            
            // --- ルーティングごとの認可設定 ---
            .authorizeExchange(exchanges -> exchanges
                // 1. プリフライトリクエスト(OPTIONS)は常に許可
                .pathMatchers(HttpMethod.OPTIONS).permitAll()
                
                // 2. 公開エンドポイント（ログイン、Webhook、一般公開情報）
                .pathMatchers("/api/v1/auth/**").permitAll()
                .pathMatchers("/api/v1/stripe/webhook").permitAll() 
                .pathMatchers(
                    "/api/v1/books/ranking", 
                    "/api/v1/books/authors/**", 
                    "/api/v1/books/genres"
                ).permitAll()
                
                // 3. それ以外はすべて要ログイン
                .anyExchange().authenticated()
            )
            
            // --- エラーハンドリング ---
            // 認証失敗時(401)と権限不足時(403)のレスポンスを制御
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((swe, e) -> Mono.fromRunnable(() -> 
                    swe.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED)))
                .accessDeniedHandler((swe, e) -> Mono.fromRunnable(() -> 
                    swe.getResponse().setStatusCode(HttpStatus.FORBIDDEN)))
            )
            .build();
    }

    /**
     * CORS (Cross-Origin Resource Sharing) 設定
     * フロントエンド（React/Vite）からの安全なリクエストを許可する。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 開発環境(5173, 3000)と本番環境を許可
        configuration.setAllowedOrigins(List.of(
            "http://localhost:5173", 
            "http://localhost:3000", 
            "https://smartbrief.jp"
        ));
        
        // 使用を許可するHTTPメソッド
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // 送信を許可するヘッダー（Cookieを含む通信に必須）
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cookie", "x-requested-with"));
        
        // Cookieの送受信を許可
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}