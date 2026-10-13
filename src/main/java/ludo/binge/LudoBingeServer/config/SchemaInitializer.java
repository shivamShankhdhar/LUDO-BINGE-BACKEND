package ludo.binge.LudoBingeServer.config;

/** Schema ownership belongs to Flyway. Startup must never drop account tables or replay migrations. */
public final class SchemaInitializer {
    private SchemaInitializer() { }
}
