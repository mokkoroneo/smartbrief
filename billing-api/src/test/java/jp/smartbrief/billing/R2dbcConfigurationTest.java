package jp.smartbrief.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import jp.smartbrief.billing.catalog.repository.UserBookHistoryRepository;
import jp.smartbrief.billing.catalog.repository.UserFavoriteRepository;
import jp.smartbrief.billing.catalog.repository.WorkRepository;

@SpringBootTest
@TestPropertySource(properties = {
    "SPRING_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "SPRING_R2DBC_USERNAME=root",
    "SPRING_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "AOZORA_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "AOZORA_R2DBC_USERNAME=root",
    "AOZORA_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "JWT_SECRET_KEY=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLXB1cnBvc2Utb25seQ==",
    "JWT_EXPIRATION=3600000",
    "STRIPE_API_KEY=test-stripe-api-key-for-testing-only",
    "stripe.api.key=test-stripe-api-key-for-testing-only",
    "STRIPE_PRICE_ID=price_test_dummy_for_testing",
    "STRIPE_WEBHOOK_SECRET=whsec_test_secret_for_integration_test"
})
class R2dbcConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void registersEachCatalogRepositoryOnce() {
        assertThat(context.getBeanNamesForType(WorkRepository.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(UserBookHistoryRepository.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(UserFavoriteRepository.class)).hasSize(1);
    }
}
