package com.example.apiasistente.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/**
 * Configuracion de DB Debug.
 */
@Configuration
@ConditionalOnProperty(value = "app.db-debug.enabled", havingValue = "true")
public class DbDebugConfig {

    @Bean
    CommandLineRunner dbDebug(DataSource ds) {
        return args -> {
            try (Connection c = ds.getConnection()) {
                var md = c.getMetaData();
                System.out.println(">>> JDBC URL=" + md.getURL());
                System.out.println(">>> JDBC USER=" + md.getUserName());
                String databaseProduct = md.getDatabaseProductName();

                if (!databaseProduct.toLowerCase(Locale.ROOT).contains("mysql")) {
                    System.out.println(">>> DB DEBUG omite consulta MySQL para motor=" + databaseProduct);
                    return;
                }

                try (var st = c.createStatement();
                     var rs = st.executeQuery("SELECT DATABASE() db, @@hostname host, @@port port, @@version ver, @@sql_mode mode")) {
                    if (!rs.next()) {
                        return;
                    }
                    System.out.println(">>> APP DB=" + rs.getString("db")
                            + " HOST=" + rs.getString("host")
                            + " PORT=" + rs.getInt("port")
                            + " VER=" + rs.getString("ver"));
                    System.out.println(">>> SQL_MODE=" + rs.getString("mode"));
                }
            }
        };
    }
}

