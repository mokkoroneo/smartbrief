package jp.smartbrief.billing.shared.config;

import java.util.Objects; // ★追加

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.lang.NonNull; // ★追加

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import jp.smartbrief.billing.catalog.repository.WorkRepository;

/**
 * Primary データベースの R2DBC 設定クラス
 * 
 * このクラスは、プライマリデータベースへの非同期リアクティブなデータベース接続を設定します。
 * コネクション管理、R2dbcEntityTemplate の設定、および Aozora 以外のリポジトリに対する
 * R2DBC リポジトリ操作を有効化します。
 */
@Configuration
@EnableR2dbcAuditing
@EnableR2dbcRepositories(
    // ★修正：プロジェクト全体（jp.smartbrief.billing）をスキャン対象にする
    // これにより catalog.repository と identity.repository の両方が見つかるようになります
    basePackages = "jp.smartbrief.billing",
    entityOperationsRef = "primaryEntityTemplate",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        value = WorkRepository.class)
)
public class R2dbcPrimaryConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.r2dbc")
    public R2dbcProperties primaryR2dbcProperties() {
        return new R2dbcProperties();
    }

    @Bean(name = "connectionFactory")
    @Primary
    public ConnectionFactory connectionFactory(@Qualifier("primaryR2dbcProperties") R2dbcProperties properties) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(properties.getUrl());
        ConnectionFactoryOptions.Builder builder = options.mutate();
        if (properties.getUsername() != null) {
            builder.option(ConnectionFactoryOptions.USER, properties.getUsername());
        }
        if (properties.getPassword() != null) {
            builder.option(ConnectionFactoryOptions.PASSWORD, properties.getPassword());
        }
        return ConnectionFactories.get(builder.build());
    }

    // ★修正箇所: ConnectionFactory を直接渡す形に変更
    @Bean(name = "primaryEntityTemplate")
    @Primary
    public R2dbcEntityTemplate primaryEntityTemplate(
            @Qualifier("connectionFactory") @NonNull ConnectionFactory connectionFactory) {        
        
        return new R2dbcEntityTemplate(Objects.requireNonNull(connectionFactory, "connectionFactory must not be null"));
    }
}