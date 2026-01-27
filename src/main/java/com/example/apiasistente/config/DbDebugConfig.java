package com.example.apiasistente.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

@Configuration
public class DbDebugConfig {

    @Bean
    CommandLineRunner dbDebug(DataSource ds) {
        return args -> {
            try (Connection c = ds.getConnection()) {
                var md = c.getMetaData();
                System.out.println(">>> JDBC URL=" + md.getURL());
                System.out.println(">>> JDBC USER=" + md.getUserName());

                try (var st = c.createStatement();
                     var rs = st.executeQuery("SELECT DATABASE() db, @@hostname host, @@port port, @@version ver, @@sql_mode mode")) {
                    if (rs.next()) {
                        System.out.println(">>> APP DB=" + rs.getString("db")
                                + " HOST=" + rs.getString("host")
                                + " PORT=" + rs.getInt("port")
                                + " VER=" + rs.getString("ver"));
                        System.out.println(">>> SQL_MODE=" + rs.getString("mode"));
                    }
                }
            }
        };
    }
}
