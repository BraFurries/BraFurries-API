package com.Brafurries.API;

import com.Brafurries.API.repository.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class UserQueryStartupProfilingTest {

    @Test
    void changesOnlyTheExpectedRepositoryFactoryWithoutChangingItsDefinition() {
        var beans = new DefaultListableBeanFactory();
        beans.setAllowBeanDefinitionOverriding(false);
        var user = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
        user.getConstructorArgumentValues().addGenericArgumentValue(UserRepository.class);
        user.setLazyInit(true);
        user.getPropertyValues().add("escapeCharacter", '!');
        var other = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
        beans.registerBeanDefinition("userRepository", user);
        beans.registerBeanDefinition("eventRepository", other);

        beans.getMergedBeanDefinition("userRepository");
        UserQueryStartupProfiling.install(beans);

        assertNotSame(user, beans.getBeanDefinition("userRepository"));
        user = (RootBeanDefinition) beans.getBeanDefinition("userRepository");
        assertEquals(UserQueryStartupProfiling.ProfiledFactoryBean.class.getName(), user.getBeanClassName());
        assertEquals(UserQueryStartupProfiling.ProfiledFactoryBean.class.getName(),
            beans.getMergedBeanDefinition("userRepository").getBeanClassName());
        assertEquals(JpaRepositoryFactoryBean.class.getName(), other.getBeanClassName());
        assertTrue(user.isLazyInit());
        assertEquals('!', user.getPropertyValues().get("escapeCharacter"));
        assertEquals(UserRepository.class, user.getConstructorArgumentValues().getGenericArgumentValues().getFirst().getValue());
    }

    @Test
    void skipsUnexpectedFactoryAndMissingDefinition() {
        var beans = new DefaultListableBeanFactory();
        assertDoesNotThrow(() -> UserQueryStartupProfiling.install(beans));
        beans.registerBeanDefinition("userRepository", new RootBeanDefinition(Object.class));
        UserQueryStartupProfiling.install(beans);
        assertEquals(Object.class.getName(), beans.getBeanDefinition("userRepository").getBeanClassName());
    }

    @Test
    void optOutLeavesFactoryUntouched() {
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty(UserQueryStartupProfiling.ENABLED, "false"));
            var definition = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
            definition.setLazyInit(true);
            context.registerBeanDefinition("userRepository", definition);
            new UserQueryStartupProfiling().initialize(context);
            assertTrue(context.getBeanFactoryPostProcessors().isEmpty());
            assertEquals(JpaRepositoryFactoryBean.class.getName(), definition.getBeanClassName());
        }
    }

    @Test
    void reportsUnavailableCountersWithoutInventingMeasurements() {
        assertEquals(-1, UserQueryStartupProfiling.delta(-1, 10));
        assertEquals(-1, UserQueryStartupProfiling.delta(10, 9));
        assertEquals(15, UserQueryStartupProfiling.delta(5, 20));
        assertEquals(2, UserQueryStartupProfiling.millisDelta(1_000_000, 3_000_000));
    }
}
