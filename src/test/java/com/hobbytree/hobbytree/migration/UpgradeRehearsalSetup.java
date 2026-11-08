package com.hobbytree.hobbytree.migration;

import org.flywaydb.core.Flyway;

/** Creates the API regression schema via V5 then latest, only on a dedicated localhost DB. */
public final class UpgradeRehearsalSetup {
  private UpgradeRehearsalSetup() {}

  public static void main(String[] args) {
    String url = System.getenv("DB_URL");
    if (url == null
        || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/hobbytree_api_rehearsal")) {
      throw new IllegalArgumentException("Dedicated localhost API rehearsal database required");
    }
    var initial =
        Flyway.configure()
            .dataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .target("5")
            .load();
    if (initial.migrate().migrationsExecuted != 5)
      throw new IllegalStateException("Fresh V5 fixture required");
    initial.validate();
    var upgrade =
        Flyway.configure().configuration(initial.getConfiguration()).target("latest").load();
    int pending = upgrade.info().pending().length;
    if (pending <= 0 || upgrade.migrate().migrationsExecuted != pending)
      throw new IllegalStateException("Upgrade did not run");
    upgrade.validate();
    if (upgrade.migrate().migrationsExecuted != 0)
      throw new IllegalStateException("Unexpected pending migration");
    System.out.println("API_REHEARSAL_SCHEMA=V5_TO_" + upgrade.info().current().getVersion());
  }
}
