package com.serasa.balancas.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reports")
public record ReportProperties(double scarcityThreshold) {
}
