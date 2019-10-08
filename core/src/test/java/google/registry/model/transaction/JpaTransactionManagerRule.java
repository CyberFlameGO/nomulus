// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.transaction;

import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import google.registry.persistence.PersistenceModule;
import google.registry.testing.FakeClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.EntityManagerFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.joda.time.DateTime;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * JUnit Rule to provision {@link JpaTransactionManagerImpl} backed by {@link PostgreSQLContainer}.
 *
 * <p>This rule also replaces the {@link JpaTransactionManagerImpl} provided by {@link
 * TransactionManagerFactory} with the {@link JpaTransactionManagerImpl} generated by the rule
 * itself, so that all SQL queries will be sent to the database instance created by {@link
 * PostgreSQLContainer} to achieve test purpose.
 */
public class JpaTransactionManagerRule extends ExternalResource {
  private static final String SCHEMA_GOLDEN_SQL = "sql/schema/nomulus.golden.sql";

  private final DateTime now = DateTime.now(UTC);
  private final FakeClock clock = new FakeClock(now);
  private final String initScript;
  private final ImmutableList<Class> extraEntityClasses;
  private final ImmutableMap userProperties;

  private JdbcDatabaseContainer database;
  private EntityManagerFactory emf;
  private JpaTransactionManager cachedTm;

  private JpaTransactionManagerRule(
      String initScript,
      ImmutableList<Class> extraEntityClasses,
      ImmutableMap<String, String> userProperties) {
    this.initScript = initScript;
    this.extraEntityClasses = extraEntityClasses;
    this.userProperties = userProperties;
  }

  /** Wraps {@link JpaTransactionManagerRule} in a {@link PostgreSQLContainer}. */
  @Override
  public Statement apply(Statement base, Description description) {
    database = new PostgreSQLContainer().withInitScript(initScript);
    return RuleChain.outerRule(database)
        .around(JpaTransactionManagerRule.super::apply)
        .apply(base, description);
  }

  @Override
  public void before() {
    ImmutableMap properties = PersistenceModule.providesDefaultDatabaseConfigs();
    if (!userProperties.isEmpty()) {
      // If there are user properties, create a new properties object with these added.
      ImmutableMap.Builder builder = properties.builder();
      builder.putAll(userProperties);
      properties = builder.build();
    }

    emf =
        createEntityManagerFactory(
            database.getJdbcUrl(),
            database.getUsername(),
            database.getPassword(),
            properties,
            extraEntityClasses);
    JpaTransactionManagerImpl txnManager = new JpaTransactionManagerImpl(emf, clock);
    cachedTm = TransactionManagerFactory.jpaTm;
    TransactionManagerFactory.jpaTm = txnManager;
  }

  @Override
  public void after() {
    TransactionManagerFactory.jpaTm = cachedTm;
    if (emf != null) {
      emf.close();
    }
    cachedTm = null;
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  private static EntityManagerFactory createEntityManagerFactory(
      String jdbcUrl,
      String username,
      String password,
      ImmutableMap<String, String> configs,
      ImmutableList<Class> extraEntityClasses) {
    HashMap<String, String> properties = Maps.newHashMap(configs);
    properties.put(Environment.URL, jdbcUrl);
    properties.put(Environment.USER, username);
    properties.put(Environment.PASS, password);

    MetadataSources metadataSources =
        new MetadataSources(new StandardServiceRegistryBuilder().applySettings(properties).build());
    extraEntityClasses.forEach(metadataSources::addAnnotatedClass);
    return metadataSources.buildMetadata().getSessionFactoryBuilder().build();
  }

  /** Returns the {@link FakeClock} used by the underlying {@link JpaTransactionManagerImpl}. */
  public FakeClock getTxnClock() {
    return clock;
  }

  /** Builder for {@link JpaTransactionManagerRule}. */
  public static class Builder {
    private String initScript;
    private List<Class> extraEntityClasses = new ArrayList<Class>();
    private Map<String, String> userProperties = new HashMap<String, String>();

    /**
     * Sets the SQL script to be used to initialize the database. If not set,
     * sql/schema/nomulus.golden.sql will be used.
     */
    public Builder withInitScript(String initScript) {
      this.initScript = initScript;
      return this;
    }

    /** Adds annotated class(es) to the known entities for the database. */
    public Builder withEntityClass(Class... classes) {
      this.extraEntityClasses.addAll(ImmutableSet.copyOf(classes));
      return this;
    }

    /** Adds the specified property to those used to initialize the transaction manager. */
    public Builder withProperty(String name, String value) {
      this.userProperties.put(name, value);
      return this;
    }

    /** Builds a {@link JpaTransactionManagerRule} instance. */
    public JpaTransactionManagerRule build() {
      if (initScript == null) {
        initScript = SCHEMA_GOLDEN_SQL;
      }
      return new JpaTransactionManagerRule(
          initScript,
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }
  }
}