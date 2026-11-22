package jp.smartbrief.billing.shared.config;

import java.time.Duration;
import java.util.Objects; // ★追加：実行時のNullチェック用

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.lang.NonNull;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import jp.smartbrief.billing.catalog.repository.WorkRepository;

/**
 * Aozora データベースの R2DBC 設定クラス
 * 
 * このクラスは、Aozora データベースへの非同期リアクティブなデータベース接続を設定します。
 * コネクションプールを管理し、WorkRepository に対する R2DBC リポジトリ操作を有効化します。
 * 
 * 機能：
 * - アプリケーション設定ファイルから Aozora DB の接続情報を読み込み
 * - R2DBC コネクションプールの初期化・最大接続数・アイドルタイムアウトを設定
 * - R2dbcEntityTemplate を利用したリアクティブなデータベース操作を提供
 */
@Configuration
@EnableR2dbcRepositories(
    // ★修正：新しいリポジトリのパッケージパスに変更
    basePackages = "jp.smartbrief.billing.catalog.repository", 
    entityOperationsRef = "aozoraEntityTemplate",
    includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        value = WorkRepository.class)
)
public class R2dbcAozoraConfig {

    @Bean
    @ConfigurationProperties(prefix = "aozora.r2dbc")
    public AozoraR2dbcProperties aozoraR2dbcProperties() {
        return new AozoraR2dbcProperties();
    }
    
    @Bean(name = "aozoraConnectionFactory")
    public ConnectionFactory aozoraConnectionFactory(AozoraR2dbcProperties properties) {
        // 1. 基本的な接続設定を作成
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(properties.getUrl());
        ConnectionFactoryOptions.Builder builder = options.mutate();
        
        if (properties.getUsername() != null) {
            builder.option(ConnectionFactoryOptions.USER, properties.getUsername());
        }
        if (properties.getPassword() != null) {
            builder.option(ConnectionFactoryOptions.PASSWORD, properties.getPassword());
        }

        // 元のConnectionFactory（プールなし）
        ConnectionFactory connectionFactory = ConnectionFactories.get(builder.build());

        // ★★★ 2. ここでプールを作成してラップする（これが修正の核心！） ★★★
        ConnectionPoolConfiguration poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
                .initialSize(properties.getPool().getInitialSize())
                .maxSize(properties.getPool().getMaxSize())
                .maxIdleTime(properties.getPool().getMaxIdleTime())
                .build();

        return new ConnectionPool(poolConfig);
    }

    @Bean(name = "aozoraEntityTemplate")
    public R2dbcEntityTemplate aozoraEntityTemplate(@Qualifier("aozoraConnectionFactory") @NonNull ConnectionFactory connectionFactory) {
        // ★修正：Objects.requireNonNull で「絶対にnullではない」ことをコンパイラに保証させてから渡す
        return new R2dbcEntityTemplate(Objects.requireNonNull(connectionFactory, "ConnectionFactory must not be null"));
    }

    // 設定値を読み取るためのクラス
    public static class AozoraR2dbcProperties {
        private String url;
        private String username;
        private String password;
        // Pool設定を受け取るためのクラスを追加
        private Pool pool = new Pool(); 

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Pool getPool() { return pool; }
        public void setPool(Pool pool) { this.pool = pool; }

        public static class Pool {
            private int initialSize = 5;
            private int maxSize = 20; // デフォルト上限を設定
            private Duration maxIdleTime = Duration.ofMinutes(30);

            public int getInitialSize() { return initialSize; }
            public void setInitialSize(int initialSize) { this.initialSize = initialSize; }
            public int getMaxSize() { return maxSize; }
            public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
            public Duration getMaxIdleTime() { return maxIdleTime; }
            public void setMaxIdleTime(Duration maxIdleTime) { this.maxIdleTime = maxIdleTime; }
        }
    }
}